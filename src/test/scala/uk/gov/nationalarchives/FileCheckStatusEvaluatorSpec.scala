package uk.gov.nationalarchives

import cats.effect.IO
import cats.effect.testing.scalatest.AsyncIOSpec
import org.mockito.ArgumentMatchers._
import org.mockito.MockitoSugar
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import software.amazon.awssdk.services.sns.model.PublishResponse
import uk.gov.nationalarchives.BackendCheckUtils.Status
import uk.gov.nationalarchives.services._

import java.util.UUID

class FileCheckStatusEvaluatorSpec extends AsyncWordSpec with AsyncIOSpec with Matchers with MockitoSugar {

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
      eval.processAndNotify(consignmentId, statuses).asserting { result =>
        verify(mockGraphQl).getConsignmentDetails(consignmentId)
        verify(mockNotification).sendFileCheckFailureNotification(details)
        result shouldBe Some(mockResponse)
      }
    }

    "return None when all Consignment statuses are Completed" in {
      val mockGraphQl = mock[GraphQlApiService]
      val mockNotification = mock[NotificationService]

      val eval = FileCheckStatusEvaluator(mockGraphQl, mockNotification)
      val statuses = List(
        Status(consignmentId, "Consignment", "ServerChecksum", "Completed"),
        Status(consignmentId, "Consignment", "ServerAntivirus", "Completed")
      )
      eval.processAndNotify(consignmentId, statuses).asserting { result =>
        verifyZeroInteractions(mockGraphQl)
        verifyZeroInteractions(mockNotification)
        result shouldBe None
      }
    }

    "return None when statuses are empty" in {
      val mockGraphQl = mock[GraphQlApiService]
      val mockNotification = mock[NotificationService]

      val eval = FileCheckStatusEvaluator(mockGraphQl, mockNotification)
      eval.processAndNotify(consignmentId, Nil).asserting { result =>
        verifyZeroInteractions(mockGraphQl)
        verifyZeroInteractions(mockNotification)
        result shouldBe None
      }
    }
  }
}

