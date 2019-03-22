package io.casperlabs.comm.gossiping

import com.google.protobuf.ByteString
import io.casperlabs.casper.consensus.{Block, BlockSummary}
import io.casperlabs.comm.discovery.Node
import io.casperlabs.comm.GossipError
import monix.eval.Task
import monix.execution.Scheduler
import org.scalatest._
import org.scalacheck.{Arbitrary, Gen}, Arbitrary.arbitrary
import scala.concurrent.duration._

class DownloadManagerSpec extends WordSpecLike with Matchers with ArbitraryConsensus {

  import DownloadManagerSpec._
  import Scheduler.Implicits.global

  "DownloadManager" when {
    "scheduled to download a section of the DAG" should {
      "download blocks in topoligical order" in (pending)
      "download siblings in parallel" in (pending)
      "eventually download the full DAG" in (pending)
      "not exceed the limit on parallelism" in (pending)
    }

    "scheduled to download a block with missing dependencies" should {
      val block  = arbitrary[Block].suchThat(_.getHeader.parentHashes.nonEmpty).sample.get
      val source = arbitrary[Node].sample.get
      val remote = MockGossipService.fromBlocks(Seq(block))

      "raise an error" in TestFixture(remote = _ => remote) { manager =>
        manager.scheduleDownload(summaryOf(block), source, false).attempt.map {
          case Left(GossipError.MissingDependencies(_)) =>
          case other =>
            fail(s"Expected scheduling to fail; got $other")
        }
      }

      "accept it if the dependency has already been scheduled" in (pending)
    }

    "scheduled to download a block which already exists" should {
      "skip the download" in (pending)
    }
    "scheduled to download a block which is already downloading" should {
      "skip the download" in (pending)
    }
    "released as a resource" should {
      "cancel outstanding downloads" in (pending)
      "reject further schedules" in (pending)
    }
    "fails to download a block for any reason" should {
      "carry on downloading other blocks from other nodes" in (pending)
      "try to download the block from a different source" in (pending)
    }
    "downloaded a valid block" should {
      "validate the block" in (pending)
      "store the block" in (pending)
      "store the block summary" in (pending)
      "gossip to other nodes" in (pending)
    }
    "cannot validate a block" should {
      "not download the dependant blocks" in (pending)
      "not store the block" in (pending)
    }
    "cannot connect to a node" should {
      "try again if the same block from the same source is scheduled again" in (pending)
      "try again later with exponential backoff" in (pending)
    }
    "receiving chunks" should {
      "check that they start with the header" in (pending)
      "check that the total size doesn't exceed promises" in (pending)
      "check that the expected compression algorithm is used" in (pending)
    }

    "cannot query the backend" should {
      val block1 = arbitrary[Block].sample.get
      val block2 = arbitrary[Block].sample.map(withoutDependencies).get
      val source = arbitrary[Node].sample.get
      val remote = MockGossipService.fromBlocks(Seq(block1, block2))
      val local = new MockBackend {
        override def hasBlock(blockHash: ByteString) =
          if (blockHash == block1.blockHash || dependenciesOf(block1).contains(blockHash))
            Task.raiseError(new RuntimeException("Oh no!"))
          else
            Task.pure(false)
      }

      "raise a scheduling error, but keep processing schedules" in TestFixture(local, _ => remote) {
        manager =>
          for {
            attempt1 <- manager.scheduleDownload(summaryOf(block1), source, false).attempt
            attempt2 <- manager.scheduleDownload(summaryOf(block2), source, false).attempt
          } yield {
            attempt1 match {
              case Left(ex: RuntimeException) =>
                ex.getMessage shouldBe "Oh no!"
              case other =>
                fail(s"Expected scheduling to fail; got $other")
            }
            attempt2 shouldBe Right(())
          }
      }
    }
  }
}

object DownloadManagerSpec {

  def summaryOf(block: Block): BlockSummary =
    BlockSummary()
      .withBlockHash(block.blockHash)
      .withHeader(block.getHeader)
      .withSignature(block.getSignature)

  def dependenciesOf(block: Block): Seq[ByteString] =
    block.getHeader.parentHashes ++ block.getHeader.justifications.map(_.latestBlockHash)

  def withoutDependencies(block: Block): Block =
    block.withHeader(block.getHeader.copy(parentHashes = Seq.empty, justifications = Seq.empty))

  object TestFixture {
    def apply(
        local: MockBackend = MockBackend.default,
        remote: Node => Task[GossipService[Task]] = _ => MockGossipService.default,
        maxParallelDownloads: Int = 1
    )(
        test: DownloadManager[Task] => Task[Unit]
    )(implicit scheduler: Scheduler): Unit = {

      val managerR = DownloadManagerImpl[Task](
        maxParallelDownloads = maxParallelDownloads,
        connectToGossip = remote(_),
        backend = local
      )

      val runTest = managerR.use { manager =>
        test(manager)
      }

      runTest.runSyncUnsafe(5.seconds)
    }
  }

  trait MockBackend extends DownloadManagerImpl.Backend[Task] {
    def hasBlock(blockHash: ByteString): Task[Boolean]       = Task.now(false)
    def validateBlock(block: Block): Task[Unit]              = Task.unit
    def storeBlock(block: Block): Task[Unit]                 = Task.unit
    def storeBlockSummary(summary: BlockSummary): Task[Unit] = Task.unit
  }
  object MockBackend {
    val default = new MockBackend {}
  }

  /** Test implementation of the remote GossipService to download the blocks from. */
  object MockGossipService {
    val default = Task.now {
      new GossipServiceServer[Task](
        getBlock = hash => Task.now(None),
        getBlockSummary = hash => ???,
        maxChunkSize = 100 * 1024
      )
    }
    def fromBlocks(blocks: Seq[Block]) = Task.now {
      val blockMap = blocks.groupBy(_.blockHash).mapValues(_.head)
      new GossipServiceServer[Task](
        getBlock = hash => Task.now(blockMap.get(hash)),
        getBlockSummary = hash => ???,
        maxChunkSize = 100 * 1024
      )
    }
  }
}
