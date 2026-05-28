package uk.gov.nationalarchives

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers._

class FileContentValidatorSpec extends AnyFlatSpec {

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

