package io.casperlabs.blockstorage

import cats._
import cats.effect.Sync
import cats.effect.concurrent.Ref
import cats.implicits._
import io.casperlabs.blockstorage.BlockStore.BlockHash
import io.casperlabs.blockstorage.StorageError.StorageIOErr
import io.casperlabs.casper.protocol.BlockMessage
import io.casperlabs.metrics.Metrics

import scala.language.higherKinds

class InMemBlockStore[F[_]] private ()(
    implicit
    monadF: Monad[F],
    refF: Ref[F, Map[BlockHash, BlockMessage]],
    metricsF: Metrics[F]
) extends BlockStore[F] {

  def get(blockHash: BlockHash): F[Option[BlockMessage]] =
    for {
      _     <- metricsF.incrementCounter("block-store-get")
      state <- refF.get
    } yield state.get(blockHash)

  override def find(p: BlockHash => Boolean): F[Seq[(BlockHash, BlockMessage)]] =
    for {
      _     <- metricsF.incrementCounter("block-store-find")
      state <- refF.get
    } yield state.filterKeys(p(_)).toSeq

  def put(f: => (BlockHash, BlockMessage)): F[StorageIOErr[Unit]] =
    for {
      _ <- metricsF.incrementCounter("block-store-put")
      _ <- refF.update { state =>
            val (hash, message) = f
            state.updated(hash, message)
          }
    } yield Right(())

  def checkpoint(): F[Unit] =
    ().pure[F]

  def clear(): F[StorageIOErr[Unit]] =
    for {
      _ <- refF.update { _.empty }
    } yield Right(())

  override def close(): F[StorageIOErr[Unit]] = monadF.pure(Right(()))
}

object InMemBlockStore {
  def create[F[_]](
      implicit
      monadF: Monad[F],
      refF: Ref[F, Map[BlockHash, BlockMessage]],
      metricsF: Metrics[F]
  ): BlockStore[F] =
    new InMemBlockStore()

  def createWithId: BlockStore[Id] = {
    import io.casperlabs.catscontrib.effect.implicits._
    import io.casperlabs.metrics.Metrics.MetricsNOP
    val refId                         = emptyMapRef[Id](syncId)
    implicit val metrics: Metrics[Id] = new MetricsNOP[Id]()(syncId)
    InMemBlockStore.create(syncId, refId, metrics)
  }

  def emptyMapRef[F[_]](implicit syncEv: Sync[F]): F[Ref[F, Map[BlockHash, BlockMessage]]] =
    Ref[F].of(Map.empty[BlockHash, BlockMessage])

}
