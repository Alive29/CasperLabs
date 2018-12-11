package coop.rchain.casper.util.comm

import cats.Monad
import cats.effect.Sync
import cats.implicits._
import com.google.protobuf.ByteString
import coop.rchain.casper.protocol._
import coop.rchain.casper.util.ProtoUtil
import coop.rchain.catscontrib.Catscontrib._
import coop.rchain.shared.Time

import scala.concurrent.duration._
import scala.io.Source
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

  //Accepts a Rholang source file and deploys it to Casper
  def deployFileProgram[F[_]: Monad: Sync: DeployService](
      purseAddress: String,
      phloLimit: Long,
      phloPrice: Long,
      nonce: Int,
      file: String
  ): F[Unit] =
    gracefulExit(
      Sync[F].delay(Try(Source.fromFile(file).mkString).toEither).flatMap {
        case Left(ex) =>
          Sync[F].delay(Left(new RuntimeException(s"Error with given file: \n${ex.getMessage}")))
        case Right(code) =>
          for {
            timestamp <- Sync[F].delay(System.currentTimeMillis())
            //TODO: allow user to specify their public key
            d = DeployData()
              .withTimestamp(timestamp)
              .withSessionCode(ByteString.copyFromUtf8(file))
              .withFrom(purseAddress)
              .withPhloLimit(phloLimit)
              .withPhloPrice(phloPrice)
              .withNonce(nonce)
            response <- DeployService[F].deploy(d)
          } yield response.map(r => s"Response: $r")
      }
    )

  //Simulates user requests by randomly deploying things to Casper.
  def deployDemoProgram[F[_]: Monad: Sync: Time: DeployService]: F[Unit] =
    singleDeploy[F].forever

  private def singleDeploy[F[_]: Monad: Time: Sync: DeployService]: F[Unit] =
    for {
      id <- Sync[F].delay { scala.util.Random.nextInt(100) }
      d  <- ProtoUtil.basicDeployData[F](id)
      _ <- Sync[F].delay {
            println(s"Sending the following to Casper: ${d.sessionCode}")
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
