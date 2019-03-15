package io.casperlabs.comm.gossiping

import cats.syntax.option._
import com.google.protobuf.ByteString
import io.casperlabs.casper.consensus.Block
import io.casperlabs.shared.Compression
import io.casperlabs.crypto.codec.Base16
import io.casperlabs.comm.ServiceError.NotFound
import org.scalatest._
import org.scalatest.prop.GeneratorDrivenPropertyChecks.{forAll, PropertyCheckConfiguration}
import monix.eval.Task
import monix.execution.Scheduler
import monix.reactive.Observable
import monix.tail.Iterant
import scala.concurrent.duration._

class GrpcGossipServiceSpec extends WordSpecLike with Matchers with ArbitraryConsensus {

  import GrpcGossipServiceSpec._
  import Scheduler.Implicits.global

  def runTest(test: Task[Unit]) =
    test.runSyncUnsafe(5.seconds)

  "getBlocksChunked" when {
    // Just want to test with random blocks; variety doesn't matter.
    implicit val config = PropertyCheckConfiguration(minSuccessful = 1)

    "no compression is supported" should {
      "return a stream of uncompressed chunks" in {
        forAll { (block: Block) =>
          runTest {
            for {
              svc    <- TestService.fromBlock(block)
              req    = GetBlockChunkedRequest(blockHash = block.blockHash)
              chunks <- svc.getBlockChunked(req).toListL
            } yield {
              chunks.head.content.isHeader shouldBe true
              val header = chunks.head.getHeader
              header.compressionAlgorithm shouldBe ""
              chunks.size should be > 1

              Inspectors.forAll(chunks.tail) { chunk =>
                chunk.content.isData shouldBe true
                chunk.getData.size should be <= DefaultMaxChunkSize
              }

              val content  = chunks.tail.flatMap(_.getData.toByteArray).toArray
              val original = block.toByteArray
              header.contentLength shouldBe content.length
              header.originalContentLength shouldBe original.length
              content shouldBe original
            }
          }
        }
      }
    }

    "compression is supported" should {
      "return a stream of compressed chunks" in {
        forAll { (block: Block) =>
          runTest {
            for {
              svc <- TestService.fromBlock(block)
              req = GetBlockChunkedRequest(
                blockHash = block.blockHash,
                acceptedCompressionAlgorithms = Seq("lz4")
              )
              chunks <- svc.getBlockChunked(req).toListL
            } yield {
              chunks.head.content.isHeader shouldBe true
              val header = chunks.head.getHeader
              header.compressionAlgorithm shouldBe "lz4"

              val content  = chunks.tail.flatMap(_.getData.toByteArray).toArray
              val original = block.toByteArray
              header.contentLength shouldBe content.length
              header.originalContentLength shouldBe original.length

              val decompressed = Compression
                .decompress(content, header.originalContentLength)
                .get

              decompressed.size shouldBe original.size
              decompressed shouldBe original
            }
          }
        }
      }
    }

    "chunk size is specified" when {
      def testChunkSize(block: Block, requestedChunkSize: Int, expectedChunkSize: Int): Task[Unit] =
        for {
          svc    <- TestService.fromBlock(block)
          req    = GetBlockChunkedRequest(blockHash = block.blockHash, chunkSize = requestedChunkSize)
          chunks <- svc.getBlockChunked(req).toListL
        } yield {
          Inspectors.forAll(chunks.tail.init) { chunk =>
            chunk.getData.size shouldBe expectedChunkSize
          }
          chunks.last.getData.size should be <= expectedChunkSize
        }

      "it is less then the maximum" should {
        "use the requested chunk size" in {
          forAll { (block: Block) =>
            runTest {
              val smallChunkSize = DefaultMaxChunkSize / 2
              testChunkSize(block, smallChunkSize, smallChunkSize)
            }
          }
        }
      }

      "bigger than the maximum" should {
        "use the default chunk size" in {
          forAll { (block: Block) =>
            runTest {
              val bigChunkSize = DefaultMaxChunkSize * 2
              testChunkSize(block, bigChunkSize, DefaultMaxChunkSize)
            }
          }
        }
      }
    }

    "block cannot be found" should {
      "return NOT_FOUND" in {
        forAll(genHash) { (hash: ByteString) =>
          runTest {
            for {
              svc <- TestService.fromGetBlock(_ => None)
              req = GetBlockChunkedRequest(blockHash = hash)
              res <- svc.getBlockChunked(req).toListL.attempt
            } yield {
              res.isLeft shouldBe true
              res.left.get match {
                case NotFound(msg) =>
                  msg shouldBe s"Block ${Base16.encode(hash.toByteArray)} could not be found."
                case ex =>
                  fail(s"Unexpected error: $ex")
              }
            }
          }
        }
      }
    }

    "iteration is abandoned" should {
      "cancel the source" in {
        forAll { (block: Block) =>
          runTest {
            // Capture the event when the Observable created from the Iterant is canceled.
            var stopCount = 0
            var nextCount = 0
            implicit val oi = new ObservableIterant[Task] {
              def toObservable[A](it: Iterant[Task, A]) =
                Observable
                  .fromReactivePublisher(it.toReactivePublisher)
                  .doOnNext(_ => Task.delay(nextCount += 1))
                  .doOnEarlyStop(Task.delay(stopCount += 1))

              def toIterant[A](obs: Observable[A]) =
                Iterant.fromReactivePublisher[Task, A](
                  obs.toReactivePublisher,
                  requestCount = 1,
                  eagerBuffer = false
                )
            }

            for {
              // Pretend we are dealing with a gRPC source, i.e. using Observable.
              stub <- TestService.fromBlock(block)
              // Turn it back into how we'd consume it in code.
              client <- GrpcGossipService.toGossipService[Task](stub)
              req    = GetBlockChunkedRequest(blockHash = block.blockHash)
              // Consume just the head, cancel the rest.
              maybeHeader <- client
                              .getBlockChunked(req)
                              .foldWhileLeftEvalL(Task.now(none[Chunk.Header])) {
                                case (None, chunk) if chunk.content.isHeader =>
                                  Task.now(Right(Some(chunk.getHeader)))
                                case _ =>
                                  Task.now(Left(None))
                              }
            } yield {
              maybeHeader should not be empty
              stopCount shouldBe 1
              // Once we consume the first message it seems to fetch another one.
              nextCount shouldBe 2
            }
          }
        }
      }
    }
  }
}

object GrpcGossipServiceSpec {
  val DefaultMaxChunkSize = 100 * 1024

  object TestService {
    def fromBlock(block: Block)(implicit oi: ObservableIterant[Task]) =
      fromGetBlock(hash => Option(block).filter(_.blockHash == hash))

    def fromGetBlock(f: ByteString => Option[Block])(implicit oi: ObservableIterant[Task]) =
      GrpcGossipService.fromGossipService[Task] {
        new GossipServiceImpl[Task](
          getBlock = hash => Task.now(f(hash)),
          maxChunkSize = DefaultMaxChunkSize
        )
      }
  }
}
