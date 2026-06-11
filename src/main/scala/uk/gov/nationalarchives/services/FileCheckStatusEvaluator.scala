package uk.gov.nationalarchives.services

import cats.effect.IO
import software.amazon.awssdk.services.sns.model.PublishResponse
import uk.gov.nationalarchives.BackendCheckUtils.{File, Status}
import uk.gov.nationalarchives.tdr.common.utils.statuses.StatusActions
import uk.gov.nationalarchives.services.ResolutionPath._
import uk.gov.nationalarchives.tdr.common.utils.statuses.StatusActions.{TNASupport => ActionTNASupport, UserFixable => ActionUserFixable}
import uk.gov.nationalarchives.tdr.common.utils.statuses.StatusTypes._
import uk.gov.nationalarchives.tdr.common.utils.statuses.StatusValues._

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
        statusesToAction = statuses.flatMap(status => StatusActions.action(toStatusType(status.statusName), StatusValue(status.statusValue)))
        hasUserFixable   = statusesToAction.exists(_.actionType == ActionUserFixable)
        hasTNASupport    = statusesToAction.exists(_.actionType == ActionTNASupport)
        resolutionPath   = (hasUserFixable, hasTNASupport) match {
                             case (true, true)  => UserFixableAndTNASupport
                             case (true, false) => UserFixable
                             case _             => TNASupport
                           }
        response <- notificationService.sendFileCheckFailureNotification(details, resolutionPath)
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

