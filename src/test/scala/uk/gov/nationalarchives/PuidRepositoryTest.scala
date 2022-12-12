package uk.gov.nationalarchives

import cats.effect._
import org.mockito.ArgumentMatchers.any
import org.mockito.{ArgumentCaptor, MockitoSugar}
import org.scalatest.flatspec.AnyFlatSpec
import skunk.{Query, Session, Void}
import uk.gov.nationalarchives.PuidRepository.PuidInformation
import cats.effect.testing.scalatest.AsyncIOSpec
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers._

import scala.jdk.CollectionConverters.ListHasAsScala

class PuidRepositoryTest extends AsyncFlatSpec with MockitoSugar with AsyncIOSpec {

  "allPuids" should "call the correct queries" in {
    val session = mock[Session[IO]]
    val queryCaptor: ArgumentCaptor[Query[Void, PuidInformation]] = ArgumentCaptor.forClass(classOf[Query[Void, PuidInformation]])
    when(session.execute[PuidInformation](queryCaptor.capture()))
      .thenReturn(IO(List(PuidInformation("", ""))))
    val repository = new PuidRepository[IO](Resource.pure(session))
    repository.allPuids.asserting(_ => {
      val captureValues = queryCaptor.getAllValues.asScala
      captureValues.head.sql.trim should equal("""SELECT "PUID", 'NonJudgmentFormat'::text FROM "AllowedPuids";""")
      captureValues.last.sql.trim should equal("""SELECT "PUID", "Reason" FROM "DisallowedPuids"""")
    })
  }

  "allPuids" should "return the correct values" in {
    val session = mock[Session[IO]]

    when(session.execute[PuidInformation](any[Query[Void, PuidInformation]]))
      .thenReturn(IO(List(PuidInformation("allowed-1", "Allowed"))), IO(List(PuidInformation("disallowed-1", "Disallowed"))))
    val repository = new PuidRepository[IO](Resource.pure(session))
    repository.allPuids.asserting(all => {
      all.allowedPuids.size shouldBe 1
      all.allowedPuids.head.puid shouldBe "allowed-1"
      all.allowedPuids.head.reason shouldBe "Allowed"

      all.disallowedPuids.size shouldBe 1
      all.disallowedPuids.head.puid shouldBe "disallowed-1"
      all.disallowedPuids.head.reason shouldBe "Disallowed"
    })
  }
}
