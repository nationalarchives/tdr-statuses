package uk.gov.nationalarchives

import cats.effect.testing.scalatest.AsyncIOSpec
import io.circe.generic.auto._
import io.circe.parser.decode
import org.mockito.{ArgumentCaptor, ArgumentMatchers, Mockito}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import software.amazon.awssdk.services.sns.model.PublishResponse
import uk.gov.nationalarchives.aws.utils.sns.SNSUtils
import uk.gov.nationalarchives.services.{ConsignmentDetails, NotificationService}
import uk.gov.nationalarchives.services.NotificationService.FileCheckFailureEvent

import java.util.UUID

class NotificationServiceSpec extends AsyncWordSpec with AsyncIOSpec with Matchers {

  private val consignmentId = UUID.randomUUID()
  private val userId = UUID.randomUUID()
  private val topicArn = "arn:aws:sns:eu-west-2:123456789:test-topic"
  private val environment = "integration"

  private val details = ConsignmentDetails(
    consignmentId = consignmentId,
    consignmentType = "standard",
    consignmentReference = "TDR-2025-ABC",
    transferringBody = Some("Test Body"),
    userId = userId
  )

  "sendFileCheckFailureNotification" should {
    "publish a correctly formatted message to SNS" in {
      val mockSnsUtils = Mockito.mock(classOf[SNSUtils])
      val mockResponse = PublishResponse.builder().messageId("msg-123").build()
      val messageCaptor = ArgumentCaptor.forClass(classOf[String])
      val topicArnCaptor = ArgumentCaptor.forClass(classOf[String])

      Mockito.when(mockSnsUtils.publish(messageCaptor.capture(), topicArnCaptor.capture())).thenReturn(mockResponse)

      val service = NotificationService(mockSnsUtils, topicArn, environment)
      service.sendFileCheckFailureNotification(details).asserting { result =>
        result.messageId() shouldBe "msg-123"

        topicArnCaptor.getValue shouldBe topicArn

        val event = decode[FileCheckFailureEvent](messageCaptor.getValue)
        event.isRight shouldBe true
        val parsed = event.toOption.get
        parsed.consignmentType shouldBe "standard"
        parsed.consignmentReference shouldBe "TDR-2025-ABC"
        parsed.consignmentId shouldBe consignmentId
        parsed.transferringBodyName shouldBe "Test Body"
        parsed.userId shouldBe userId
        parsed.environment shouldBe "integration"
      }
    }

    "default consignmentType to Unknown when None" in {
      val detailsNoBody = details.copy(transferringBody = None)
      val mockSnsUtils = Mockito.mock(classOf[SNSUtils])
      val mockResponse = PublishResponse.builder().messageId("msg-456").build()
      val messageCaptor = ArgumentCaptor.forClass(classOf[String])

      Mockito.when(mockSnsUtils.publish(messageCaptor.capture(), ArgumentMatchers.any[String]())).thenReturn(mockResponse)

      val service = NotificationService(mockSnsUtils, topicArn, environment)
      service.sendFileCheckFailureNotification(detailsNoBody).asserting { result =>
        val event = decode[FileCheckFailureEvent](messageCaptor.getValue)
        event.toOption.get.transferringBodyName shouldBe "Unknown"
      }
    }

    "propagate SNS client errors" in {
      val mockSnsUtils = Mockito.mock(classOf[SNSUtils])

      Mockito.when(mockSnsUtils.publish(ArgumentMatchers.any[String](), ArgumentMatchers.any[String]()))
        .thenThrow(new RuntimeException("SNS unavailable"))

      val service = NotificationService(mockSnsUtils, topicArn, environment)

      service.sendFileCheckFailureNotification(details).attempt.asserting { result =>
        result.left.toOption.get shouldBe a[RuntimeException]
      }
    }
  }
}

