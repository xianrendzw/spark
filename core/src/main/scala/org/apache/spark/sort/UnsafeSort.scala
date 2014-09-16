package org.apache.spark.sort

import java.io.{FileInputStream, BufferedInputStream, File}

import org.apache.spark.{TaskContext, Partition, SparkContext}
import org.apache.spark.rdd.RDD


class MutableLong(var value: Long)


/**
 * A version of the sort code that uses Unsafe to allocate off-heap blocks.
 */
object UnsafeSort {

  final val UNSAFE: sun.misc.Unsafe = {
    val unsafeField = classOf[sun.misc.Unsafe].getDeclaredField("theUnsafe")
    unsafeField.setAccessible(true)
    unsafeField.get().asInstanceOf[sun.misc.Unsafe]
  }

  final val BYTE_ARRAY_BASE_OFFSET: Long = UNSAFE.arrayBaseOffset(classOf[Array[Byte]])

  /** A thread local variable storing a pointer to the buffer allocated off-heap. */
  val blocks = new ThreadLocal[java.lang.Long]

  /** A offset, used by the deserializer to store data ... */
  val blockOffset = new ThreadLocal[MutableLong] {
    override def initialValue = new MutableLong(0L)
  }

  def createInputRDDUnsafe(
      sc: SparkContext, sizeInGB: Int, numParts: Int, bufSize: Int, numEbsVols: Int)
  : RDD[(Long, Long)] = {

    val sizeInBytes = sizeInGB.toLong * 1000 * 1000 * 1000
    val numRecords = sizeInBytes / 100
    val recordsPerPartition = math.ceil(numRecords.toDouble / numParts).toLong

    val hosts = Sort.readSlaves()
    new NodeLocalRDD[(Long, Long)](sc, numParts, hosts) {
      override def compute(split: Partition, context: TaskContext) = {
        val part = split.index
        val host = split.asInstanceOf[NodeLocalRDDPartition].node

        val start = recordsPerPartition * part
        val volIndex = part % numEbsVols

        val baseFolder = s"/vol$volIndex/sort-${sizeInGB}g-$numParts"
        val outputFile = s"$baseFolder/part$part.dat"

        val fileSize = new File(outputFile).length
        assert(fileSize % 100 == 0)

        if (blocks.get == null) {
          val blockSize = recordsPerPartition * 100
          logInfo(s"Allocating $blockSize bytes")
          val blockAddress = UNSAFE.allocateMemory(blockSize)
          logInfo(s"Allocating $blockSize bytes ... allocated at $blockAddress")
          blocks.set(blockAddress)
        }

        val blockAddress: Long = blocks.get.longValue()
        val numRecords = fileSize / 100

        val arrOffset = BYTE_ARRAY_BASE_OFFSET
        val buf = new Array[Byte](100)
        val is = new BufferedInputStream(new FileInputStream(outputFile), bufSize)

        new Iterator[(Long, Long)] {
          private[this] var pos: Long = 0
          override def hasNext: Boolean = {
            val end = pos < numRecords
            end
          }
          override def next(): (Long, Long) = {
            pos += 1
            is.read(buf)
            val startAddr = blockAddress + pos * 100
            UNSAFE.copyMemory(buf, arrOffset, null, startAddr, 100)
            (startAddr, 0)
          }
        }
      }
    }
  }
}