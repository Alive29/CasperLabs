package io.casperlabs.casper.genesis

import java.io.PrintWriter
import java.nio.file.{Files, Path, Paths}

import cats.Id
import cats.implicits._
import com.google.protobuf.ByteString
import io.casperlabs.blockstorage.BlockStore
import io.casperlabs.catscontrib.TaskContrib._
import io.casperlabs.casper.helper.BlockDagStorageFixture
import io.casperlabs.casper.protocol.{BlockMessage, Bond}
import io.casperlabs.casper.util.ProtoUtil
import io.casperlabs.casper.util.rholang.{InterpreterUtil, RuntimeManager}
import io.casperlabs.catscontrib._
import io.casperlabs.crypto.codec.Base16
import io.casperlabs.p2p.EffectsTestInstances.{LogStub, LogicalTime}
import io.casperlabs.shared.PathOps.RichPath
import monix.execution.Scheduler.Implicits.global
import org.scalatest.{BeforeAndAfterEach, FlatSpec, Matchers}
import io.casperlabs.shared.StoreType
import io.casperlabs.smartcontracts.{ExecutionEngineService, GrpcExecutionEngineService}
import cats.effect.Sync
import monix.eval.Task
import io.casperlabs.shared.TestOutlaws._

class GenesisTest extends FlatSpec with Matchers with BlockDagStorageFixture {
  import GenesisTest._
  implicit val absId = ToAbstractContext.idToAbstractContext

  val validators = Seq(
    "299670c52849f1aa82e8dfe5be872c16b600bf09cc8983e04b903411358f2de6",
    "6bf1b2753501d02d386789506a6d93681d2299c6edfd4455f596b97bc5725968"
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

  "Genesis.fromInputFiles" should "generate random validators when no bonds file is given" in withGenResources {
    (
        runtimeManager: RuntimeManager[Task],
        genesisPath: Path,
        log: LogStub[Task],
        time: LogicalTime[Task]
    ) =>
      for {
        _      <- fromInputFiles()(runtimeManager, genesisPath, log, time)
        _      = log.warns.find(_.contains("bonds")) should be(None)
        result = log.infos.count(_.contains("Created validator")) should be(numValidators)
      } yield result
  }

  it should "generate random validators, with a warning, when bonds file does not exist" in withGenResources {
    (
        runtimeManager: RuntimeManager[Task],
        genesisPath: Path,
        log: LogStub[Task],
        time: LogicalTime[Task]
    ) =>
      for {
        _ <- fromInputFiles(maybeBondsPath = Some("not/a/real/file"))(
              runtimeManager,
              genesisPath,
              log,
              time
            )
        _ = log.warns.count(
          _.contains("does not exist. Falling back on generating random validators.")
        ) should be(
          1
        )
        result = log.infos.count(_.contains("Created validator")) should be(numValidators)
      } yield result
  }

  it should "generate random validators, with a warning, when bonds file cannot be parsed" in withGenResources {
    (
        runtimeManager: RuntimeManager[Task],
        genesisPath: Path,
        log: LogStub[Task],
        time: LogicalTime[Task]
    ) =>
      val badBondsFile = genesisPath.resolve("misformatted.txt").toString

      val pw = new PrintWriter(badBondsFile)
      pw.println("xzy 1\nabc 123 7")
      pw.close()

      for {
        _ <- fromInputFiles(maybeBondsPath = Some(badBondsFile))(
              runtimeManager,
              genesisPath,
              log,
              time
            )
        _ = log.warns.count(
          _.contains("cannot be parsed. Falling back on generating random validators.")
        ) should be(
          1
        )
        result = log.infos.count(_.contains("Created validator")) should be(numValidators)
      } yield result
  }

  it should "create a genesis block with the right bonds when a proper bonds file is given" in withGenResources {
    (
        runtimeManager: RuntimeManager[Task],
        genesisPath: Path,
        log: LogStub[Task],
        time: LogicalTime[Task]
    ) =>
      val bondsFile = genesisPath.resolve("givenBonds.txt").toString
      printBonds(bondsFile)

      for {
        genesis <- fromInputFiles(maybeBondsPath = Some(bondsFile))(
                    runtimeManager,
                    genesisPath,
                    log,
                    time
                  )
        bonds = ProtoUtil.bonds(genesis)
        _     = log.infos.isEmpty should be(true)
        result = validators
          .map {
            case (v, i) => Bond(ByteString.copyFrom(Base16.decode(v)), i.toLong)
          }
          .forall(
            bonds.contains(_)
          ) should be(true)
      } yield result
  }

  it should "create a valid genesis block" in withStorage {
    implicit blockStore => implicit blockDagStorage =>
      withGenResources {
        (
            runtimeManager: RuntimeManager[Task],
            genesisPath: Path,
            log: LogStub[Task],
            time: LogicalTime[Task]
        ) =>
          implicit val logEff = log
          for {
            genesis <- fromInputFiles()(runtimeManager, genesisPath, log, time)
            _       <- BlockStore[Task].put(genesis.blockHash, genesis)
            dag     <- blockDagStorage.getRepresentation
            maybePostGenesisStateHash <- InterpreterUtil
                                          .validateBlockCheckpoint[Task](
                                            genesis,
                                            dag,
                                            runtimeManager
                                          )
          } yield maybePostGenesisStateHash should matchPattern { case Right(Some(_)) => }
      }
  }

  it should "detect an existing bonds file in the default location" in withGenResources {
    (
        runtimeManager: RuntimeManager[Task],
        genesisPath: Path,
        log: LogStub[Task],
        time: LogicalTime[Task]
    ) =>
      val bondsFile = genesisPath.resolve("bonds.txt").toString
      printBonds(bondsFile)

      for {
        genesis <- fromInputFiles()(runtimeManager, genesisPath, log, time)
        bonds   = ProtoUtil.bonds(genesis)
        _       = log.infos.length should be(1)
        result = validators
          .map {
            case (v, i) => Bond(ByteString.copyFrom(Base16.decode(v)), i.toLong)
          }
          .forall(
            bonds.contains(_)
          ) should be(true)
      } yield result
  }
}

object GenesisTest {
  val storageSize       = 1024L * 1024
  def storageLocation   = Files.createTempDirectory(s"casper-genesis-test-runtime")
  def genesisPath       = Files.createTempDirectory(s"casper-genesis-test")
  val numValidators     = 5
  val casperlabsShardId = "casperlabs"

  def fromInputFiles(
      maybeBondsPath: Option[String] = None,
      numValidators: Int = numValidators,
      maybeWalletsPath: Option[String] = None,
      minimumBond: Long = 1L,
      maximumBond: Long = Long.MaxValue,
      faucet: Boolean = false,
      shardId: String = casperlabsShardId,
      deployTimestamp: Option[Long] = Some(System.currentTimeMillis())
  )(
      implicit runtimeManager: RuntimeManager[Task],
      genesisPath: Path,
      log: LogStub[Task],
      time: LogicalTime[Task]
  ): Task[BlockMessage] =
    Genesis
      .fromInputFiles[Task](
        maybeBondsPath,
        numValidators,
        genesisPath,
        maybeWalletsPath,
        minimumBond,
        maximumBond,
        faucet,
        runtimeManager,
        shardId,
        deployTimestamp
      )

  def withRawGenResources(
      body: (ExecutionEngineService[Task], Path, LogStub[Task], LogicalTime[Task]) => Unit
  ): Unit = {
    val storePath               = storageLocation
    val casperSmartContractsApi = ExecutionEngineService.noOpApi[Task]()
    val gp                      = genesisPath
    val log                     = new LogStub[Task]
    val time                    = new LogicalTime[Task]

    for {
      result <- body(casperSmartContractsApi, genesisPath, log, time)
      _      <- runtime.close()
      _      <- Sync[Task].delay { storePath.recursivelyDelete() }
      _      <- Sync[Task].delay { gp.recursivelyDelete() }
    } yield result
  }

  def withGenResources(
      body: (RuntimeManager[Task], Path, LogStub[Task], LogicalTime[Task]) => Unit
  ): Task[Unit] =
    withRawGenResources {
      (
          executionEngineService: ExecutionEngineService[Task],
          genesisPath: Path,
          log: LogStub[Task],
          time: LogicalTime[Task]
      ) =>
        val runtimeManager = RuntimeManager.fromExecutionEngineService(executionEngineService)
        body(runtimeManager, genesisPath, log, time)
    }
}
