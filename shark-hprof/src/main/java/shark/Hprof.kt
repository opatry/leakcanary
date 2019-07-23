package shark

import okio.BufferedSource
import okio.buffer
import okio.source
import java.io.Closeable
import java.io.File
import java.nio.channels.FileChannel

/**
 * An opened Hprof file which can be read via [reader]. Open a new hprof with [open], and don't
 * forget to call [close] once done.
 */
class Hprof private constructor(
  private val channel: FileChannel,
  private val source: BufferedSource,
  val reader: HprofReader,
  /** Unix timestamp at which the heap was dumped. */
  val heapDumpTimestamp: Long,
  /** Version of the opened hprof, which is tied to the runtime where the heap was dumped. */
  val hprofVersion: HprofVersion
) : Closeable {

  private var lastReaderByteReadCount = reader.byteReadCount
  private var lastKnownPosition = reader.byteReadCount

  override fun close() {
    source.close()
  }

  /**
   * Moves [reader] to a new position in the hprof file. This is transparent to the reader, and
   * will not reset [HprofReader.byteReadCount].
   */
  fun moveReaderTo(newPosition: Long) {
    val currentPosition = lastKnownPosition + (reader.byteReadCount - lastReaderByteReadCount)

    if (currentPosition == newPosition) {
      return
    }
    source.buffer.clear()
    channel.position(newPosition)
    lastReaderByteReadCount = reader.byteReadCount
    lastKnownPosition = newPosition
  }

  /**
   * Supported hprof versions
   */
  enum class HprofVersion(val versionString: String) {
    JDK1_2_BETA3("JAVA PROFILE 1.0"),
    JDK1_2_BETA4("JAVA PROFILE 1.0.1"),
    JDK_6("JAVA PROFILE 1.0.2"),
    ANDROID("JAVA PROFILE 1.0.3")
  }

  companion object {
    private val supportedVersions = HprofVersion.values()
        .map { it.versionString to it }.toMap()

    /**
     * Reads the headers of the provided [hprofFile] and returns an opened [Hprof]. Don't forget
     * to call [close] once done.
     */
    fun open(hprofFile: File): Hprof {
      if (hprofFile.length() == 0L) {
        throw IllegalArgumentException("Hprof file is 0 byte length")
      }
      val inputStream = hprofFile.inputStream()
      val channel = inputStream.channel
      val source = inputStream.source()
          .buffer()

      val endOfVersionString = source.indexOf(0)
      val versionName = source.readUtf8(endOfVersionString)

      val hprofVersion = supportedVersions[versionName]

      require(hprofVersion != null) {
        "Unsupported Hprof version [$versionName] not in supported list ${supportedVersions.keys}"
      }
      // Skip the 0 at the end of the version string.
      source.skip(1)
      val identifierByteSize = source.readInt()

      // heap dump timestamp
      val heapDumpTimestamp = source.readLong()

      val byteReadCount = endOfVersionString + 1 + 4 + 8

      val reader = HprofReader(source, identifierByteSize, byteReadCount)

      return Hprof(
          channel, source, reader, heapDumpTimestamp, hprofVersion
      )
    }
  }

}