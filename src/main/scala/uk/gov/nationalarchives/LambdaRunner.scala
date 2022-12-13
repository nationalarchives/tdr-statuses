package uk.gov.nationalarchives

import java.io.{ByteArrayInputStream, ByteArrayOutputStream}

object LambdaRunner extends App {
  private val body =
    """{
      |  "results": [
      |    {
      |      "consignmentId": "68c11ad6-f7e2-4506-833a-a28426aecbdd",
      |      "fileId": "20d80488-d247-47cf-8687-be26de2558b5",
      |      "originalPath": "smallfile/subfolder/subfolder-nested/subfolder-nested-1.txt",
      |      "fileSize": "2",
      |      "clientChecksum": "87428fc522803d31065e7bce3cf03fe475096631e5e07bbd7a0fde60c4cf25c7",
      |      "consignmentType": "standard",
      |      "fileCheckResults": {
      |        "antivirus": [
      |          {
      |            "result": "",
      |            "fileId": "20d80488-d247-47cf-8687-be26de2558b5"
      |          }
      |        ],
      |        "checksum": [
      |          {
      |            "sha256Checksum": "87428fc522803d31065e7bce3cf03fe475096631e5e07bbd7a0fde60c4cf25c7"
      |          }
      |        ],
      |        "fileFormat": [
      |          {
      |            "matches": [
      |              {
      |                "puid": "fmt/866"
      |              }
      |            ]
      |          }
      |        ]
      |      }
      |    }
      |  ],
      |  "redactedResults": {
      |    "redactedFiles": [
      |      {
      |        "originalFileId": "4dbabaef-1fae-4d09-8faa-88e9dfb85b05",
      |        "originalFilePath": "originalPath",
      |        "redactedFileId": "7daa4ab6-ab7d-449a-88f5-a8ff1705b888",
      |        "redactedFilePath": "redactedPath"
      |      }
      |    ],
      |    "errors": [
      |      {
      |        "fileId": "97536958-2192-4485-af9a-d98a0290e692",
      |        "cause": "TestFailureReason"
      |      }
      |    ]
      |  }
      |}
      |""".stripMargin

  private val baos = new ByteArrayInputStream(body.getBytes())
  val output = new ByteArrayOutputStream()
  new Lambda().run(baos, output)
  val res = output.toByteArray.map(_.toChar).mkString
  println(res)
}
