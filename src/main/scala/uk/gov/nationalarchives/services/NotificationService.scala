package uk.gov.nationalarchives.services

import cats.effect.IO
import io.circe.generic.auto._
import io.circe.syntax._
import software.amazon.awssdk.services.sns.model.PublishResponse
import uk.gov.nationalarchives.aws.utils.sns.SNSUtils
import uk.gov.nationalarchives.services.NotificationService.FileCheckFailureEvent

import java.util.UUID

class NotificationService(snsUtils: SNSUtils, topicArn: String, environment: String) {

  def sendFileCheckFailureNotification(details: ConsignmentDetails): IO[PublishResponse] = {
    val event = FileCheckFailureEvent(
      consignmentType = details.consignmentType,
      consignmentReference = details.consignmentReference,
      consignmentId = details.consignmentId,
      transferringBodyName = details.transferringBody.getOrElse("Unknown"),
      userId = details.userId,
      environment = environment
    )

    IO(snsUtils.publish(event.asJson.noSpaces, topicArn))
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

  def apply(snsUtils: SNSUtils, topicArn: String, environment: String): NotificationService =
    new NotificationService(snsUtils, topicArn, environment)
}

