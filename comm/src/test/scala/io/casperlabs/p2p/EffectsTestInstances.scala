package io.casperlabs.p2p

import scala.concurrent.duration.{FiniteDuration, MILLISECONDS}
import cats._
import cats.effect.Sync
import cats.implicits._
import io.casperlabs.comm.rp._
import io.casperlabs.catscontrib._
import io.casperlabs.comm.CommError._
import io.casperlabs.comm._
import io.casperlabs.comm.transport._
import io.casperlabs.comm.discovery._
import io.casperlabs.shared._
import io.casperlabs.comm.protocol.routing._

/** Eagerly evaluated instances to do reasoning about applied effects */
object EffectsTestInstances {

  class LogicalTime[F[_]: Sync] extends Time[F] {
    var clock: Long = 0

    def currentMillis: F[Long] = Sync[F].delay {
      this.clock = clock + 1
      clock
    }

    def nanoTime: F[Long] = Sync[F].delay {
      this.clock = clock + 1
      clock
    }

    def sleep(duration: FiniteDuration): F[Unit] = Sync[F].delay(())

    def reset(): Unit = this.clock = 0
  }

  class NodeDiscoveryStub[F[_]: Sync]() extends NodeDiscovery[F] {

    var nodes: List[PeerNode] = List.empty[PeerNode]
    def reset(): Unit =
      nodes = List.empty[PeerNode]
    def peers: F[Seq[PeerNode]] = Sync[F].delay {
      nodes
    }
    def discover: F[Unit]                                          = ???
    def handleCommunications: Protocol => F[CommunicationResponse] = ???
  }

  def createRPConfAsk[F[_]: Applicative](
      local: PeerNode,
      defaultTimeout: FiniteDuration = FiniteDuration(1, MILLISECONDS),
      clearConnections: ClearConnetionsConf = ClearConnetionsConf(1, 1)
  ) =
    new ConstApplicativeAsk[F, RPConf](
      RPConf(local, Some(local), defaultTimeout, clearConnections)
    )

  class TransportLayerStub[F[_]: Sync] extends TransportLayer[F] {
    case class Request(peer: PeerNode, msg: Protocol)
    type Responses = PeerNode => Protocol => CommErr[Protocol]
    var reqresp: Option[Responses]  = None
    var requests: List[Request]     = List.empty[Request]
    var disconnects: List[PeerNode] = List.empty[PeerNode]

    def setResponses(responses: Responses): Unit =
      reqresp = Some(responses)

    def reset(): Unit = {
      reqresp = None
      requests = List.empty[Request]
      disconnects = List.empty[PeerNode]
    }

    def roundTrip(peer: PeerNode, msg: Protocol, timeout: FiniteDuration): F[CommErr[Protocol]] =
      Sync[F].delay {
        requests = requests :+ Request(peer, msg)
        reqresp.get.apply(peer).apply(msg)
      }

    def send(peer: PeerNode, msg: Protocol): F[CommErr[Unit]] =
      Sync[F].delay {
        requests = requests :+ Request(peer, msg)
        Right(())
      }

    def broadcast(peers: Seq[PeerNode], msg: Protocol): F[Seq[CommErr[Unit]]] = Sync[F].delay {
      requests = requests ++ peers.map(peer => Request(peer, msg))
      peers.map(_ => Right(()))
    }

    def stream(peers: Seq[PeerNode], blob: Blob): F[Unit] =
      broadcast(peers, ProtocolHelper.protocol(blob.sender).withPacket(blob.packet)).void

    def receive(
        dispatch: Protocol => F[CommunicationResponse],
        handleStreamed: Blob => F[Unit]
    ): F[Unit] = ???

    def disconnect(peer: PeerNode): F[Unit] =
      Sync[F].delay {
        disconnects = disconnects :+ peer
      }

    def shutdown(msg: Protocol): F[Unit] = ???
  }

  class LogStub[F[_]: Applicative] extends Log[F] {

    var debugs: Vector[String] = Vector.empty[String]
    var infos: Vector[String]  = Vector.empty[String]
    var warns: Vector[String]  = Vector.empty[String]
    var errors: Vector[String] = Vector.empty[String]

    // To be able to reconstruct the timeline.
    var all: Vector[String] = Vector.empty[String]

    def reset(): Unit = {
      debugs = Vector.empty[String]
      infos = Vector.empty[String]
      warns = Vector.empty[String]
      errors = Vector.empty[String]
      all = Vector.empty[String]
    }
    def isTraceEnabled(implicit ev: LogSource): F[Boolean]  = false.pure[F]
    def trace(msg: String)(implicit ev: LogSource): F[Unit] = ().pure[F]
    def debug(msg: String)(implicit ev: LogSource): F[Unit] = {
      debugs = debugs :+ msg
      all = all :+ msg
      ().pure[F]
    }
    def info(msg: String)(implicit ev: LogSource): F[Unit] = {
      infos = infos :+ msg
      all = all :+ msg
      ().pure[F]
    }
    def warn(msg: String)(implicit ev: LogSource): F[Unit] = {
      warns = warns :+ msg
      all = all :+ msg
      ().pure[F]
    }
    def error(msg: String)(implicit ev: LogSource): F[Unit] = {
      errors = errors :+ msg
      all = all :+ msg
      ().pure[F]
    }
    def error(msg: String, cause: scala.Throwable)(implicit ev: LogSource): F[Unit] = {
      errors = errors :+ msg
      all = all :+ msg
      ().pure[F]
    }
  }

}
