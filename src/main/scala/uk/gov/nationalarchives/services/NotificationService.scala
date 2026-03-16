package uk.gov.nationalarchives.services

import cats.effect.IO
import io.circe.generic.auto._
import io.circe.syntax._
import software.amazon.awssdk.services.sns.SnsClient
import software.amazon.awssdk.services.sns.model.{PublishRequest, PublishResponse}
import uk.gov.nationalarchives.services.NotificationService.FileCheckFailureEvent

import java.util.UUID

class NotificationService(snsClient: SnsClient, topicArn: String, environment: String) {

  def sendFileCheckFailureNotification(details: ConsignmentDetails): IO[PublishResponse] = {
    val event = FileCheckFailureEvent(
      consignmentType = details.consignmentType,
      consignmentReference = details.consignmentReference,
      consignmentId = details.consignmentId,
      transferringBodyName = details.transferringBody.getOrElse("Unknown"),
      userId = details.userId,
      environment = environment
    )

    IO.blocking {
      val request = PublishRequest.builder()
        .topicArn(topicArn)
        .message(event.asJson.noSpaces)
        .subject("File Check Failure")
        .build()
      snsClient.publish(request)
    }
  }
}

object NotificationService {

  case class FileCheckFailureEvent(
                                    consignmentType: String,
                                    consignmentReference: String,
                                    consignmentId: UUID,
                                    transferringBodyName: String,
                                    userId: UUID,
                                    environment: String
  )

  def apply(snsClient: SnsClient, topicArn: String, environment: String): NotificationService =
    new NotificationService(snsClient, topicArn, environment)
}

