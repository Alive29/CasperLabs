package io.casperlabs.shared

import cats._
import cats.implicits._
import org.scalatest.{FunSpec, Matchers}
import org.scalatest.prop.GeneratorDrivenPropertyChecks
import org.scalacheck.Arbitrary.arbitrary
import org.scalacheck.{Arbitrary, Gen}
import monix.eval.Task
import monix.execution.Scheduler

import scala.util.{Failure, Success, Try}
import scala.concurrent.duration._

class StreamTSpec extends FunSpec with Matchers with GeneratorDrivenPropertyChecks {
  import StreamTSpec._

  describe("StreamT") {
    it("should be able to be constructed from lists") {
      forAll { (list: List[Int]) =>
        val stream: StreamT[Id, Int] = StreamT.fromList[Id, Int](list)

        stream.toList[Int] shouldBe list
      }
    }

    it("should be able to be constructed from iterators") {
      forAll { (list: List[Int]) =>
        val it                       = list.iterator
        val stream: StreamT[Id, Int] = StreamT.fromIterator[Id, Int](it)

        stream.toList[Int] shouldBe list
      }
    }

    it("should be able to be constructed from delayed iterator constructors") {
      import Scheduler.Implicits.global
      forAll { (list: List[Int]) =>
        val it                         = Task.delay(list.iterator)
        val stream: StreamT[Task, Int] = StreamT.fromIterator[Task, Int](it)

        stream.toList[Int].foreach(_ shouldBe list)
      }
    }

    it("should correctly compute heads") {
      forAll { (list: List[Int]) =>
        whenever(list.nonEmpty) {
          val stream: StreamT[Id, Int] = StreamT.fromList[Id, Int](list)

          stream.head[Int](mErrId) shouldBe list.head
        }
      }
    }

    it("should correctly compute headOption") {
      forAll { (list: List[Int]) =>
        if (list.isEmpty) {
          StreamT.fromList[Id, Int](list).headOption[Int](mErrId) shouldBe none[Int]
        } else {
          val stream: StreamT[Id, Int] = StreamT.fromList[Id, Int](list)

          stream.headOption[Int](mErrId) shouldBe Some(list.head)
        }
      }
    }

    it("should correctly compute tails") {
      forAll { (list: List[Int]) =>
        whenever(list.nonEmpty) {
          val stream: StreamT[Id, Int] = StreamT.fromList[Id, Int](list)

          stream.tail(mErrId).toList[Int] shouldBe list.tail
        }
      }
    }

    it("should be able to zip with other StreamTs") {
      forAll { (listA: List[Int], listB: List[String]) =>
        val streamA = StreamT.fromList[Id, Int](listA)
        val streamB = StreamT.fromList[Id, String](listB)

        listA.zip(listB) shouldBe streamA.zip(streamB).toList
      }
    }

    it("should allow taking a finite number of terms") {
      forAll { (list: List[Int], n: Int) =>
        val stream: StreamT[Id, Int] = StreamT.fromList[Id, Int](list)

        stream.take(n).toList[Int] shouldBe list.take(n)
      }

    }

    it(
      "should allow taking the longest prefix of this StreamT whose elements satisfy the predicate"
    ) {
      forAll { list: List[Int] =>
        val stream: StreamT[Id, Int] = StreamT.fromList[Id, Int](list)

        stream.takeWhile(_ < 100).toList[Int] shouldBe list.takeWhile(_ < 100)
      }

    }

    it(
      "should allow taking the longest prefix of this StreamT whose elements satisfy the predicate " +
        "and include first element that does not satisfy it"
    ) {
      forAll { list: List[Int] =>
        val stream = StreamT.fromList[Id, Int](list)

        val smaller     = list.takeWhile(_ < 100)
        val stopElement = list.slice(smaller.size, smaller.size + 1)

        stream.takeUntil(_ >= 100).toList shouldBe (smaller ++ stopElement)
      }
    }

    it("should allow dropping a finite number of terms") {
      forAll { (list: List[Int], n: Int) =>
        val stream: StreamT[Id, Int] = StreamT.fromList[Id, Int](list)

        stream.drop(n).toList[Int] shouldBe list.drop(n)
      }

    }

    it("should allow dropping a finite number of terms until a term doesn't satisfy the predicate") {
      forAll { list: List[Int] =>
        val stream: StreamT[Id, Int] = StreamT.fromList[Id, Int](list)

        stream.dropWhile(_ < 100).toList[Int] shouldBe list.dropWhile(_ < 100)
      }
    }

    it("should find elements properly in") {
      forAll { (list: List[Int]) =>
        val stream: StreamT[Id, Int] = StreamT.fromList[Id, Int](list)

        stream.find[Int](_ % 2 == 0) shouldBe list.find(_ % 2 == 0)
      }
    }

    it("should foldLeft properly in") {
      forAll { (list: List[Int]) =>
        val stream: StreamT[Id, Int] = StreamT.fromList[Id, Int](list)

        stream.foldLeft[Int](0)(_ + _) shouldBe list.sum
      }
    }

    it("should foldWhileLeft properly in") {
      forAll {
        for {
          list <- arbitrary[List[Int]]
          n    <- Gen.choose(0, list.size)
        } yield (list, n)
      } {
        case (list: List[Int], n: Int) =>
          val stream: StreamT[Id, Int] = StreamT.fromList[Id, Int](list)

          val (sum, _) = stream
            .foldWhileLeft[(Int, Int)]((0, 0)) {
              case ((s, i), x) =>
                if (i < n) Left((s + x, i + 1)) else Right((s, n))
            }

          sum shouldBe list.take(n).sum
      }
    }

    it("should map properly in") {
      forAll { (list: List[Int]) =>
        val stream: StreamT[Id, Int] = StreamT.fromList[Id, Int](list)

        stream.map(_ * 2).toList[Int] shouldBe list.map(_ * 2)
      }
    }

    it("should flatMap properly in") {
      forAll { (list: List[Int]) =>
        val stream: StreamT[Id, Int] = StreamT.fromList[Id, Int](list)

        stream
          .flatMap(i => StreamT.fromList[Id, Int](List(i + 1, i + 2, i + 3)))
          .toList[Int] shouldBe list.flatMap(i => List(i + 1, i + 2, i + 3))
      }
    }
    it("should be able lazily construct infinite sequences") {
      lazy val fibs: StreamT[Id, Long] =
        StreamT.cons(
          0L,
          Eval.now(pure(StreamT.cons(1L, Eval.later(pure(fibs.zip(fibs.tail(mErrId)).map {
            case (a, b) => a + b
          })))))
        )

      fibs.take(8).toList[Long] shouldBe List(0L, 1L, 1L, 2L, 3L, 5L, 8L, 13L)
    }
  }

  private def pure[A](value: A): Id[A] = Applicative[Id].pure(value)
}

object StreamTSpec {
  val mErrId = unsafeMErr[Id]

  def unsafeMErr[F[_]: Monad]: MonadError[F, Throwable] =
    new MonadError[F, Throwable] {
      def pure[A](x: A): F[A]                                 = Monad[F].pure(x)
      def flatMap[A, B](fa: F[A])(f: A => F[B]): F[B]         = Monad[F].flatMap(fa)(f)
      def tailRecM[A, B](a: A)(f: A => F[Either[A, B]]): F[B] = Monad[F].tailRecM(a)(f)
      def raiseError[A](e: Throwable): F[A]                   = throw e
      def handleErrorWith[A](fa: F[A])(f: Throwable => F[A]): F[A] = Try(fa) match {
        case Success(x) => x
        case Failure(e) => f(e)
      }
    }
}
