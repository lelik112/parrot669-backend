package com.parrot669.integration

import cats.effect.{Deferred, IO, Ref}
import cats.effect.unsafe.implicits.global
import cats.syntax.all._
import com.parrot669.domain.GeocodeQuery
import scala.concurrent.duration._

class LocationIqPacingSuite extends munit.FunSuite {
  private val query = GeocodeQuery("Barcelona", "city", Some("ES"))
  test("concurrent starts stay spaced when the first response is slow") {
    (for {
      starts <- Ref.of[IO, List[FiniteDuration]](Nil)
      client <- LocationIqClient.paced[IO](_ => IO.monotonic.flatMap(t => starts.update(_ :+ t)) *>
        IO.sleep(80.millis).as(LocationIqResponse(200, "[]")), 50.millis, 1.second)
      _ <- List.fill(3)(client.autocomplete(query, "key")).parSequence
      times <- starts.get
    } yield {
      assertEquals(times.size, 3)
      assert(times.sliding(2).forall(pair => pair(1) - pair(0) >= 50.millis))
    }).unsafeRunSync()
  }
  test("busy gate times out without calling provider; cancellation releases it") {
    (for {
      entered <- Deferred[IO, Unit]
      calls <- Ref.of[IO, Int](0)
      client <- LocationIqClient.paced[IO](_ => calls.updateAndGet(_ + 1).flatMap {
        case 1 => entered.complete(()) *> IO.never[LocationIqResponse]
        case _ => IO.pure(LocationIqResponse(200, "[]"))
      }, 10.millis, 40.millis)
      first <- client.autocomplete(query, "key").start
      _ <- entered.get
      busy <- client.autocomplete(query, "key").attempt
      beforeCancel <- calls.get
      _ <- first.cancel
      recovered <- client.autocomplete(query, "key")
    } yield {
      assert(busy.left.toOption.exists(_.isInstanceOf[LocationIqRateLimited]))
      assertEquals(beforeCancel, 1); assertEquals(recovered, Nil)
    }).unsafeRunSync()
  }
}
