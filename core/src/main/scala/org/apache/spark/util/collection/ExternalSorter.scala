/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.util.collection

import java.io._
import java.util.Comparator

import scala.collection.mutable.ArrayBuffer
import scala.collection.mutable

import com.google.common.io.ByteStreams

import org.apache.spark._
import org.apache.spark.serializer.{DeserializationStream, Serializer}
import org.apache.spark.storage.BlockId
import org.apache.spark.executor.ShuffleWriteMetrics

/**
 * Sorts and potentially merges a number of key-value pairs of type (K, V) to produce key-combiner
 * pairs of type (K, C). Uses a Partitioner to first group the keys into partitions, and then
 * optionally sorts keys within each partition using a custom Comparator. Can output a single
 * partitioned file with a different byte range for each partition, suitable for shuffle fetches.
 *
 * If combining is disabled, the type C must equal V -- we'll cast the objects at the end.
 *
 * @param aggregator optional Aggregator with combine functions to use for merging data
 * @param partitioner optional Partitioner; if given, sort by partition ID and then key
 * @param ordering optional Ordering to sort keys within each partition; should be a total ordering
 * @param serializer serializer to use when spilling to disk
 *
 * Note that if an Ordering is given, we'll always sort using it, so only provide it if you really
 * want the output keys to be sorted. In a map task without map-side combine for example, you
 * probably want to pass None as the ordering to avoid extra sorting. On the other hand, if you do
 * want to do combining, having an Ordering is more efficient than not having it.
 *
 * At a high level, this class works as follows:
 *
 * - We repeatedly fill up buffers of in-memory data, using either a SizeTrackingAppendOnlyMap if
 *   we want to combine by key, or an simple SizeTrackingBuffer if we don't. Inside these buffers,
 *   we sort elements of type ((Int, K), C) where the Int is the partition ID. This is done to
 *   avoid calling the partitioner multiple times on the same key (e.g. for RangePartitioner).
 *
 * - When each buffer reaches our memory limit, we spill it to a file. This file is sorted first
 *   by partition ID and possibly second by key or by hash code of the key, if we want to do
 *   aggregation. For each file, we track how many objects were in each partition in memory, so we
 *   don't have to write out the partition ID for every element.
 *
 * - When the user requests an iterator, the spilled files are merged, along with any remaining
 *   in-memory data, using the same sort order defined above (unless both sorting and aggregation
 *   are disabled). If we need to aggregate by key, we either use a total ordering from the
 *   ordering parameter, or read the keys with the same hash code and compare them with each other
 *   for equality to merge values.
 *
 * - Users are expected to call stop() at the end to delete all the intermediate files.
 */
private[spark] class ExternalSorter[K, V, C](
    aggregator: Option[Aggregator[K, V, C]] = None,
    partitioner: Option[Partitioner] = None,
    ordering: Option[Ordering[K]] = None,
    serializer: Option[Serializer] = None) extends Logging {

  private val numPartitions = partitioner.map(_.numPartitions).getOrElse(1)
  private val shouldPartition = numPartitions > 1

  private val blockManager = SparkEnv.get.blockManager
  private val diskBlockManager = blockManager.diskBlockManager
  private val shuffleMemoryManager = SparkEnv.get.shuffleMemoryManager
  private val ser = Serializer.getSerializer(serializer)
  private val serInstance = ser.newInstance()

  private val conf = SparkEnv.get.conf
  private val spillingEnabled = conf.getBoolean("spark.shuffle.spill", true)
  private val fileBufferSize = conf.getInt("spark.shuffle.file.buffer.kb", 32) * 1024

  // Size of object batches when reading/writing from serializers.
  //
  // Objects are written in batches, with each batch using its own serialization stream. This
  // cuts down on the size of reference-tracking maps constructed when deserializing a stream.
  //
  // NOTE: Setting this too low can cause excessive copying when serializing, since some serializers
  // grow internal data structures by growing + copying every time the number of objects doubles.
  private val serializerBatchSize = conf.getLong("spark.shuffle.spill.batchSize", 10000)

  private def getPartition(key: K): Int = {
    if (shouldPartition) partitioner.get.getPartition(key) else 0
  }

  // Data structures to store in-memory objects before we spill. Depending on whether we have an
  // Aggregator set, we either put objects into an AppendOnlyMap where we combine them, or we
  // store them in an array buffer.
  private var map = new SizeTrackingAppendOnlyMap[(Int, K), C]
  private var buffer = new SizeTrackingPairBuffer[(Int, K), C]

  // Number of pairs read from input since last spill; note that we count them even if a value is
  // merged with a previous key in case we're doing something like groupBy where the result grows
  private var elementsRead = 0L

  // What threshold of elementsRead we start estimating map size at.
  private val trackMemoryThreshold = 1000

  // Total spilling statistics
  private var spillCount = 0
  private var _memoryBytesSpilled = 0L
  private var _diskBytesSpilled = 0L

  // Write metrics for current spill
  private var curWriteMetrics: ShuffleWriteMetrics = _

  // How much of the shared memory pool this collection has claimed
  private var myMemoryThreshold = 0L

  // A comparator for keys K that orders them within a partition to allow aggregation or sorting.
  // Can be a partial ordering by hash code if a total ordering is not provided through by the
  // user. (A partial ordering means that equal keys have comparator.compare(k, k) = 0, but some
  // non-equal keys also have this, so we need to do a later pass to find truly equal keys).
  // Note that we ignore this if no aggregator and no ordering are given.
  private val keyComparator: Comparator[K] = ordering.getOrElse(new Comparator[K] {
    override def compare(a: K, b: K): Int = {
      val h1 = if (a == null) 0 else a.hashCode()
      val h2 = if (b == null) 0 else b.hashCode()
      h1 - h2
    }
  })

  // A comparator for (Int, K) elements that orders them by partition and then possibly by key
  private val partitionKeyComparator: Comparator[(Int, K)] = {
    if (ordering.isDefined || aggregator.isDefined) {
      // Sort by partition ID then key comparator
      new Comparator[(Int, K)] {
        override def compare(a: (Int, K), b: (Int, K)): Int = {
          val partitionDiff = a._1 - b._1
          if (partitionDiff != 0) {
            partitionDiff
          } else {
            keyComparator.compare(a._2, b._2)
          }
        }
      }
    } else {
      // Just sort it by partition ID
      new Comparator[(Int, K)] {
        override def compare(a: (Int, K), b: (Int, K)): Int = {
          a._1 - b._1
        }
      }
    }
  }

  // Information about a spilled file. Includes sizes in bytes of "batches" written by the
  // serializer as we periodically reset its stream, as well as number of elements in each
  // partition, used to efficiently keep track of partitions when merging.
  private[this] case class SpilledFile(
    file: File,
    blockId: BlockId,
    serializerBatchSizes: Array[Long],
    elementsPerPartition: Array[Long])
  private val spills = new ArrayBuffer[SpilledFile]

  def insertAll(records: Iterator[_ <: Product2[K, V]]): Unit = {
    // TODO: stop combining if we find that the reduction factor isn't high
    val shouldCombine = aggregator.isDefined

    if (shouldCombine) {
      // Combine values in-memory first using our AppendOnlyMap
      val mergeValue = aggregator.get.mergeValue
      val createCombiner = aggregator.get.createCombiner
      var kv: Product2[K, V] = null
      val update = (hadValue: Boolean, oldValue: C) => {
        if (hadValue) mergeValue(oldValue, kv._2) else createCombiner(kv._2)
      }
      while (records.hasNext) {
        elementsRead += 1
        kv = records.next()
        map.changeValue((getPartition(kv._1), kv._1), update)
        maybeSpill(usingMap = true)
      }
    } else {
      // Stick values into our buffer
      while (records.hasNext) {
        elementsRead += 1
        val kv = records.next()
        buffer.insert((getPartition(kv._1), kv._1), kv._2.asInstanceOf[C])
        maybeSpill(usingMap = false)
      }
    }
  }

  /**
   * Spill the current in-memory collection to disk if needed.
   *
   * @param usingMap whether we're using a map or buffer as our current in-memory collection
   */
  private def maybeSpill(usingMap: Boolean): Unit = {
    if (!spillingEnabled) {
      return
    }

    val collection: SizeTrackingPairCollection[(Int, K), C] = if (usingMap) map else buffer

    // TODO: factor this out of both here and ExternalAppendOnlyMap
    if (elementsRead > trackMemoryThreshold && elementsRead % 32 == 0 &&
        collection.estimateSize() >= myMemoryThreshold)
    {
      // Claim up to double our current memory from the shuffle memory pool
      val currentMemory = collection.estimateSize()
      val amountToRequest = 2 * currentMemory - myMemoryThreshold
      val granted = shuffleMemoryManager.tryToAcquire(amountToRequest)
      myMemoryThreshold += granted
      if (myMemoryThreshold <= currentMemory) {
        // We were granted too little memory to grow further (either tryToAcquire returned 0,
        // or we already had more memory than myMemoryThreshold); spill the current collection
        spill(currentMemory, usingMap)  // Will also release memory back to ShuffleMemoryManager
      }
    }
  }

  /**
   * Spill the current in-memory collection to disk, adding a new file to spills, and clear it.
   *
   * @param usingMap whether we're using a map or buffer as our current in-memory collection
   */
  private def spill(memorySize: Long, usingMap: Boolean): Unit = {
    val collection: SizeTrackingPairCollection[(Int, K), C] = if (usingMap) map else buffer
    val memorySize = collection.estimateSize()

    spillCount += 1
    val threadId = Thread.currentThread().getId
    logInfo("Thread %d spilling in-memory batch of %d MB to disk (%d spill%s so far)"
      .format(threadId, memorySize / (1024 * 1024), spillCount, if (spillCount > 1) "s" else ""))
    val (blockId, file) = diskBlockManager.createTempBlock()
    curWriteMetrics = new ShuffleWriteMetrics()
    var writer = blockManager.getDiskWriter(blockId, file, ser, fileBufferSize, curWriteMetrics)
    var objectsWritten = 0   // Objects written since the last flush

    // List of batch sizes (bytes) in the order they are written to disk
    val batchSizes = new ArrayBuffer[Long]

    // How many elements we have in each partition
    val elementsPerPartition = new Array[Long](numPartitions)

    // Flush the disk writer's contents to disk, and update relevant variables.
    // The writer is closed at the end of this process, and cannot be reused.
    def flush() = {
      val w = writer
      writer = null
      w.commitAndClose()
      _diskBytesSpilled += curWriteMetrics.shuffleBytesWritten
      batchSizes.append(curWriteMetrics.shuffleBytesWritten)
      objectsWritten = 0
    }

    var success = false
    try {
      val it = collection.destructiveSortedIterator(partitionKeyComparator)
      while (it.hasNext) {
        val elem = it.next()
        val partitionId = elem._1._1
        val key = elem._1._2
        val value = elem._2
        writer.write(key)
        writer.write(value)
        elementsPerPartition(partitionId) += 1
        objectsWritten += 1

        if (objectsWritten == serializerBatchSize) {
          flush()
          curWriteMetrics = new ShuffleWriteMetrics()
          writer = blockManager.getDiskWriter(blockId, file, ser, fileBufferSize, curWriteMetrics)
        }
      }
      if (objectsWritten > 0) {
        flush()
      } else if (writer != null) {
        val w = writer
        writer = null
        w.revertPartialWritesAndClose()
      }
      success = true
    } finally {
      if (!success) {
        // This code path only happens if an exception was thrown above before we set success;
        // close our stuff and let the exception be thrown further
        if (writer != null) {
          writer.revertPartialWritesAndClose()
        }
        if (file.exists()) {
          file.delete()
        }
      }
    }

    if (usingMap) {
      map = new SizeTrackingAppendOnlyMap[(Int, K), C]
    } else {
      buffer = new SizeTrackingPairBuffer[(Int, K), C]
    }

    // Release our memory back to the shuffle pool so that other threads can grab it
    shuffleMemoryManager.release(myMemoryThreshold)
    myMemoryThreshold = 0

    spills.append(SpilledFile(file, blockId, batchSizes.toArray, elementsPerPartition))
    _memoryBytesSpilled += memorySize
  }

  /**
   * Merge a sequence of sorted files, giving an iterator over partitions and then over elements
   * inside each partition. This can be used to either write out a new file or return data to
   * the user.
   *
   * Returns an iterator over all the data written to this object, grouped by partition. For each
   * partition we then have an iterator over its contents, and these are expected to be accessed
   * in order (you can't "skip ahead" to one partition without reading the previous one).
   * Guaranteed to return a key-value pair for each partition, in order of partition ID.
   */
  private def merge(spills: Seq[SpilledFile], inMemory: Iterator[((Int, K), C)])
      : Iterator[(Int, Iterator[Product2[K, C]])] = {
    val readers = spills.map(new SpillReader(_))
    val inMemBuffered = inMemory.buffered
    (0 until numPartitions).iterator.map { p =>
      val inMemIterator = new IteratorForPartition(p, inMemBuffered)
      val iterators = readers.map(_.readNextPartition()) ++ Seq(inMemIterator)
      if (aggregator.isDefined) {
        // Perform partial aggregation across partitions
        (p, mergeWithAggregation(
          iterators, aggregator.get.mergeCombiners, keyComparator, ordering.isDefined))
      } else if (ordering.isDefined) {
        // No aggregator given, but we have an ordering (e.g. used by reduce tasks in sortByKey);
        // sort the elements without trying to merge them
        (p, mergeSort(iterators, ordering.get))
      } else {
        (p, iterators.iterator.flatten)
      }
    }
  }

  /**
   * Merge-sort a sequence of (K, C) iterators using a given a comparator for the keys.
   */
  private def mergeSort(iterators: Seq[Iterator[Product2[K, C]]], comparator: Comparator[K])
      : Iterator[Product2[K, C]] =
  {
    val bufferedIters = iterators.filter(_.hasNext).map(_.buffered)
    type Iter = BufferedIterator[Product2[K, C]]
    val heap = new mutable.PriorityQueue[Iter]()(new Ordering[Iter] {
      // Use the reverse of comparator.compare because PriorityQueue dequeues the max
      override def compare(x: Iter, y: Iter): Int = -comparator.compare(x.head._1, y.head._1)
    })
    heap.enqueue(bufferedIters: _*)  // Will contain only the iterators with hasNext = true
    new Iterator[Product2[K, C]] {
      override def hasNext: Boolean = !heap.isEmpty

      override def next(): Product2[K, C] = {
        if (!hasNext) {
          throw new NoSuchElementException
        }
        val firstBuf = heap.dequeue()
        val firstPair = firstBuf.next()
        if (firstBuf.hasNext) {
          heap.enqueue(firstBuf)
        }
        firstPair
      }
    }
  }

  /**
   * Merge a sequence of (K, C) iterators by aggregating values for each key, assuming that each
   * iterator is sorted by key with a given comparator. If the comparator is not a total ordering
   * (e.g. when we sort objects by hash code and different keys may compare as equal although
   * they're not), we still merge them by doing equality tests for all keys that compare as equal.
   */
  private def mergeWithAggregation(
      iterators: Seq[Iterator[Product2[K, C]]],
      mergeCombiners: (C, C) => C,
      comparator: Comparator[K],
      totalOrder: Boolean)
      : Iterator[Product2[K, C]] =
  {
    if (!totalOrder) {
      // We only have a partial ordering, e.g. comparing the keys by hash code, which means that
      // multiple distinct keys might be treated as equal by the ordering. To deal with this, we
      // need to read all keys considered equal by the ordering at once and compare them.
      new Iterator[Iterator[Product2[K, C]]] {
        val sorted = mergeSort(iterators, comparator).buffered

        // Buffers reused across elements to decrease memory allocation
        val keys = new ArrayBuffer[K]
        val combiners = new ArrayBuffer[C]

        override def hasNext: Boolean = sorted.hasNext

        override def next(): Iterator[Product2[K, C]] = {
          if (!hasNext) {
            throw new NoSuchElementException
          }
          keys.clear()
          combiners.clear()
          val firstPair = sorted.next()
          keys += firstPair._1
          combiners += firstPair._2
          val key = firstPair._1
          while (sorted.hasNext && comparator.compare(sorted.head._1, key) == 0) {
            val pair = sorted.next()
            var i = 0
            var foundKey = false
            while (i < keys.size && !foundKey) {
              if (keys(i) == pair._1) {
                combiners(i) = mergeCombiners(combiners(i), pair._2)
                foundKey = true
              }
              i += 1
            }
            if (!foundKey) {
              keys += pair._1
              combiners += pair._2
            }
          }

          // Note that we return an iterator of elements since we could've had many keys marked
          // equal by the partial order; we flatten this below to get a flat iterator of (K, C).
          keys.iterator.zip(combiners.iterator)
        }
      }.flatMap(i => i)
    } else {
      // We have a total ordering, so the objects with the same key are sequential.
      new Iterator[Product2[K, C]] {
        val sorted = mergeSort(iterators, comparator).buffered

        override def hasNext: Boolean = sorted.hasNext

        override def next(): Product2[K, C] = {
          if (!hasNext) {
            throw new NoSuchElementException
          }
          val elem = sorted.next()
          val k = elem._1
          var c = elem._2
          while (sorted.hasNext && sorted.head._1 == k) {
            c = mergeCombiners(c, sorted.head._2)
          }
          (k, c)
        }
      }
    }
  }

  /**
   * An internal class for reading a spilled file partition by partition. Expects all the
   * partitions to be requested in order.
   */
  private[this] class SpillReader(spill: SpilledFile) {
    // Serializer batch offsets; size will be batchSize.length + 1
    val batchOffsets = spill.serializerBatchSizes.scanLeft(0L)(_ + _)

    // Track which partition and which batch stream we're in. These will be the indices of
    // the next element we will read. We'll also store the last partition read so that
    // readNextPartition() can figure out what partition that was from.
    var partitionId = 0
    var indexInPartition = 0L
    var batchId = 0
    var indexInBatch = 0
    var lastPartitionId = 0

    skipToNextPartition()


    // Intermediate file and deserializer streams that read from exactly one batch
    // This guards against pre-fetching and other arbitrary behavior of higher level streams
    var fileStream: FileInputStream = null
    var deserializeStream = nextBatchStream()  // Also sets fileStream

    var nextItem: (K, C) = null
    var finished = false

    /** Construct a stream that only reads from the next batch */
    def nextBatchStream(): DeserializationStream = {
      // Note that batchOffsets.length = numBatches + 1 since we did a scan above; check whether
      // we're still in a valid batch.
      if (batchId < batchOffsets.length - 1) {
        if (deserializeStream != null) {
          deserializeStream.close()
          fileStream.close()
          deserializeStream = null
          fileStream = null
        }

        val start = batchOffsets(batchId)
        fileStream = new FileInputStream(spill.file)
        fileStream.getChannel.position(start)
        batchId += 1

        val end = batchOffsets(batchId)

        assert(end >= start, "start = " + start + ", end = " + end +
          ", batchOffsets = " + batchOffsets.mkString("[", ", ", "]"))

        val bufferedStream = new BufferedInputStream(ByteStreams.limit(fileStream, end - start))
        val compressedStream = blockManager.wrapForCompression(spill.blockId, bufferedStream)
        serInstance.deserializeStream(compressedStream)
      } else {
        // No more batches left
        cleanup()
        null
      }
    }

    /**
     * Update partitionId if we have reached the end of our current partition, possibly skipping
     * empty partitions on the way.
     */
    private def skipToNextPartition() {
      while (partitionId < numPartitions &&
          indexInPartition == spill.elementsPerPartition(partitionId)) {
        partitionId += 1
        indexInPartition = 0L
      }
    }

    /**
     * Return the next (K, C) pair from the deserialization stream and update partitionId,
     * indexInPartition, indexInBatch and such to match its location.
     *
     * If the current batch is drained, construct a stream for the next batch and read from it.
     * If no more pairs are left, return null.
     */
    private def readNextItem(): (K, C) = {
      if (finished || deserializeStream == null) {
        return null
      }
      val k = deserializeStream.readObject().asInstanceOf[K]
      val c = deserializeStream.readObject().asInstanceOf[C]
      lastPartitionId = partitionId
      // Start reading the next batch if we're done with this one
      indexInBatch += 1
      if (indexInBatch == serializerBatchSize) {
        indexInBatch = 0
        deserializeStream = nextBatchStream()
      }
      // Update the partition location of the element we're reading
      indexInPartition += 1
      skipToNextPartition()
      // If we've finished reading the last partition, remember that we're done
      if (partitionId == numPartitions) {
        finished = true
        if (deserializeStream != null) {
          deserializeStream.close()
        }
      }
      (k, c)
    }

    var nextPartitionToRead = 0

    def readNextPartition(): Iterator[Product2[K, C]] = new Iterator[Product2[K, C]] {
      val myPartition = nextPartitionToRead
      nextPartitionToRead += 1

      override def hasNext: Boolean = {
        if (nextItem == null) {
          nextItem = readNextItem()
          if (nextItem == null) {
            return false
          }
        }
        assert(lastPartitionId >= myPartition)
        // Check that we're still in the right partition; note that readNextItem will have returned
        // null at EOF above so we would've returned false there
        lastPartitionId == myPartition
      }

      override def next(): Product2[K, C] = {
        if (!hasNext) {
          throw new NoSuchElementException
        }
        val item = nextItem
        nextItem = null
        item
      }
    }

    // Clean up our open streams and put us in a state where we can't read any more data
    def cleanup() {
      batchId = batchOffsets.length  // Prevent reading any other batch
      val ds = deserializeStream
      deserializeStream = null
      fileStream = null
      ds.close()
      // NOTE: We don't do file.delete() here because that is done in ExternalSorter.stop().
      // This should also be fixed in ExternalAppendOnlyMap.
    }
  }

  /**
   * Return an iterator over all the data written to this object, grouped by partition and
   * aggregated by the requested aggregator. For each partition we then have an iterator over its
   * contents, and these are expected to be accessed in order (you can't "skip ahead" to one
   * partition without reading the previous one). Guaranteed to return a key-value pair for each
   * partition, in order of partition ID.
   *
   * For now, we just merge all the spilled files in once pass, but this can be modified to
   * support hierarchical merging.
   */
  def partitionedIterator: Iterator[(Int, Iterator[Product2[K, C]])] = {
    val usingMap = aggregator.isDefined
    val collection: SizeTrackingPairCollection[(Int, K), C] = if (usingMap) map else buffer
    if (spills.isEmpty) {
      // Special case: if we have only in-memory data, we don't need to merge streams, and perhaps
      // we don't even need to sort by anything other than partition ID
      if (!ordering.isDefined) {
        // The user isn't requested sorted keys, so only sort by partition ID, not key
        val partitionComparator = new Comparator[(Int, K)] {
          override def compare(a: (Int, K), b: (Int, K)): Int = {
            a._1 - b._1
          }
        }
        groupByPartition(collection.destructiveSortedIterator(partitionComparator))
      } else {
        // We do need to sort by both partition ID and key
        groupByPartition(collection.destructiveSortedIterator(partitionKeyComparator))
      }
    } else {
      // General case: merge spilled and in-memory data
      merge(spills, collection.destructiveSortedIterator(partitionKeyComparator))
    }
  }

  /**
   * Return an iterator over all the data written to this object, aggregated by our aggregator.
   */
  def iterator: Iterator[Product2[K, C]] = partitionedIterator.flatMap(pair => pair._2)

  /**
   * Write all the data added into this ExternalSorter into a file in the disk store, creating
   * an .index file for it as well with the offsets of each partition. This is called by the
   * SortShuffleWriter and can go through an efficient path of just concatenating binary files
   * if we decided to avoid merge-sorting.
   *
   * @param blockId block ID to write to. The index file will be blockId.name + ".index".
   * @param context a TaskContext for a running Spark task, for us to update shuffle metrics.
   * @return array of lengths, in bytes, for each partition of the file (for map output tracker)
   */
  def writePartitionedFile(blockId: BlockId, context: TaskContext): Array[Long] = {
    val outputFile = blockManager.diskBlockManager.getFile(blockId)

    // Track location of each range in the output file
    val offsets = new Array[Long](numPartitions + 1)
    val lengths = new Array[Long](numPartitions)

    // Statistics
    var totalBytes = 0L
    var totalTime = 0L

    for ((id, elements) <- this.partitionedIterator) {
      if (elements.hasNext) {
        val writer = blockManager.getDiskWriter(blockId, outputFile, ser, fileBufferSize)
        for (elem <- elements) {
          writer.write(elem)
        }
        writer.commitAndClose()
        val segment = writer.fileSegment()
        offsets(id + 1) = segment.offset + segment.length
        lengths(id) = segment.length
        totalTime += writer.timeWriting()
        totalBytes += segment.length
      } else {
        // The partition is empty; don't create a new writer to avoid writing headers, etc
        offsets(id + 1) = offsets(id)
      }
    }

    val shuffleMetrics = new ShuffleWriteMetrics
    shuffleMetrics.shuffleBytesWritten = totalBytes
    shuffleMetrics.shuffleWriteTime = totalTime
    context.taskMetrics.shuffleWriteMetrics = Some(shuffleMetrics)
    context.taskMetrics.memoryBytesSpilled += memoryBytesSpilled
    context.taskMetrics.diskBytesSpilled += diskBytesSpilled

    // Write an index file with the offsets of each block, plus a final offset at the end for the
    // end of the output file. This will be used by SortShuffleManager.getBlockLocation to figure
    // out where each block begins and ends.

    val diskBlockManager = blockManager.diskBlockManager
    val indexFile = diskBlockManager.getFile(blockId.name + ".index")
    val out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(indexFile)))
    try {
      var i = 0
      while (i < numPartitions + 1) {
        out.writeLong(offsets(i))
        i += 1
      }
    } finally {
      out.close()
    }

    lengths
  }

  def stop(): Unit = {
    spills.foreach(s => s.file.delete())
    spills.clear()
  }

  def memoryBytesSpilled: Long = _memoryBytesSpilled

  def diskBytesSpilled: Long = _diskBytesSpilled

  /**
   * Given a stream of ((partition, key), combiner) pairs *assumed to be sorted by partition ID*,
   * group together the pairs for each partition into a sub-iterator.
   *
   * @param data an iterator of elements, assumed to already be sorted by partition ID
   */
  private def groupByPartition(data: Iterator[((Int, K), C)])
      : Iterator[(Int, Iterator[Product2[K, C]])] =
  {
    val buffered = data.buffered
    (0 until numPartitions).iterator.map(p => (p, new IteratorForPartition(p, buffered)))
  }

  /**
   * An iterator that reads only the elements for a given partition ID from an underlying buffered
   * stream, assuming this partition is the next one to be read. Used to make it easier to return
   * partitioned iterators from our in-memory collection.
   */
  private[this] class IteratorForPartition(partitionId: Int, data: BufferedIterator[((Int, K), C)])
    extends Iterator[Product2[K, C]]
  {
    override def hasNext: Boolean = data.hasNext && data.head._1._1 == partitionId

    override def next(): Product2[K, C] = {
      if (!hasNext) {
        throw new NoSuchElementException
      }
      val elem = data.next()
      (elem._1._2, elem._2)
    }
  }
}
