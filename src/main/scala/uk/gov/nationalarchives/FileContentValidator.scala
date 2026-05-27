package uk.gov.nationalarchives

import uk.gov.nationalarchives.utf8.validator.{Utf8Validator, ValidationException, ValidationHandler}

import java.io.ByteArrayInputStream

object FileContentValidator {

  def isValidUtf8(bytes: Array[Byte]): Boolean = {
    val handler: ValidationHandler = (message: String, byteOffset: Long) =>
      throw new ValidationException(message, byteOffset)
    val validator = new Utf8Validator(handler)
    scala.util.Try(validator.validate(new ByteArrayInputStream(bytes))).isSuccess
  }

  def isValidWindows1252Range(bytes: Array[Byte]): Boolean = {
    bytes.forall { b =>
      val value = b & 0xFF
      value == 0x09 || value == 0x0A || value == 0x0D ||
        (value >= 0x20 && value < 0x7F) ||
        value == 0x80 ||
        (value >= 0x82 && value <= 0x8C) ||
        value == 0x8E ||
        (value >= 0x91 && value <= 0x9C) ||
        (value >= 0x9E && value <= 0xFF)
    }
  }

  def isAllowedContent(bytes: Array[Byte]): Boolean = bytes match {
    case content if content.isEmpty => false
    case content => isValidUtf8(content) || isValidWindows1252Range(content)
  }
}
