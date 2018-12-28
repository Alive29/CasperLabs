package io.casperlabs.casper.util.comm

import java.nio.file.{Files, Paths}

import cats.effect.Sync
import cats.implicits._
import cats.{Apply, Monad}
import com.google.protobuf.ByteString
import io.casperlabs.casper.protocol.{BlockQuery, BlocksQuery}
import io.casperlabs.casper.util.ProtoUtil
import io.casperlabs.catscontrib.Catscontrib._
import io.casperlabs.ipc.{CommutativeEffects, Deploy, ExecutionEffect}
import io.casperlabs.shared.Time

import scala.concurrent.duration._
import scala.language.higherKinds
import scala.util._

object DeployRuntime {

  def propose[F[_]: Monad: Sync: DeployService](): F[Unit] =
    gracefulExit(
      for {
        response <- DeployService[F].createBlock()
      } yield response.map(r => s"Response: $r")
    )

  def showBlock[F[_]: Monad: Sync: DeployService](hash: String): F[Unit] =
    gracefulExit(DeployService[F].showBlock(BlockQuery(hash)))

  def showBlocks[F[_]: Monad: Sync: DeployService](depth: Int): F[Unit] =
    gracefulExit(DeployService[F].showBlocks(BlocksQuery(depth)))

  def executeEffects[F[_]: Monad: Sync: ExecutionEngineService](c: CommutativeEffects): F[Unit] =
    gracefulExit(ExecutionEngineService[F].executeEffects(c).map(_ => "success".asRight[Throwable]))

  def handleDeployResult[F[_]: Monad: Sync: ExecutionEngineService](
      r: ExecutionEffect
  ): F[CommutativeEffects] =
    CommutativeEffects(r.transformMap).pure[F]

  def deployFileProgram[F[_]: Monad: Sync: ExecutionEngineService](
      purseAddress: String,
      gasLimit: Long,
      gasPrice: Long,
      nonce: Long,
      sessionsCodeFile: String,
      paymentCodeFile: String
  ): F[Unit] = {
    def readFile(filename: String) =
      Sync[F].fromTry(
        Try(ByteString.copyFrom(Files.readAllBytes(Paths.get(filename))))
      )

    gracefulExit({
      val result = for {
        d <- Apply[F]
              .map2(
                readFile(sessionsCodeFile),
                readFile(paymentCodeFile)
              ) {
                case (sessionCode, paymentCode) =>
                  //TODO: allow user to specify their public key
                  Deploy()
                    .withTimestamp(System.currentTimeMillis())
                    .withSessionCode(sessionCode)
                    .withPaymentCode(paymentCode)
                    .withAddress(ByteString.copyFromUtf8(purseAddress))
                    .withGasLimit(gasLimit)
                    .withGasPrice(gasPrice)
                    .withNonce(nonce)
                    .asRight[Throwable]
              }
        r           <- d.traverse(ExecutionEngineService[F].sendDeploy(_))
        effects     <- r.joinRight.traverse(handleDeployResult[F](_))
        finalResult <- effects.traverse(ExecutionEngineService[F].executeEffects(_))
      } yield finalResult

      result
        .map(_ => "Success".asRight[Throwable])
        .handleError(
          ex => Left(new RuntimeException(s"Couldn't make deploy, reason: ${ex.getMessage}", ex))
        )
    })
  }

  //Simulates user requests by randomly deploying things to Casper.
  def deployDemoProgram[F[_]: Monad: Sync: Time: DeployService]: F[Unit] =
    singleDeploy[F].forever

  private def singleDeploy[F[_]: Monad: Time: Sync: DeployService]: F[Unit] =
    for {
      id <- Sync[F].delay { scala.util.Random.nextInt(100) }
      d  <- ProtoUtil.basicDeployData[F](id)
      _ <- Sync[F].delay {
            println(
              s"Sending the demo deploy to Casper."
            )
          }
      response <- DeployService[F].deploy(d)
      msg      = response.fold(processError(_).getMessage, "Response: " + _)
      _        <- Sync[F].delay(println(msg))
      _        <- Time[F].sleep(4.seconds)
    } yield ()

  private def gracefulExit[F[_]: Monad: Sync, A](program: F[Either[Throwable, String]]): F[Unit] =
    for {
      result <- Sync[F].attempt(program)
      _ <- result.joinRight match {
            case Left(ex) =>
              Sync[F].delay {
                println(processError(ex).getMessage)
                System.exit(1)
              }
            case Right(msg) => Sync[F].delay(println(msg))
          }
    } yield ()

  private def processError(t: Throwable): Throwable =
    Option(t.getCause).getOrElse(t)

}
