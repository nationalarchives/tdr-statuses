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

  private def evaluator: FileCheckStatusEvaluator =
    FileCheckStatusEvaluator(mock[GraphQlApiService], mock[NotificationService])

  "shouldSendFailureNotification" should {
    "return true when a Consignment status has value Failed" in {
      val statuses = List(
        Status(consignmentId, "Consignment", "ServerChecksum", "Failed"),
        Status(consignmentId, "Consignment", "ServerAntivirus", "Completed")
      )
      evaluator.shouldSendFailureNotification(statuses) shouldBe true
    }

    "return true when a Consignment status has value CompletedWithIssues" in {
      val statuses = List(
        Status(consignmentId, "Consignment", "ServerAntivirus", "CompletedWithIssues")
      )
      evaluator.shouldSendFailureNotification(statuses) shouldBe true
    }

    "return false when all Consignment statuses are Completed" in {
      val statuses = List(
        Status(consignmentId, "Consignment", "ServerChecksum", "Completed"),
        Status(consignmentId, "Consignment", "ServerAntivirus", "Completed"),
        Status(UUID.randomUUID(), "File", "FFID", "Failed")
      )
      evaluator.shouldSendFailureNotification(statuses) shouldBe false
    }

    "return false when there are only File-level statuses" in {
      val statuses = List(
        Status(UUID.randomUUID(), "File", "Antivirus", "Failed")
      )
      evaluator.shouldSendFailureNotification(statuses) shouldBe false
    }

    "return false when statuses are empty" in {

      evaluator.shouldSendFailureNotification(Nil) shouldBe false
    }
  }

  "processAndNotify" should {
    "send notification when a Consignment status is not Completed" in {
      val mockGraphQl = mock[GraphQlApiService]
      val mockNotification = mock[NotificationService]
      val mockResponse = PublishResponse.builder().messageId("msg-123").build()

      when(mockGraphQl.getConsignmentDetails(any[UUID]))
        .thenReturn(IO.pure(details))
      when(mockNotification.sendFileCheckFailureNotification(any[ConsignmentDetails]))
        .thenReturn(IO.pure(mockResponse))

      val eval = FileCheckStatusEvaluator(mockGraphQl, mockNotification)
      val statuses = List(Status(consignmentId, "Consignment", "ServerAntivirus", "Failed"))
      val result = eval.processAndNotify(consignmentId, statuses).unsafeRunSync()

      result shouldBe Some(mockResponse)
      verify(mockGraphQl).getConsignmentDetails(consignmentId)
      verify(mockNotification).sendFileCheckFailureNotification(details)
    }

    "return None when all Consignment statuses are Completed" in {
      val mockGraphQl = mock[GraphQlApiService]
      val mockNotification = mock[NotificationService]

      val eval = FileCheckStatusEvaluator(mockGraphQl, mockNotification)
      val statuses = List(
        Status(consignmentId, "Consignment", "ServerChecksum", "Completed"),
        Status(consignmentId, "Consignment", "ServerAntivirus", "Completed")
      )
      val result = eval.processAndNotify(consignmentId, statuses).unsafeRunSync()

      result shouldBe None
      verifyZeroInteractions(mockGraphQl)
      verifyZeroInteractions(mockNotification)
    }

    "return None when statuses are empty" in {
      val mockGraphQl = mock[GraphQlApiService]
      val mockNotification = mock[NotificationService]

      val eval = FileCheckStatusEvaluator(mockGraphQl, mockNotification)
      val result = eval.processAndNotify(consignmentId, Nil).unsafeRunSync()

      result shouldBe None
      verifyZeroInteractions(mockGraphQl)
      verifyZeroInteractions(mockNotification)
    }
  }
}

