package io.casperlabs.comm.transport

import java.nio.file._

import cats.implicits._
import io.casperlabs.comm.{PeerNode, _}
import io.casperlabs.comm.transport.PacketOps._
import io.casperlabs.shared.Log
import monix.eval.Task
import monix.execution.{Cancelable, Scheduler}
import monix.reactive.Observable
import monix.reactive.observers.Subscriber

import scala.concurrent.duration._

case class ToStream(peerNode: PeerNode, path: Path, sender: PeerNode)

class StreamObservable(bufferSize: Int, folder: Path)(implicit log: Log[Task], scheduler: Scheduler)
    extends Observable[ToStream] {

  private val subject = buffer.LimitedBufferObservable.dropNew[ToStream](bufferSize)

  def stream(peers: List[PeerNode], blob: Blob): Task[Unit] = {
    def push(peer: PeerNode): Task[Boolean] =
      blob.packet.store[Task](folder) >>= {
        case Right(file) => Task.delay(subject.pushNext(ToStream(peer, file, blob.sender)))
        case Left(UnableToStorePacket(p, er)) =>
          log.error(s"Could not serialize packet $p. Error message: $er") *> true.pure[Task]
        case Left(er) =>
          log.error(s"Could not serialize packet ${blob.packet}. Error: $er") *> true.pure[Task]
      }

    def retry(failed: List[PeerNode]): Task[Unit] =
      Task
        .defer(log.debug(s"Retrying for $failed") *> stream(failed, blob))
        .delayExecution(100.millis)

    for {
      results     <- peers.traverse(push)
      paired      = peers.zip(results)
      (_, failed) = paired.partition(_._2)
      _           <- if (failed.nonEmpty) retry(failed.map(_._1)) else Task.unit
    } yield ()
  }

  def unsafeSubscribeFn(subscriber: Subscriber[ToStream]): Cancelable = {
    val subscription = subject.subscribe(subscriber)
    () => subscription.cancel()
  }
}
