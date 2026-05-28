package uk.gov.nationalarchives

import uk.gov.nationalarchives.utf8.validator.{Utf8Validator, ValidationException, ValidationHandler}

import java.io.InputStream

object FileContentValidator {

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
    val validationHandler: ValidationHandler = (message: String, byteOffset: Long) =>
      throw new ValidationException(message, byteOffset)
    val isUtf8Valid = scala.util.Try(new Utf8Validator(validationHandler).validate(trackingStream)).isSuccess
    !trackingStream.isStreamEmpty && (isUtf8Valid || trackingStream.isWindows1252Valid)
  }

  /**
   * Wraps an [[InputStream]], tracking Windows-1252 validity for every byte
   * read without buffering. Delegates all reading to the underlying stream.
   */
  private final class Windows1252TrackingInputStream(delegate: InputStream) extends InputStream {
    var isWindows1252Valid: Boolean = true
    var isStreamEmpty: Boolean = true

    private def trackByte(nextByte: Int): Unit =
      if (nextByte != -1) {
        isStreamEmpty = false
        if (!isValidWindows1252Byte(nextByte.toByte)) isWindows1252Valid = false
      }

    override def read(): Int = {
      val nextByte = delegate.read()
      trackByte(nextByte)
      nextByte
    }

    override def read(buffer: Array[Byte], offset: Int, length: Int): Int = {
      val bytesRead = delegate.read(buffer, offset, length)
      if (bytesRead > 0) {
        var index = offset
        while (index < offset + bytesRead) {
          if (!isValidWindows1252Byte(buffer(index))) isWindows1252Valid = false
          index += 1
        }
        isStreamEmpty = false
      }
      bytesRead
    }
  }
}
