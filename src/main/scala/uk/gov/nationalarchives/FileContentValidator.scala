package uk.gov.nationalarchives

import uk.gov.nationalarchives.utf8.validator.{Utf8Validator, ValidationException, ValidationHandler}

import java.io.{BufferedInputStream, InputStream}
import scala.util.Try

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
   * byte for Windows-1252 validity inline, then passes it to [[Utf8Validator]].
   * If UTF-8 validation succeeds, the file is allowed. If it fails, the tracked
   * Windows-1252 result is used as fallback.
   *
   * Returns false for an empty stream.
   */
  def isAllowedContent(inputStream: InputStream): Boolean = {
    val trackingStream = new Windows1252TrackingInputStream(inputStream)
    val isUtf8Valid = validateUtf8(trackingStream)

    if (!isUtf8Valid && trackingStream.isWindows1252Valid) {
      drainForWindows1252Validation(trackingStream)
    }

    !trackingStream.isStreamEmpty && (isUtf8Valid || trackingStream.isWindows1252Valid)
  }

  private def validateUtf8(stream: InputStream): Boolean = {
    val validationHandler: ValidationHandler = (message: String, byteOffset: Long) =>
      throw new ValidationException(message, byteOffset)
    Try(new Utf8Validator(validationHandler).validate(stream)).isSuccess
  }

  private def drainForWindows1252Validation(stream: Windows1252TrackingInputStream): Unit = {
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
