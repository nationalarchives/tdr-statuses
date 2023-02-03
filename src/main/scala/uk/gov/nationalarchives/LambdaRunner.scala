package uk.gov.nationalarchives

import java.io.{ByteArrayInputStream, ByteArrayOutputStream}

object LambdaRunner extends App {
  private val body =
    """{
      |  "key": "19286aee-5a39-4f11-bf35-738e22e553c3/3e0cf5f5-4630-45b7-98f8-a559ac324ce0/results.json",
      |  "bucket": "tdr-backend-checks-intg"
      |}
      |""".stripMargin

  private val baos = new ByteArrayInputStream(body.getBytes())
  val output = new ByteArrayOutputStream()
  new Lambda().run(baos, output)
  val res = output.toByteArray.map(_.toChar).mkString
  println(res)
}
