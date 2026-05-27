package uk.gov.nationalarchives

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers._

class FileContentValidatorSpec extends AnyFlatSpec {

  "isValidUtf8" should "return true for valid ASCII text" in {
    val bytes = "Hello, world!".getBytes("UTF-8")
    FileContentValidator.isValidUtf8(bytes) should be(true)
  }

  it should "return true for valid multi-byte UTF-8" in {
    val bytes = "Héllo wörld café".getBytes("UTF-8")
    FileContentValidator.isValidUtf8(bytes) should be(true)
  }

  it should "return false for invalid UTF-8 bytes" in {
    val bytes = Array[Byte](0xC0.toByte, 0x01.toByte)
    FileContentValidator.isValidUtf8(bytes) should be(false)
  }

  it should "return true for empty input" in {
    FileContentValidator.isValidUtf8(Array.emptyByteArray) should be(true)
  }

  "isValidWindows1252Range" should "return true for standard printable ASCII" in {
    val bytes = "Hello world".getBytes("US-ASCII")
    FileContentValidator.isValidWindows1252Range(bytes) should be(true)
  }

  it should "return true for tab, newline, carriage return" in {
    val bytes = Array[Byte](0x09, 0x0A, 0x0D)
    FileContentValidator.isValidWindows1252Range(bytes) should be(true)
  }

  it should "return true for Windows-1252 high bytes (0x80, 0x82-0x8C, 0x8E, 0x91-0x9C, 0x9E-0xFF)" in {
    val bytes = Array[Byte](0x80.toByte, 0x82.toByte, 0x8C.toByte, 0x8E.toByte, 0x91.toByte, 0x9C.toByte, 0x9E.toByte, 0xFF.toByte)
    FileContentValidator.isValidWindows1252Range(bytes) should be(true)
  }

  it should "return false for disallowed byte 0x81" in {
    val bytes = Array[Byte](0x81.toByte)
    FileContentValidator.isValidWindows1252Range(bytes) should be(false)
  }

  it should "return false for disallowed byte 0x8D" in {
    val bytes = Array[Byte](0x8D.toByte)
    FileContentValidator.isValidWindows1252Range(bytes) should be(false)
  }

  it should "return false for disallowed byte 0x8F" in {
    val bytes = Array[Byte](0x8F.toByte)
    FileContentValidator.isValidWindows1252Range(bytes) should be(false)
  }

  it should "return false for disallowed byte 0x90" in {
    val bytes = Array[Byte](0x90.toByte)
    FileContentValidator.isValidWindows1252Range(bytes) should be(false)
  }

  it should "return false for disallowed byte 0x9D" in {
    val bytes = Array[Byte](0x9D.toByte)
    FileContentValidator.isValidWindows1252Range(bytes) should be(false)
  }

  it should "return false for null byte 0x00" in {
    val bytes = Array[Byte](0x00)
    FileContentValidator.isValidWindows1252Range(bytes) should be(false)
  }

  it should "return true for empty input" in {
    FileContentValidator.isValidWindows1252Range(Array.emptyByteArray) should be(true)
  }

  "isAllowedContent" should "return true for valid UTF-8 content" in {
    val bytes = "Hello café".getBytes("UTF-8")
    FileContentValidator.isAllowedContent(bytes) should be(true)
  }

  it should "return true for content that fails UTF-8 but passes Windows-1252 range" in {
    // 0x80 is not valid as a standalone UTF-8 byte but is allowed in Windows-1252
    val bytes = Array[Byte](0x48, 0x65, 0x6C, 0x6C, 0x6F, 0x80.toByte)
    FileContentValidator.isAllowedContent(bytes) should be(true)
  }

  it should "return false for content that fails both checks" in {
    // 0x81 is neither valid UTF-8 nor in the allowed Windows-1252 range
    val bytes = Array[Byte](0x48, 0x65, 0x6C, 0x6C, 0x6F, 0x81.toByte)
    FileContentValidator.isAllowedContent(bytes) should be(false)
  }

  it should "return false for empty content" in {
    FileContentValidator.isAllowedContent(Array.emptyByteArray) should be(false)
  }

  // --- Streaming variant tests ---

  private def stream(bytes: Array[Byte]): java.io.ByteArrayInputStream =
    new java.io.ByteArrayInputStream(bytes)

  "isAllowedContent(InputStream)" should "return true for valid UTF-8 content" in {
    FileContentValidator.isAllowedContent(stream("Hello café".getBytes("UTF-8"))) should be(true)
  }

  it should "return true for content that fails UTF-8 but passes Windows-1252 range" in {
    val bytes = Array[Byte](0x48, 0x65, 0x6C, 0x6C, 0x6F, 0x80.toByte)
    FileContentValidator.isAllowedContent(stream(bytes)) should be(true)
  }

  it should "return false for content that fails both checks" in {
    val bytes = Array[Byte](0x48, 0x65, 0x6C, 0x6C, 0x6F, 0x81.toByte)
    FileContentValidator.isAllowedContent(stream(bytes)) should be(false)
  }

  it should "return false for empty content" in {
    FileContentValidator.isAllowedContent(stream(Array.emptyByteArray)) should be(false)
  }

  it should "return false for a truncated multi-byte UTF-8 sequence with no Windows-1252 fallback" in {
    // 0x81 fails Windows-1252, so any sequence containing it cannot fall back.
    // A leading UTF-8 byte 0xC2 followed by 0x81 is also invalid as UTF-8 because
    // 0x81 here is a valid continuation, but we then append another 0x81 which is
    // neither a valid leading byte nor in the allowed Windows-1252 range.
    val bytes = Array[Byte](0xC2.toByte, 0x81.toByte, 0x81.toByte)
    FileContentValidator.isAllowedContent(stream(bytes)) should be(false)
  }

  it should "return true across chunk boundaries for valid multi-byte UTF-8" in {
    val bytes = "Héllo wörld café".getBytes("UTF-8")
    FileContentValidator.isAllowedContent(stream(bytes)) should be(true)
  }

  it should "terminate early when both checks fail without consuming the rest of the stream" in {
    // 0x81 fails Windows-1252. The Utf8Validator will also fail early once it
    // encounters it. The remainder is deliberately large to verify we don't read it.
    val invalidPrefix = Array[Byte](0x48, 0x81.toByte)
    val trailingSize = 10 * 1024 * 1024
    var bytesRead = 0
    val instrumented = new java.io.InputStream {
      private val src = new java.io.ByteArrayInputStream(invalidPrefix ++ new Array[Byte](trailingSize))
      override def read(): Int = {
        val b = src.read()
        if (b != -1) bytesRead += 1
        b
      }
      override def read(buf: Array[Byte], off: Int, len: Int): Int = {
        val n = src.read(buf, off, len)
        if (n > 0) bytesRead += n
        n
      }
    }
    FileContentValidator.isAllowedContent(instrumented) should be(false)
    // Only the prefix (plus a small read buffer) should have been consumed,
    // not the multi-megabyte trailing payload.
    bytesRead should be < trailingSize
  }
}

