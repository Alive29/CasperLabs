package io.casperlabs.client
import cats.effect.Sync
import cats.syntax.functor._
import cats.syntax.flatMap._
import com.google.protobuf.ByteString
import io.casperlabs.crypto.codec.Base16
import io.casperlabs.client.configuration._
import io.casperlabs.ipc
import io.casperlabs.casper.protocol
import io.casperlabs.shared.{Log, LogSource, UncaughtExceptionLogger}
import io.casperlabs.catscontrib.ToAbstractContext
import io.casperlabs.smartcontracts.GrpcExecutionEngineService
import monix.eval.Task
import monix.execution.Scheduler

object Main {

  implicit val logSource: LogSource = LogSource(this.getClass)
  implicit val log: Log[Task]       = Log.log

  def main(args: Array[String]): Unit = {
    implicit val scheduler: Scheduler = Scheduler.computation(
      Math.max(java.lang.Runtime.getRuntime.availableProcessors(), 2),
      "node-runner",
      reporter = UncaughtExceptionLogger
    )

    implicit val tac = new ToAbstractContext[Task] {
      def fromTask[A](fa: Task[A]): Task[A] = fa
    }
    val exec =
      for {
        maybeConf <- Task(Configuration.parse(args))
        _ <- maybeConf.fold(Log[Task].error("Couldn't parse CLI args into configuration")) { conf =>
              implicit val deployService = new GrpcDeployService(
                conf.host,
                conf.port
              )
              program(conf)(Sync[Task], deployService, tac)
                .doOnFinish(_ => Task(deployService.close()))
            }
      } yield ()

    exec.runSyncUnsafe()
  }

  def program[F[_]: Sync: DeployService: ToAbstractContext](configuration: Configuration): F[Unit] =
    configuration match {
      case ShowBlock(_, _, hash)   => DeployRuntime.showBlock(hash)
      case ShowBlocks(_, _, depth) => DeployRuntime.showBlocks(depth)
      case Deploy(_, _, from, gasLimit, gasPrice, nonce, sessionCode, paymentCode) =>
        DeployRuntime.deployFileProgram(from, gasLimit, gasPrice, nonce, sessionCode, paymentCode)
      case _: Propose =>
        DeployRuntime.propose()
      case VisualizeDag(_, _, depth, showJustificationLines) =>
        DeployRuntime.visualizeDag(depth, showJustificationLines)

      case Query(_, _, socket, hash, keyType, keyValue, path) =>
        DeployService[F].showBlock(protocol.BlockQuery(hash)).flatMap {
          case err @ Left(_) =>
            DeployRuntime.gracefulExit(Sync[F].pure[Either[Throwable, String]](err))
          case Right(blockDesc) =>
            //TODO: should be able to get the result directly instead of parsing output
            val state = blockDesc
              .split("\n")
              .map(_.trim)
              .find(_.startsWith("tupleSpaceHash"))
              .get
              .split("\"")(1)

            val socketP   = java.nio.file.Paths.get(socket)
            val ee        = new GrpcExecutionEngineService(socketP, 4 * 1024 * 1024)
            val stateHash = ByteString.copyFrom(Base16.decode(state))
            val keyBytes  = ByteString.copyFrom(Base16.decode(keyValue))
            val key = keyType.toLowerCase match {
              case "hash" =>
                ipc.Key(ipc.Key.KeyInstance.Hash(ipc.KeyHash(keyBytes)))
              case "uref" =>
                ipc.Key(ipc.Key.KeyInstance.Uref(ipc.KeyURef(keyBytes)))
              case "address" =>
                ipc.Key(ipc.Key.KeyInstance.Account(ipc.KeyAddress(keyBytes)))
            }
            val f = for {
              result   <- ee.query(stateHash, key, path)
              response = result.map(_.toProtoString)
              _        <- ee.close()
            } yield response
            DeployRuntime.gracefulExit(f)
        }
    }
}
