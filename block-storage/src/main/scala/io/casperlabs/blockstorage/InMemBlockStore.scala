package io.casperlabs.blockstorage

import cats._
import cats.effect.Sync
import cats.effect.concurrent.Ref
import cats.implicits._
import io.casperlabs.blockstorage.BlockStore.{BlockHash, MeteredBlockStore}
import io.casperlabs.casper.protocol.ApprovedBlock
import io.casperlabs.ipc.TransformEntry
import io.casperlabs.metrics.Metrics
import io.casperlabs.metrics.Metrics.Source
import io.casperlabs.storage.{ApprovedBlockWithTransforms, BlockMsgWithTransform}

import scala.language.higherKinds

class InMemBlockStore[F[_]] private (
    implicit
    monadF: Monad[F],
    refF: Ref[F, Map[BlockHash, BlockMsgWithTransform]],
    approvedBlockRef: Ref[F, Option[ApprovedBlockWithTransforms]]
) extends BlockStore[F] {

  def get(blockHash: BlockHash): F[Option[BlockMsgWithTransform]] =
    refF.get.map(_.get(blockHash))

  override def find(p: BlockHash => Boolean): F[Seq[(BlockHash, BlockMsgWithTransform)]] =
    refF.get.map(_.filterKeys(p).toSeq)

  def put(f: => (BlockHash, BlockMsgWithTransform)): F[Unit] =
    refF.update(_ + f)

  def getApprovedBlockTransform(): F[Option[ApprovedBlockWithTransforms]] =
    approvedBlockRef.get

  def putApprovedBlockTransform(block: ApprovedBlock, transforms: Seq[TransformEntry]): F[Unit] =
    approvedBlockRef.set(Some(ApprovedBlockWithTransforms(Some(block), transforms)))

  def checkpoint(): F[Unit] =
    ().pure[F]

  def clear(): F[Unit] =
    refF.update(_.empty)

  override def close(): F[Unit] =
    monadF.pure(())
}

object InMemBlockStore {
  def create[F[_]](
      implicit
      monadF: Monad[F],
      refF: Ref[F, Map[BlockHash, BlockMsgWithTransform]],
      approvedBlockRef: Ref[F, Option[ApprovedBlockWithTransforms]],
      metricsF: Metrics[F]
  ): BlockStore[F] =
    new InMemBlockStore[F] with MeteredBlockStore[F] {
      override implicit val m: Metrics[F] = metricsF
      override implicit val ms: Source    = Metrics.Source(BlockStorageMetricsSource, "in-mem")
      override implicit val a: Apply[F]   = monadF
    }

  def createWithId: BlockStore[Id] = {
    import io.casperlabs.catscontrib.effect.implicits._
    import io.casperlabs.metrics.Metrics.MetricsNOP
    val refId            = emptyMapRef[Id](syncId)
    val approvedBlockRef = Ref[Id].of(none[ApprovedBlockWithTransforms])

    implicit val metrics: Metrics[Id] = new MetricsNOP[Id]()(syncId)
    InMemBlockStore.create(syncId, refId, approvedBlockRef, metrics)
  }

  def emptyMapRef[F[_]](
      implicit syncEv: Sync[F]
  ): F[Ref[F, Map[BlockHash, BlockMsgWithTransform]]] =
    Ref[F].of(Map.empty[BlockHash, BlockMsgWithTransform])

}
