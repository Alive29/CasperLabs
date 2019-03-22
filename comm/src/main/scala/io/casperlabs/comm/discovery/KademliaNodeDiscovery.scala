package io.casperlabs.comm.discovery

import scala.collection.mutable
import scala.concurrent.duration._
import cats._
import cats.implicits._
import io.casperlabs.catscontrib._
import Catscontrib._
import cats.effect.Sync
import io.casperlabs.comm._
import io.casperlabs.metrics.Metrics
import io.casperlabs.shared._

object KademliaNodeDiscovery {
  def create[F[_]: Monad: Sync: Log: Time: Metrics: KademliaRPC](
      id: NodeIdentifier,
      defaultTimeout: FiniteDuration
  )(init: Option[PeerNode]): F[KademliaNodeDiscovery[F]] =
    for {
      table <- PeerTable(id)
      knd   <- new KademliaNodeDiscovery[F](id, defaultTimeout, table).pure[F]
      _     <- init.fold(().pure[F])(p => knd.addNode(p))
    } yield knd

}

private[discovery] class KademliaNodeDiscovery[F[_]: Monad: Sync: Log: Time: Metrics: KademliaRPC](
    id: NodeIdentifier,
    timeout: FiniteDuration,
    table: PeerTable[F]
) extends NodeDiscovery[F] {
  private implicit val metricsSource: Metrics.Source =
    Metrics.Source(CommMetricsSource, "discovery.kademlia")

  // TODO inline usage
  private[discovery] def addNode(peer: PeerNode): F[Unit] =
    for {
      _     <- table.updateLastSeen(peer)
      peers <- table.peers
      _     <- Metrics[F].setGauge("peers", peers.length.toLong)
    } yield ()

  private def pingHandler(peer: PeerNode): F[Unit] =
    addNode(peer) *> Metrics[F].incrementCounter("handle.ping")

  private def lookupHandler(peer: PeerNode, id: NodeIdentifier): F[Seq[PeerNode]] =
    for {
      peers <- table.lookup(id)
      _     <- Metrics[F].incrementCounter("handle.lookup")
      _     <- addNode(peer)
    } yield peers

  def discover: F[Unit] = {

    val initRPC = KademliaRPC[F].receive(pingHandler, lookupHandler)

    val findNewAndAdd = for {
      _     <- Time[F].sleep(9.seconds)
      peers <- findMorePeers(10).map(_.toList)
      _     <- peers.traverse(addNode)
    } yield ()

    initRPC *> findNewAndAdd.forever
  }

  /**
    * Return up to `limit` candidate peers.
    *
    * Curently, this function determines the distances in the table that are
    * least populated and searches for more peers to fill those. It asks one
    * node for peers at one distance, then moves on to the next node and
    * distance. The queried nodes are not in any particular order. For now, this
    * function should be called with a relatively small `limit` parameter like
    * 10 to avoid making too many unproductive networking calls.
    */
  private def findMorePeers(limit: Int): F[Seq[PeerNode]] = {
    def find(dists: Array[Int],
             peerSet: Set[PeerNode],
             potentials: Set[PeerNode],
             i: Int): F[Seq[PeerNode]] =
      if (peerSet.nonEmpty && potentials.size < limit && i < dists.length) {
        val dist = dists(i)
        /*
         * The general idea is to ask a peer for its peers around a certain
         * distance from our own key. So, construct a key that first differs
         * from ours at bit position dist.
         */
        val target       = id.key.to[mutable.ArrayBuffer] // Our key
        val byteIndex    = dist / 8
        val differentBit = 1 << (dist % 8)
        target(byteIndex) = (target(byteIndex) ^ differentBit).toByte // A key at a distance dist from me

        for {
          results <- KademliaRPC[F]
                      .lookup(NodeIdentifier(target), peerSet.head)
          newPotentials <- Traverse[List]
                            .traverse(results.toList)(r => table.find(r.id))
                            .map { maybeNodes =>
                              val existing = maybeNodes.flatten.toSet
                              potentials ++ results
                                .filter(
                                  r =>
                                    !potentials.contains(r)
                                      && r.id.key != id.key && !existing(r))
                            }
          res <- find(dists, peerSet.tail, newPotentials, i + 1)
        } yield res
      } else {
        potentials.toSeq.pure[F]
      }

    for {
      dists <- table.sparseness()
      peers <- table.peers
      res   <- find(dists.toArray, peers.toSet, Set.empty, 0)
    } yield res
  }

  override def peers: F[Seq[PeerNode]] = table.peers
}
