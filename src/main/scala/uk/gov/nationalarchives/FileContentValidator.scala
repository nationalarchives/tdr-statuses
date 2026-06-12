package uk.gov.nationalarchives

import java.io.{BufferedInputStream, InputStream}
import java.nio.{ByteBuffer, CharBuffer}
import java.nio.charset.{CodingErrorAction, StandardCharsets}

object FileContentValidator {

  private val StreamBufferSize = 8 * 1024 * 1024
  private val DrainBufferSize = 8192

  private def isValidWindows1252Byte(inputByte: Byte): Boolean = {
    val value = inputByte & 0xFF
    value == 0x09 || value == 0x0A || value == 0x0D ||
      (value >= 0x20 && value < 0x7F) ||
      value == 0x80 ||
      (value >= 0x82 && value <= 0x8C) ||
      value == 0x8E ||
      (value >= 0x91 && value <= 0x9C) ||
      (value >= 0x9E && value <= 0xFF)
  }

  /**
   * Validates file content from a stream with early termination.
   *
   * Wraps the stream in a [[Windows1252TrackingInputStream]] that checks each
   * byte for Windows-1252 validity inline, then validates UTF-8 using a
   * bulk-read CharsetDecoder. If UTF-8 validation succeeds, the file is allowed.
   * If it fails, the tracked Windows-1252 result is used as fallback.
   *
   * Returns false for an empty stream.
   */
  def isAllowedContent(inputStream: InputStream): Boolean = {
    val trackingStream = new Windows1252TrackingInputStream(inputStream)
    val isUtf8Valid = validateUtf8(trackingStream)
    if (!isUtf8Valid) {
      drainStream(trackingStream)
    }

    !trackingStream.isStreamEmpty && (isUtf8Valid || trackingStream.isWindows1252Valid)
  }

  private val Utf8ReadBufferSize = 8192

  private def validateUtf8(stream: InputStream): Boolean = {
    val decoder = StandardCharsets.UTF_8.newDecoder()
      .onMalformedInput(CodingErrorAction.REPORT)
      .onUnmappableCharacter(CodingErrorAction.REPORT)
    val buffer = new Array[Byte](Utf8ReadBufferSize)
    val charBuf = CharBuffer.allocate(Utf8ReadBufferSize)
    // Tracks how many leftover bytes from an incomplete multi-byte sequence
    // remain at the start of the buffer from the previous read.
    var leftover = 0
    var bytesRead = stream.read(buffer, 0, buffer.length)
    while (bytesRead != -1) {
      val total = leftover + bytesRead
      val bb = ByteBuffer.wrap(buffer, 0, total)
      val result = decoder.decode(bb, charBuf, false)
      if (result.isError) return false
      charBuf.clear()
      // Carry over any unconsumed bytes (incomplete multi-byte sequence at end)
      leftover = bb.remaining()
      if (leftover > 0) {
        System.arraycopy(buffer, bb.position(), buffer, 0, leftover)
      }
      bytesRead = stream.read(buffer, leftover, buffer.length - leftover)
    }
    // Signal end-of-input — any remaining incomplete sequence is an error
    val bb = ByteBuffer.wrap(buffer, 0, leftover)
    val endResult = decoder.decode(bb, charBuf, true)
    if (endResult.isError) return false
    val flushResult = decoder.flush(charBuf)
    !flushResult.isError
  }

  private def drainStream(stream: Windows1252TrackingInputStream): Unit = {
    val buffer = new Array[Byte](DrainBufferSize)
    while (stream.isWindows1252Valid && stream.read(buffer) != -1) {}
  }

  /**
   * Wraps an [[InputStream]], tracking Windows-1252 validity for every byte
   * read without buffering. Delegates all reading to the underlying stream.
   */
  private final class Windows1252TrackingInputStream(delegate: InputStream) extends InputStream {
    var isWindows1252Valid: Boolean = true
    var isStreamEmpty: Boolean = true
    private val buffered = new BufferedInputStream(delegate, StreamBufferSize)

    private def trackSingleByte(nextByte: Int): Unit =
      if (nextByte != -1) {
        isStreamEmpty = false
        if (!isValidWindows1252Byte(nextByte.toByte)) isWindows1252Valid = false
      }

    private def trackBufferBytes(buffer: Array[Byte], offset: Int, bytesRead: Int): Unit = {
      val end = offset + bytesRead
      var index = offset
      while (index < end) {
        if (!isValidWindows1252Byte(buffer(index))) isWindows1252Valid = false
        index += 1
      }
      isStreamEmpty = false
    }

    override def available(): Int = buffered.available()

    override def read(): Int = {
      val nextByte = buffered.read()
      trackSingleByte(nextByte)
      nextByte
    }

    override def read(buffer: Array[Byte], offset: Int, length: Int): Int = {
      val bytesRead = buffered.read(buffer, offset, length)
      if (bytesRead > 0) {
        trackBufferBytes(buffer, offset, bytesRead)
      }
      bytesRead
    }
  }
}
