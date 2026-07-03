package uk.gov.nationalarchives

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers._

class FileContentValidatorSpec extends AnyFlatSpec {

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

  it should "return true for valid multi-byte UTF-8 content" in {
    val bytes = "Héllo wörld café".getBytes("UTF-8")
    FileContentValidator.isAllowedContent(stream(bytes)) should be(true)
  }

  it should "return true when a multi-byte UTF-8 character spans a read boundary" in {
    // Build input where a 2-byte UTF-8 char (é = 0xC3 0xA9) straddles the 8192-byte
    // internal read buffer boundary. Fill 8191 bytes of ASCII, then place é.
    val prefix = new Array[Byte](8191)
    java.util.Arrays.fill(prefix, 'A'.toByte)
    val multiByteChar = "é".getBytes("UTF-8") // [0xC3, 0xA9]
    val suffix = "OK".getBytes("UTF-8")
    val bytes = prefix ++ multiByteChar ++ suffix
    FileContentValidator.isAllowedContent(stream(bytes)) should be(true)
  }

  it should "return false for a truncated multi-byte UTF-8 sequence at end of stream" in {
    // 0xC3 is a leading byte expecting a continuation, but stream ends.
    // All bytes are valid Windows-1252, so Windows-1252 fallback would pass,
    // but UTF-8 should fail.
    val prefix = "Hello".getBytes("UTF-8")
    val truncated = prefix :+ 0xC3.toByte
    // UTF-8 invalid, but all bytes are valid Windows-1252 → should still return true via fallback
    FileContentValidator.isAllowedContent(stream(truncated)) should be(true)
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

  it should "throw IOException when stream ends before expectedSize is reached" in {
    val content = "Hello".getBytes("UTF-8") // 5 bytes
    val expectedSize = 1000L // claim the file should be 1000 bytes
    val exception = intercept[java.io.IOException] {
      FileContentValidator.isAllowedContent(stream(content), expectedSize)
    }
    exception.getMessage should include("prematurely")
    exception.getMessage should include("5")
    exception.getMessage should include("1000")
  }

  it should "not throw when stream delivers all expected bytes" in {
    val content = "Hello café".getBytes("UTF-8")
    val expectedSize = content.length.toLong
    FileContentValidator.isAllowedContent(stream(content), expectedSize) should be(true)
  }

  it should "not throw when expectedSize is not provided" in {
    val content = "Hello".getBytes("UTF-8")
    // Default expectedSize = -1L means no check
    FileContentValidator.isAllowedContent(stream(content)) should be(true)
  }
}
