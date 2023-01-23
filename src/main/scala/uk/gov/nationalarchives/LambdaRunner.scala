package uk.gov.nationalarchives

import java.io.{ByteArrayInputStream, ByteArrayOutputStream}

object LambdaRunner extends App {
  private val body =
    """{
      |  "key": "a4c0e084-7562-46dd-9724-92ac9b64d149/d5cd3fb2-4869-4d28-9537-4655486a8a2d/results.json",
      |  "bucket": "tdr-backend-checks-intg"
      |}
      |""".stripMargin

  private val baos = new ByteArrayInputStream(body.getBytes())
  val output = new ByteArrayOutputStream()
  new Lambda().run(baos, output)
  val res = output.toByteArray.map(_.toChar).mkString
  println(res)
}
