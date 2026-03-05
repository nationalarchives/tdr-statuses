package uk.gov.nationalarchives

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.mockito.ArgumentMatchers._
import org.mockito.MockitoSugar
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import software.amazon.awssdk.services.sns.model.PublishResponse
import uk.gov.nationalarchives.BackendCheckUtils.Status
import uk.gov.nationalarchives.services._

import java.util.UUID

class FileCheckStatusEvaluatorSpec extends AnyWordSpec with Matchers with MockitoSugar {

  private val consignmentId = UUID.randomUUID()
  private val userId = UUID.randomUUID()

  private val details = ConsignmentDetails(
    consignmentId = consignmentId,
    consignmentType = Some("standard"),
    consignmentReference = "TDR-2025-ABC",
    transferringBody = Some("Test Body"),
    userId = userId
  )

  "shouldSendFailureNotification" should {
    "return true when statuses are non-empty" in {
      val evaluator = FileCheckStatusEvaluator(mock[GraphQlApiService], mock[NotificationService])
      val statuses = List(Status(UUID.randomUUID(), "Consignment", "ServerAntivirus", "CompletedWithIssues"))

      evaluator.shouldSendFailureNotification(statuses) shouldBe true
    }

    "return false when statuses are empty" in {
      val evaluator = FileCheckStatusEvaluator(mock[GraphQlApiService], mock[NotificationService])

      evaluator.shouldSendFailureNotification(Nil) shouldBe false
    }
  }

  "processAndNotify" should {
    "fetch consignment details and send notification when statuses are non-empty" in {
      val mockGraphQl = mock[GraphQlApiService]
      val mockNotification = mock[NotificationService]
      val mockResponse = PublishResponse.builder().messageId("msg-123").build()

      when(mockGraphQl.getConsignmentDetails(any[UUID]))
        .thenReturn(IO.pure(details))
      when(mockNotification.sendFileCheckFailureNotification(any[ConsignmentDetails]))
        .thenReturn(IO.pure(mockResponse))

      val evaluator = FileCheckStatusEvaluator(mockGraphQl, mockNotification)

      val statuses = List(Status(UUID.randomUUID(), "Consignment", "ServerAntivirus", "CompletedWithIssues"))
      val result = evaluator.processAndNotify(consignmentId, statuses).unsafeRunSync()

      result shouldBe Some(mockResponse)
      verify(mockGraphQl).getConsignmentDetails(consignmentId)
      verify(mockNotification).sendFileCheckFailureNotification(details)
    }

    "return None and not call services when statuses are empty" in {
      val mockGraphQl = mock[GraphQlApiService]
      val mockNotification = mock[NotificationService]

      val evaluator = FileCheckStatusEvaluator(mockGraphQl, mockNotification)
      val result = evaluator.processAndNotify(consignmentId, Nil).unsafeRunSync()

      result shouldBe None
      verifyZeroInteractions(mockGraphQl)
      verifyZeroInteractions(mockNotification)
    }
  }
}

