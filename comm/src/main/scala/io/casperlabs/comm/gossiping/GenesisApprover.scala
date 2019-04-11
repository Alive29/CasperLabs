package io.casperlabs.comm.gossiping

import cats._
import cats.implicits._
import cats.effect._
import cats.effect.concurrent._
import com.google.protobuf.ByteString
import io.casperlabs.casper.consensus.{Approval, Block, BlockSummary, GenesisCandidate, Signature}
import io.casperlabs.crypto.codec.Base16
import io.casperlabs.comm.discovery.{Node, NodeDiscovery}
import io.casperlabs.comm.discovery.NodeUtils._
import io.casperlabs.comm.ServiceError
import io.casperlabs.comm.ServiceError.{Internal, InvalidArgument, NotFound, Unavailable}
import io.casperlabs.shared.Log
import scala.util.control.NonFatal
import scala.util.Random
import scala.concurrent.duration.FiniteDuration

/** Accumulate approvals for the Genesis block. When enough of them is
  * present to pass a threshold which is the preorgative of this node,
  * let the rest of the system transition to processing blocks.
  * Keep accumulating and gossiping approvals to facilitate other joiners. */
trait GenesisApprover[F[_]] {

  /** Try to get the candidate, if we already have it. */
  def getCandidate: F[Either[ServiceError, GenesisCandidate]]

  /** Try to add the approval, if we already have the candidate and it matches. If successful, relay it as well.
    * Return whether we have made the transition to processing.*/
  def addApproval(
      blockHash: ByteString,
      approval: Approval
  ): F[Either[ServiceError, Boolean]]

  /** Trigger once when the Genesis candidate has gathered enough signatures that this node
	  * can transition to processing blocks and deploys. */
  def onApproved: F[ByteString]
}

object GenesisApproverImpl {
  trait Backend[F[_]] {

    /** Check that the genesis we retrieved from the bootstrap nodes has the right content. */
    def validateCandidate(block: Block): F[Either[Throwable, Unit]]

    /** Decide if the the currently accumulated validator public keys are enough to transition to processing blocks. */
    def canTransition(block: Block, signatories: Set[ByteString]): Boolean

    // Unfortunately we can't call Validate.signature from the `comm` module.
    def validateSignature(
        blockHash: ByteString,
        publicKey: ByteString,
        signature: Signature
    ): Boolean

    def getBlock(blockHash: ByteString): F[Option[Block]]
  }

  case class Status(candidate: GenesisCandidate, block: Block)

  /** Use by non-standalone nodes while there is no DAG. */
  def fromBootstrap[F[_]: Concurrent: Log: Timer](
      backend: GenesisApproverImpl.Backend[F],
      nodeDiscovery: NodeDiscovery[F],
      connectToGossip: Node => F[GossipService[F]],
      relayFactor: Int,
      bootstrap: Node,
      pollInterval: FiniteDuration,
      downloadManager: DownloadManager[F]
  ): F[GenesisApprover[F]] =
    for {
      statusRef          <- Ref.of(none[Status])
      hasTransitionedRef <- Ref.of(false)
      onApprovedDeferred <- Deferred[F, ByteString]
      approver = new GenesisApproverImpl(
        statusRef,
        hasTransitionedRef,
        onApprovedDeferred,
        backend,
        nodeDiscovery,
        connectToGossip,
        relayFactor
      )
      _ <- Concurrent[F].start(approver.pollBootstrap(bootstrap, pollInterval, downloadManager))
    } yield approver

  /** Use in standalone mode with the pre-constructed Genesis block. */
  def fromGenesis[F[_]: Concurrent: Log: Timer](
      backend: GenesisApproverImpl.Backend[F],
      nodeDiscovery: NodeDiscovery[F],
      connectToGossip: Node => F[GossipService[F]],
      relayFactor: Int,
      genesis: Block,
      approval: Approval
  ): F[GenesisApprover[F]] =
    for {
      // Start with empty list of approvals and add it to trigger the transition if it has to be.
      statusRef          <- Ref.of(Status(GenesisCandidate(genesis.blockHash), genesis).some)
      hasTransitionedRef <- Ref.of(false)
      onApprovedDeferred <- Deferred[F, ByteString]
      approver = new GenesisApproverImpl(
        statusRef,
        hasTransitionedRef,
        onApprovedDeferred,
        backend,
        nodeDiscovery,
        connectToGossip,
        relayFactor
      )
      // Gossip, trigger as usual.
      _ <- approver.addApprovals(genesis.blockHash, List(approval))
    } yield approver
}

/** Maintain the state of the Genesis approval and handle gossiping.
  * Once instantiated start either the polling or inject it with a self-constructed Genesis. */
class GenesisApproverImpl[F[_]: Concurrent: Log: Timer](
    statusRef: Ref[F, Option[GenesisApproverImpl.Status]],
    hasTransitionedRef: Ref[F, Boolean],
    onApprovedDeferred: Deferred[F, ByteString],
    backend: GenesisApproverImpl.Backend[F],
    nodeDiscovery: NodeDiscovery[F],
    connectToGossip: Node => F[GossipService[F]],
    relayFactor: Int
) extends GenesisApprover[F] {
  import GenesisApproverImpl.Status

  val unavailable = Unavailable("The Genesis candidate is not yet available.")

  private def hex(hash: ByteString) = Base16.encode(hash.toByteArray)

  override def onApproved: F[ByteString] =
    onApprovedDeferred.get

  override def getCandidate: F[Either[ServiceError, GenesisCandidate]] =
    statusRef.get.map {
      case None         => unavailable.asLeft[GenesisCandidate]
      case Some(status) => status.candidate.asRight[ServiceError]
    }

  /** Add a new approval if it checks out and transition if possible. */
  override def addApproval(
      blockHash: ByteString,
      approval: Approval
  ): F[Either[ServiceError, Boolean]] =
    tryAddApproval(blockHash, approval) flatMap {
      case Left(ex) =>
        ex.asLeft[Boolean].pure[F]

      case Right(None) =>
        false.asRight[ServiceError].pure[F]

      case Right(Some(newStatus)) =>
        for {
          transitioned <- tryTransition(newStatus)
          _            <- Concurrent[F].start(relayApproval(blockHash, approval))
        } yield {
          Right(transitioned)
        }
    }

  /** Validate and add each approval, if possible. Return if transition has happened. */
  private def addApprovals(blockHash: ByteString, approvals: List[Approval]): F[Boolean] =
    approvals.traverse {
      addApproval(blockHash, _) flatMap {
        case Left(ex) =>
          Log[F].warn(s"Cannot use approval from bootstrap: $ex") *> false.pure[F]
        case Right(transitioned) =>
          transitioned.pure[F]
      }
    } map {
      _ contains true
    }

  /** Get the Genesis candidate from the bootstrap node and keep polling until we can do the transition. */
  private def pollBootstrap(
      bootstrap: Node,
      pollInterval: FiniteDuration,
      downloadManager: DownloadManager[F]
  ): F[Unit] = {

    def download(service: GossipService[F], blockHash: ByteString): F[Block] =
      for {
        maybeSummary <- service
                         .streamBlockSummaries(StreamBlockSummariesRequest(Seq(blockHash)))
                         .headOptionL
        summary <- maybeSummary.fold(
                    Sync[F].raiseError[BlockSummary](
                      NotFound("Cannot get Genesis summary from bootstrap.")
                    )
                  )(_.pure[F])
        watch      <- downloadManager.scheduleDownload(summary, bootstrap, relay = false)
        _          <- watch
        maybeBlock <- backend.getBlock(blockHash)
        block <- maybeBlock.fold(
                  Sync[F].raiseError[Block](Internal("Cannot retrieve downloaded block."))
                )(_.pure[F])
        _ <- Log[F].info(s"Downloaded Genesis candidate ${hex(blockHash)} from bootstrap.")
      } yield block

    def getOrAddStatus(service: GossipService[F], blockHash: ByteString): F[Status] =
      statusRef.get.flatMap {
        case None =>
          for {
            maybeBlock <- backend.getBlock(blockHash)
            block <- maybeBlock.fold(download(service, blockHash))(
                      _.pure[F]
                    )
            _ <- Sync[F].rethrow(backend.validateCandidate(block))
            // Add empty candidate so we can verify all approvals one by one.
            status = Status(GenesisCandidate(blockHash), block)
            _      <- statusRef.set(Some(status))
          } yield status

        case Some(status) =>
          status.pure[F]
      }

    def loop(prevApprovals: Set[Approval]): F[Unit] = {
      val trySync: F[(Set[Approval], Boolean)] = for {
        service      <- connectToGossip(bootstrap)
        candidate    <- service.getGenesisCandidate(GetGenesisCandidateRequest())
        status       <- getOrAddStatus(service, candidate.blockHash)
        newApprovals = candidate.approvals.toSet -- prevApprovals
        transitioned <- addApprovals(candidate.blockHash, newApprovals.toList)
      } yield (newApprovals ++ prevApprovals, transitioned)

      trySync
        .handleErrorWith {
          case NonFatal(ex) =>
            Log[F].warn(s"Failed to sync genesis candidate with bootstrap ${bootstrap.show}: $ex") *>
              (prevApprovals, false).pure[F]
        }
        .flatMap {
          case (_, true)                 => ().pure[F]
          case (checkedApprovals, false) => Timer[F].sleep(pollInterval) *> loop(checkedApprovals)
        }
    }

    loop(Set.empty)
  }

  /** Add the approval to the state if it's new and matches what we accept. Return the new state if it changed. */
  private def tryAddApproval(
      blockHash: ByteString,
      approval: Approval
  ): F[Either[ServiceError, Option[Status]]] =
    // Just doing the checks first, to avoid locking in futility.
    statusRef.get.map {
      case None =>
        Left(unavailable)

      case Some(Status(candidate, _)) if candidate.approvals contains approval =>
        Right(false)

      case Some(Status(candidate, _)) if candidate.blockHash != blockHash =>
        Left(InvalidArgument("The block hash doesn't match the candidate."))

      case Some(Status(_, block))
          if !block.getHeader.getState.bonds
            .map(_.validatorPublicKey)
            .contains(approval.validatorPublicKey) =>
        Left(InvalidArgument("The signatory is not one of the bonded validators."))

      case _
          if !backend.validateSignature(
            blockHash,
            approval.validatorPublicKey,
            approval.getSignature
          ) =>
        Left(InvalidArgument("Could not validate signature."))

      case _ =>
        // It's new.
        Right(true)
    } flatMap {
      case Left(ex) =>
        (ex: ServiceError).asLeft[Option[Status]].pure[F]

      case Right(false) =>
        none.asRight[ServiceError].pure[F]

      case Right(true) =>
        statusRef.modify {
          case Some(status @ Status(candidate, _)) if !candidate.approvals.contains(approval) =>
            val ns = status.copy(
              candidate = candidate.copy(approvals = approval +: candidate.approvals)
            )
            ns.some -> ns.some.asRight[ServiceError]

          case status =>
            status -> none.asRight[ServiceError]
        }
    }

  private def relayApproval(blockHash: ByteString, approval: Approval): F[Unit] = {
    def relayTo(peer: Node): F[Boolean] = {
      val tryRelay = for {
        service <- connectToGossip(peer)
        _       <- service.addApproval(AddApprovalRequest(blockHash).withApproval(approval))
        _       <- Log[F].debug(s"Relayed an approval for ${hex(blockHash)} to ${peer.show}")
      } yield true

      tryRelay.handleErrorWith {
        case NonFatal(ex) =>
          Log[F].warn(s"Could not relay the approval for ${hex(blockHash)} to ${peer.show}: $ex") *> false
            .pure[F]
      }
    }

    def loop(peers: List[Node], relayed: Int): F[Unit] =
      peers match {
        case peer :: peers if relayed < relayFactor =>
          relayTo(peer) flatMap { ok =>
            loop(peers, relayed + (if (ok) 1 else 0))
          }
        case _ =>
          ().pure[F]
      }

    nodeDiscovery.alivePeersAscendingDistance.flatMap { peers =>
      loop(Random.shuffle(peers), 0)
    }
  }

  /** Trigger the transition if we can. Return true if the transition has happened. */
  private def tryTransition(status: Status): F[Boolean] =
    hasTransitionedRef.get.ifM(
      true.pure[F],
      Sync[F]
        .delay {
          backend.canTransition(
            status.block,
            status.candidate.approvals.map(_.validatorPublicKey).toSet
          )
        }
        .ifM(
          (
            onApprovedDeferred.complete(status.candidate.blockHash) *>
              hasTransitionedRef.set(true) *>
              Log[F].info("Transitioned to approved genesis state.")
          ).attempt *> hasTransitionedRef.get,
          false.pure[F]
        )
    )
}
