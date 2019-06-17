package io.casperlabs.casper.genesis

import java.io.PrintWriter
import java.nio.file.{Files, Path, Paths}
import java.util.Base64

import cats.effect.Sync
import com.google.protobuf.ByteString
import io.casperlabs.blockstorage.BlockStore
import io.casperlabs.casper.helper.{BlockDagStorageFixture, HashSetCasperTestNode}
import io.casperlabs.casper.protocol.Bond
import io.casperlabs.casper.util.ProtoUtil
import io.casperlabs.casper.util.execengine.ExecutionEngineServiceStub
import io.casperlabs.p2p.EffectsTestInstances.{LogStub, LogicalTime}
import io.casperlabs.shared.PathOps.RichPath
import io.casperlabs.smartcontracts.ExecutionEngineService
import io.casperlabs.blockstorage.BlockMetadata
import io.casperlabs.storage.BlockMsgWithTransform
import monix.eval.Task
import org.scalatest.{FlatSpec, Matchers}

class GenesisTest extends FlatSpec with Matchers with BlockDagStorageFixture {
  import GenesisTest._

  val validators = Seq(
    "KZZwxShJ8aqC6N/lvocsFrYAvwnMiYPgS5A0ETWPLeY=",
    "a/GydTUB0C04Z4lQam2TaB0imcbt/URV9Za5e8VyWWg="
  ).zipWithIndex

  val walletAddresses = Seq(
    "0x20356b6fae3a94db5f01bdd45347faFad3dd18ef",
    "0x041e1eec23d118f0c4ffc814d4f415ac3ef3dcff"
  ).zipWithIndex

  def printBonds(bondsFile: String): Unit = {
    val pw = new PrintWriter(bondsFile)
    pw.println(
      validators
        .map {
          case (v, i) => s"$v $i"
        }
        .mkString("\n")
    )
    pw.close()
  }

  def printWallets(walletsFile: String): Unit = {
    val pw = new PrintWriter(walletsFile)
    pw.println(
      walletAddresses
        .map {
          case (v, i) => s"$v,$i,0"
        }
        .mkString("\n")
    )
    pw.close()
  }

  it should "throw exception when bonds file does not exist" in withGenResources {
    (
        executionEngineService: ExecutionEngineService[Task],
        genesisPath: Path,
        log: LogStub[Task],
        time: LogicalTime[Task]
    ) =>
      for {
        r <- fromBondsFile(nonExistentPath)(
              executionEngineService,
              log,
              time
            ).attempt
      } yield {
        log.errors.count(
          _.contains(s"Specified bonds file ${nonExistentPath} does not exist.")
        ) should be(
          1
        )
        r.isLeft shouldBe true
        r.left.get shouldBe an[IllegalArgumentException]
      }
  }

  it should "throw exception when bonds file cannot be parsed" in withGenResources {
    (
        executionEngineService: ExecutionEngineService[Task],
        genesisPath: Path,
        log: LogStub[Task],
        time: LogicalTime[Task]
    ) =>
      val badBondsFile = genesisPath.resolve("misformatted.txt")

      val pw = new PrintWriter(badBondsFile.toString)
      pw.println("xzy 1\nabc 123 7")
      pw.close()

      for {
        r <- fromBondsFile(badBondsFile)(
              executionEngineService,
              log,
              time
            ).attempt
      } yield {
        log.errors.count(
          _.contains(s"Bonds file ${badBondsFile} cannot be parsed.")
        ) should be(
          1
        )
        r.isLeft shouldBe true
        r.left.get shouldBe an[IllegalArgumentException]
      }
  }

  it should "create a genesis block with the right bonds when a proper bonds file is given" in withGenResources {
    (
        executionEngineService: ExecutionEngineService[Task],
        genesisPath: Path,
        log: LogStub[Task],
        time: LogicalTime[Task]
    ) =>
      val bondsFile = genesisPath.resolve("givenBonds.txt")
      printBonds(bondsFile.toString)

      for {
        genesisWithTransform <- fromBondsFile(bondsFile)(
                                 executionEngineService,
                                 log,
                                 time
                               )
        BlockMsgWithTransform(Some(genesis), _) = genesisWithTransform
        bonds                                   = ProtoUtil.bonds(genesis)
      } yield {
        val fromGenesis =
          bonds.map(
            b => (Base64.getEncoder().encodeToString(b.validatorPublicKey.toByteArray()), b.stake)
          )
        fromGenesis should contain theSameElementsAs validators
      }
  }

  it should "create a valid genesis block" in withStorage {
    implicit blockStore => implicit blockDagStorage =>
      Task.delay(
        withGenResources {
          (
              executionEngineService: ExecutionEngineService[Task],
              genesisPath: Path,
              log: LogStub[Task],
              time: LogicalTime[Task]
          ) =>
            val bondsFile = genesisPath.resolve("bonds.txt")
            printBonds(bondsFile.toString)
            implicit val logEff                    = log
            implicit val executionEngineServiceEff = executionEngineService
            for {
              genesisWithTransform <- fromBondsFile(bondsFile)(
                                       executionEngineService,
                                       log,
                                       time
                                     )
              BlockMsgWithTransform(Some(genesis), transforms) = genesisWithTransform
              _ <- BlockStore[Task]
                    .put(genesis.blockHash, genesis, transforms)
              dag <- blockDagStorage.getRepresentation
              maybePostGenesisStateHash <- ExecutionEngineServiceStub
                                            .validateBlockCheckpoint[Task](
                                              genesis,
                                              dag
                                            )
            } yield maybePostGenesisStateHash shouldBe 'right
        }
      )
  }
}

object GenesisTest {
  val nonExistentPath   = Paths.get("/a/b/c/d/e/f/g")
  val storageSize       = 1024L * 1024
  def mkStoragePath     = Files.createTempDirectory(s"casper-genesis-test-runtime")
  def mkGenesisPath     = Files.createTempDirectory(s"casper-genesis-test")
  val numValidators     = 5
  val casperlabsChainId = "casperlabs"

  def fromBondsFile(bondsPath: Path)(
      implicit executionEngineService: ExecutionEngineService[Task],
      log: LogStub[Task],
      time: LogicalTime[Task]
  ): Task[BlockMsgWithTransform] =
    for {
      bonds <- Genesis.getBonds[Task](bondsPath, numValidators)
      _     <- ExecutionEngineService[Task].setBonds(bonds)
      genesis <- Genesis[Task](
                  walletsPath = nonExistentPath,
                  minimumBond = 1L,
                  maximumBond = Long.MaxValue,
                  faucet = false,
                  chainId = casperlabsChainId,
                  deployTimestamp = Some(System.currentTimeMillis)
                )
    } yield genesis

  def withGenResources(
      body: (ExecutionEngineService[Task], Path, LogStub[Task], LogicalTime[Task]) => Task[Unit]
  ): Unit = {
    val storagePath             = mkStoragePath
    val genesisPath             = mkGenesisPath
    val casperSmartContractsApi = HashSetCasperTestNode.simpleEEApi[Task](Map.empty)
    val log                     = new LogStub[Task]
    val time                    = new LogicalTime[Task]

    val task = for {
      result <- body(casperSmartContractsApi, genesisPath, log, time)
      _      <- Sync[Task].delay { storagePath.recursivelyDelete() }
      _      <- Sync[Task].delay { genesisPath.recursivelyDelete() }
    } yield result
    import monix.execution.Scheduler.Implicits.global
    import monix.execution.schedulers.CanBlock.permit
    import scala.concurrent.duration._
    task.runSyncUnsafe(15.seconds)
  }
}
