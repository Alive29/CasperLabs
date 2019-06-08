package io.casperlabs.node.api.graphql

import cats.effect._
import cats.implicits._
import io.casperlabs.casper.Estimator.BlockHash
import io.casperlabs.casper.FinalizationHandler
import fs2._
import fs2.concurrent._

trait FinalizedBlocksStream[F[_]] extends FinalizationHandler[F] {
  def subscribe: Stream[F, BlockHash]
}

object FinalizedBlocksStream {

  def apply[F[_]](implicit FBS: FinalizedBlocksStream[F]): FinalizedBlocksStream[F] = FBS

  def of[F[_]: Concurrent]: F[FinalizedBlocksStream[F]] =
    for {
      // TODO: Can lead to consensus performance problems if readers are slow
      // In the future needs to be reworked to unbounded underlying queue
      // together with the GraphQL ability to manage requests intensity
      // https://casperlabs.atlassian.net/browse/NODE-549
      q         <- Topic[F, Option[BlockHash]](none[BlockHash])
      maxQueued = 100
    } yield {
      new FinalizedBlocksStream[F] {
        override def subscribe: Stream[F, BlockHash] =
          q.subscribe(maxQueued).flatMap(o => Stream.fromIterator(o.iterator))

        override def newFinalizedBlock(blockHash: BlockHash): F[Unit] = q.publish1(blockHash.some)
      }
    }
}
