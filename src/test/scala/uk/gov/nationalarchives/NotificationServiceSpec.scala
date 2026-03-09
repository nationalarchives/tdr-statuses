package uk.gov.nationalarchives

import cats.effect.unsafe.implicits.global
import io.circe.generic.auto._
import io.circe.parser.decode
import org.mockito.{ArgumentCaptor, ArgumentMatchers, Mockito}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import software.amazon.awssdk.services.sns.SnsClient
import software.amazon.awssdk.services.sns.model.{PublishRequest, PublishResponse}
import uk.gov.nationalarchives.services.{ConsignmentDetails, NotificationService}
import uk.gov.nationalarchives.services.NotificationService.FileCheckFailureEvent

import java.util.UUID

class NotificationServiceSpec extends AnyWordSpec with Matchers {

  private val consignmentId = UUID.randomUUID()
  private val userId = UUID.randomUUID()
  private val topicArn = "arn:aws:sns:eu-west-2:123456789:test-topic"
  private val environment = "integration"

  private val details = ConsignmentDetails(
    consignmentId = consignmentId,
    consignmentType = Some("standard"),
    consignmentReference = "TDR-2025-ABC",
    transferringBody = Some("Test Body"),
    userId = userId
  )

  "sendFileCheckFailureNotification" should {
    "publish a correctly formatted message to SNS" in {
      val mockSnsClient = Mockito.mock(classOf[SnsClient])
      val mockResponse = PublishResponse.builder().messageId("msg-123").build()
      val captor = ArgumentCaptor.forClass(classOf[PublishRequest])

      Mockito.when(mockSnsClient.publish(captor.capture())).thenReturn(mockResponse)

      val service = NotificationService(mockSnsClient, topicArn, environment)
      val result = service.sendFileCheckFailureNotification(details).unsafeRunSync()

      result.messageId() shouldBe "msg-123"

      val publishedRequest = captor.getValue
      publishedRequest.topicArn() shouldBe topicArn
      publishedRequest.subject() shouldBe "File Check Failure"

      val event = decode[FileCheckFailureEvent](publishedRequest.message())
      event.isRight shouldBe true
      val parsed = event.toOption.get
      parsed.consignmentType shouldBe "standard"
      parsed.consignmentReference shouldBe "TDR-2025-ABC"
      parsed.consignmentId shouldBe consignmentId
      parsed.transferringBody shouldBe "Test Body"
      parsed.userId shouldBe userId
      parsed.environment shouldBe "integration"
    }

    "default consignmentType to Unknown when None" in {
      val mockSnsClient = Mockito.mock(classOf[SnsClient])
      val mockResponse = PublishResponse.builder().messageId("msg-456").build()
      val captor = ArgumentCaptor.forClass(classOf[PublishRequest])

      Mockito.when(mockSnsClient.publish(captor.capture())).thenReturn(mockResponse)

      val detailsNoType = details.copy(consignmentType = None, transferringBody = None)
      val service = NotificationService(mockSnsClient, topicArn, environment)
      service.sendFileCheckFailureNotification(detailsNoType).unsafeRunSync()

      val event = decode[FileCheckFailureEvent](captor.getValue.message()).toOption.get
      event.consignmentType shouldBe "Unknown"
      event.transferringBody shouldBe "Unknown"
    }

    "propagate SNS client errors" in {
      val mockSnsClient = Mockito.mock(classOf[SnsClient])

      Mockito.when(mockSnsClient.publish(ArgumentMatchers.any[PublishRequest]()))
        .thenThrow(new RuntimeException("SNS unavailable"))

      val service = NotificationService(mockSnsClient, topicArn, environment)

      assertThrows[RuntimeException] {
        service.sendFileCheckFailureNotification(details).unsafeRunSync()
      }
    }
  }
}

