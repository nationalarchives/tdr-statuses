package uk.gov.nationalarchives

import cats.effect.unsafe.implicits.global
import uk.gov.nationalarchives.services.GraphQlApiService

import java.util.UUID

object ConsignmentMetadataRunner extends App {

  private val consignmentId: UUID = UUID.fromString("589c8bf8-c64d-4e38-b226-42aed5f757ed")

  println(s"Fetching consignment details for: $consignmentId")

  private val details = GraphQlApiService.service
    .getConsignmentDetails(consignmentId)
    .unsafeRunSync()

  println(s"Consignment Type:      ${details.consignmentType.getOrElse("N/A")}")
  println(s"Consignment Reference: ${details.consignmentReference}")
  println(s"Consignment ID:        ${details.consignmentId}")
  println(s"Transferring Body:     ${details.transferringBody.getOrElse("N/A")}")
  println(s"User ID:               ${details.userId}")
}
