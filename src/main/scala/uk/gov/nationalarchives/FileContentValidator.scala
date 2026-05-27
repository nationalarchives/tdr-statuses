package uk.gov.nationalarchives

import uk.gov.nationalarchives.utf8.validator.{Utf8Validator, ValidationException, ValidationHandler}

import java.io.{ByteArrayInputStream, InputStream}

object FileContentValidator {

  def isValidUtf8(bytes: Array[Byte]): Boolean = {
    val handler: ValidationHandler = (message: String, byteOffset: Long) =>
      throw new ValidationException(message, byteOffset)
    val validator = new Utf8Validator(handler)
    scala.util.Try(validator.validate(new ByteArrayInputStream(bytes))).isSuccess
  }

  def isValidWindows1252Range(bytes: Array[Byte]): Boolean =
    bytes.forall(isValidWindows1252Byte)

  private def isValidWindows1252Byte(b: Byte): Boolean = {
    val value = b & 0xFF
    value == 0x09 || value == 0x0A || value == 0x0D ||
      (value >= 0x20 && value < 0x7F) ||
      value == 0x80 ||
      (value >= 0x82 && value <= 0x8C) ||
      value == 0x8E ||
      (value >= 0x91 && value <= 0x9C) ||
      (value >= 0x9E && value <= 0xFF)
  }

  def isAllowedContent(bytes: Array[Byte]): Boolean = bytes match {
    case content if content.isEmpty => false
    case content => isValidUtf8(content) || isValidWindows1252Range(content)
  }

  /**
   * Streaming variant of [[isAllowedContent]] with early termination.
   *
   * Wraps the stream in a [[Windows1252TrackingInputStream]] that checks each
   * byte for Windows-1252 validity inline, then passes it to the library's
   * [[Utf8Validator]], which validates UTF-8 and throws [[ValidationException]]
   * as soon as it encounters invalid bytes — providing early termination without
   * loading the full object into memory.
   *
   * If UTF-8 validation succeeds the file is allowed. If it fails, the
   * Windows-1252 result accumulated up to that point is used as the fallback.
   */
  def isAllowedContent(in: InputStream): Boolean = {
    val tracker = new Windows1252TrackingInputStream(in)
    val handler: ValidationHandler = (message: String, byteOffset: Long) =>
      throw new ValidationException(message, byteOffset)
    val utf8Valid = scala.util.Try(new Utf8Validator(handler).validate(tracker)).isSuccess
    if (tracker.isEmpty) false
    else utf8Valid || tracker.win1252Valid
  }

  /**
   * Wraps an [[InputStream]], tracking Windows-1252 validity for every byte
   * read without buffering. Delegates all reading to the underlying stream.
   */
  private final class Windows1252TrackingInputStream(delegate: InputStream) extends InputStream {
    var win1252Valid: Boolean = true
    var isEmpty: Boolean = true

    private def trackByte(b: Int): Unit =
      if (b != -1) {
        isEmpty = false
        if (!isValidWindows1252Byte(b.toByte)) win1252Valid = false
      }

    override def read(): Int = {
      val b = delegate.read()
      trackByte(b)
      b
    }

    override def read(buf: Array[Byte], off: Int, len: Int): Int = {
      val n = delegate.read(buf, off, len)
      if (n > 0) {
        var i = off
        while (i < off + n) {
          if (!isValidWindows1252Byte(buf(i))) win1252Valid = false
          i += 1
        }
        isEmpty = false
      }
      n
    }
  }
}
