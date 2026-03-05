package uk.gov.nationalarchives

import cats.effect.unsafe.implicits.global
import com.nimbusds.oauth2.sdk.token.BearerAccessToken
import graphql.codegen.GetConsignment.{getConsignment => gc}
import graphql.codegen.GetConsignmentType.{getConsignmentType => gct}
import org.mockito.ArgumentMatchers._
import org.mockito.MockitoSugar
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sttp.client3.{HttpURLConnectionBackend, Identity, SttpBackend}
import uk.gov.nationalarchives.services.{ConsignmentDetails, ConsignmentNotFound, GraphQlApiService}
import uk.gov.nationalarchives.tdr.{GraphQLClient, GraphQlResponse}
import uk.gov.nationalarchives.tdr.keycloak.{KeycloakUtils, TdrKeycloakDeployment}

import java.util.UUID
import scala.concurrent.Future

class GraphQlApiServiceSpec extends AnyWordSpec with Matchers with MockitoSugar {

  implicit val backend: SttpBackend[Identity, Any] = HttpURLConnectionBackend()
  implicit val deployment: TdrKeycloakDeployment = TdrKeycloakDeployment("https://auth", "tdr", 3600)

  private val consignmentId: UUID = UUID.randomUUID()
  private val userId: UUID = UUID.randomUUID()

  private def createService(
    gcResponse: GraphQlResponse[gc.Data],
    gctResponse: GraphQlResponse[gct.Data]
  ): GraphQlApiService = {
    val mockConsignmentClient     = mock[GraphQLClient[gc.Data, gc.Variables]]
    val mockConsignmentTypeClient = mock[GraphQLClient[gct.Data, gct.Variables]]
    val mockKeycloak              = mock[KeycloakUtils]

    when(mockKeycloak.serviceAccountToken(anyString(), anyString())(any(), any(), any()))
      .thenReturn(Future.successful(new BearerAccessToken("token")))

    when(mockConsignmentClient.getResult(any[BearerAccessToken], any(), any())(any(), any()))
      .thenReturn(Future.successful(gcResponse))

    when(mockConsignmentTypeClient.getResult(any[BearerAccessToken], any(), any())(any(), any()))
      .thenReturn(Future.successful(gctResponse))

    GraphQlApiService(mockConsignmentClient, mockConsignmentTypeClient, mockKeycloak)
  }

  "getConsignmentDetails" should {
    "return ConsignmentDetails when both queries return data" in {
      val gcData = gc.Data(Some(gc.GetConsignment(
        userid = userId,
        seriesid = None,
        parentFolder = None,
        consignmentReference = "TDR-2025-ABC",
        parentFolderId = None,
        clientSideDraftMetadataFileName = None,
        transferringBodyName = Some("Test Body"),
        consignmentStatuses = Nil
      )))
      val gctData = gct.Data(Some(gct.GetConsignment(consignmentType = Some("standard"))))

      val service = createService(
        GraphQlResponse(Some(gcData), Nil),
        GraphQlResponse(Some(gctData), Nil)
      )

      val result = service.getConsignmentDetails(consignmentId).unsafeRunSync()

      result shouldBe ConsignmentDetails(
        consignmentId = consignmentId,
        consignmentType = Some("standard"),
        consignmentReference = "TDR-2025-ABC",
        transferringBody = Some("Test Body"),
        userId = userId
      )
    }

    "raise ConsignmentNotFound when GetConsignment returns no consignment" in {
      val gcData  = gc.Data(None)
      val gctData = gct.Data(Some(gct.GetConsignment(consignmentType = Some("standard"))))

      val service = createService(
        GraphQlResponse(Some(gcData), Nil),
        GraphQlResponse(Some(gctData), Nil)
      )

      assertThrows[ConsignmentNotFound] {
        service.getConsignmentDetails(consignmentId).unsafeRunSync()
      }
    }

    "raise ConsignmentNotFound when GetConsignmentType returns no consignment" in {
      val gcData = gc.Data(Some(gc.GetConsignment(
        userid = userId,
        seriesid = None,
        parentFolder = None,
        consignmentReference = "TDR-2025-ABC",
        parentFolderId = None,
        clientSideDraftMetadataFileName = None,
        transferringBodyName = Some("Test Body"),
        consignmentStatuses = Nil
      )))
      val gctData = gct.Data(None)

      val service = createService(
        GraphQlResponse(Some(gcData), Nil),
        GraphQlResponse(Some(gctData), Nil)
      )

      assertThrows[ConsignmentNotFound] {
        service.getConsignmentDetails(consignmentId).unsafeRunSync()
      }
    }

    "raise ConsignmentNotFound when GetConsignment returns no data" in {
      val service = createService(
        GraphQlResponse[gc.Data](None, Nil),
        GraphQlResponse(Some(gct.Data(Some(gct.GetConsignment(Some("standard"))))), Nil)
      )

      assertThrows[ConsignmentNotFound] {
        service.getConsignmentDetails(consignmentId).unsafeRunSync()
      }
    }
  }
}
