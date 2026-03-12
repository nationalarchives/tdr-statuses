package uk.gov.nationalarchives.services

import cats.effect.IO
import software.amazon.awssdk.services.sns.model.PublishResponse
import uk.gov.nationalarchives.BackendCheckUtils.{File, Status}

import java.util.UUID

class FileCheckStatusEvaluator(
  graphQlApiService: GraphQlApiService,
  notificationService: NotificationService
) {

  def shouldSendFailureNotification(statuses: List[Status]): Boolean =
    statuses.exists(s => s.statusType == "Consignment" && s.statusValue != "Completed")

  def processAndNotify(result: File, statuses: List[Status]): IO[Option[PublishResponse]] = {
    if (shouldSendFailureNotification(statuses)) {
      for {
        details  <- graphQlApiService.getConsignmentDetails(result)
        response <- notificationService.sendFileCheckFailureNotification(details)
      } yield Some(response)
    } else {
      IO.pure(None)
    }
  }
}

object FileCheckStatusEvaluator {

  def apply(
    graphQlApiService: GraphQlApiService,
    notificationService: NotificationService
  ): FileCheckStatusEvaluator =
    new FileCheckStatusEvaluator(graphQlApiService, notificationService)

  /** No-op evaluator for tests that don't need notification behaviour. */
  val noOp: FileCheckStatusEvaluator = new FileCheckStatusEvaluator(null, null) {
    override def shouldSendFailureNotification(statuses: List[Status]): Boolean = false
  }
}

