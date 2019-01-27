package io.casperlabs.casper.util.rholang

import cats.Monad
import cats.implicits._
import com.google.protobuf.ByteString
import io.casperlabs.blockstorage.{BlockDagRepresentation, BlockMetadata, BlockStore}
import io.casperlabs.casper.protocol._
import io.casperlabs.casper.util.rholang.RuntimeManager.StateHash
import io.casperlabs.casper.util.{DagOperations, ProtoUtil}
import io.casperlabs.casper.{BlockException, PrettyPrinter}
import io.casperlabs.catscontrib.ToAbstractContext
import io.casperlabs.crypto.codec.Base16
import io.casperlabs.ipc.ExecutionEffect
import io.casperlabs.models._
import io.casperlabs.shared.{Log, LogSource}
import io.casperlabs.smartcontracts.ExecutionEngineService
import monix.eval.Task
import monix.execution.Scheduler

import scala.concurrent.duration.Duration

object InterpreterUtil {

  private implicit val logSource: LogSource = LogSource(this.getClass)

  //Returns (None, checkpoints) if the block's tuplespace hash
  //does not match the computed hash based on the deploys
  def validateBlockCheckpoint[F[_]: Monad: Log: BlockStore: ToAbstractContext](
      b: BlockMessage,
      dag: BlockDagRepresentation[F],
      runtimeManager: RuntimeManager[Task]
  )(
      implicit scheduler: Scheduler
  ): F[Either[BlockException, Option[StateHash]]] = {
    val preStateHash    = ProtoUtil.preStateHash(b)
    val tsHash          = ProtoUtil.tuplespace(b)
    val deploys         = ProtoUtil.deploys(b)
    val internalDeploys = deploys.flatMap(ProcessedDeployUtil.toInternal)
    val timestamp       = Some(b.header.get.timestamp) // TODO: Ensure header exists through type
    for {
      parents <- ProtoUtil.unsafeGetParents[F](b)
      possiblePreStateHash <- computeParentsPostState[F](
                               parents,
                               dag,
                               runtimeManager,
                               timestamp
                             )
      _ <- Log[F].info(s"Computed parents post state for ${PrettyPrinter.buildString(b)}.")
      result <- processPossiblePreStateHash[F](
                 runtimeManager,
                 preStateHash,
                 tsHash,
                 internalDeploys,
                 possiblePreStateHash,
                 timestamp
               )
    } yield result
  }

  private def processPossiblePreStateHash[F[_]: Monad: Log: BlockStore: ToAbstractContext](
      runtimeManager: RuntimeManager[Task],
      preStateHash: StateHash,
      tsHash: Option[StateHash],
      internalDeploys: Seq[InternalProcessedDeploy],
      possiblePreStateHash: Either[Throwable, StateHash],
      time: Option[Long]
  )(implicit scheduler: Scheduler): F[Either[BlockException, Option[StateHash]]] =
    possiblePreStateHash match {
      case Left(ex) =>
        Left(BlockException(ex)).rightCast[Option[StateHash]].pure[F]
      case Right(computedPreStateHash) =>
        if (preStateHash == computedPreStateHash) {
          processPreStateHash[F](
            runtimeManager,
            preStateHash,
            tsHash,
            internalDeploys,
            possiblePreStateHash,
            time
          )
        } else {
          Log[F].warn(
            s"Computed pre-state hash ${PrettyPrinter.buildString(computedPreStateHash)} does not equal block's pre-state hash ${PrettyPrinter
              .buildString(preStateHash)}"
          ) *> Right(none[StateHash]).leftCast[BlockException].pure[F]
        }
    }

  private def processPreStateHash[F[_]: Monad: Log: BlockStore: ToAbstractContext](
      runtimeManager: RuntimeManager[Task],
      preStateHash: StateHash,
      tsHash: Option[StateHash],
      internalDeploys: Seq[InternalProcessedDeploy],
      possiblePreStateHash: Either[Throwable, StateHash],
      time: Option[Long]
  )(implicit scheduler: Scheduler): F[Either[BlockException, Option[StateHash]]] =
    ToAbstractContext[F]
      .fromTask(
        runtimeManager
          .replayComputeState(preStateHash, internalDeploys, time)
      )
      .flatMap {
        case Left((Some(deploy), status)) =>
          status match {
            case InternalErrors(exs) =>
              Left(
                BlockException(
                  new Exception(s"Internal errors encountered while processing ${PrettyPrinter
                    .buildString(deploy)}: ${exs.mkString("\n")}")
                )
              ).rightCast[Option[StateHash]].pure[F]
            case UserErrors(errors: Vector[Throwable]) =>
              Log[F].warn(s"Found user error(s) ${errors.map(_.getMessage).mkString("\n")}") *> Right(
                none[StateHash]
              ).leftCast[BlockException].pure[F]
            case UnknownFailure =>
              Log[F].warn(s"Found unknown failure") *> Right(none[StateHash])
                .leftCast[BlockException]
                .pure[F]
          }
        case Left((None, _)) =>
          //TODO Log error
          ???
        case Right(computedStateHash) =>
          if (tsHash.contains(computedStateHash)) {
            // state hash in block matches computed hash!
            Right(Option(computedStateHash))
              .leftCast[BlockException]
              .pure[F]
          } else {
            // state hash in block does not match computed hash -- invalid!
            // return no state hash, do not update the state hash set
            Log[F].warn(
              s"Tuplespace hash ${tsHash.getOrElse(ByteString.EMPTY)} does not match computed hash $computedStateHash."
            ) *> Right(none[StateHash]).leftCast[BlockException].pure[F]
          }
      }

  def computeDeploysCheckpoint[F[_]: Monad: BlockStore: ToAbstractContext](
      parents: Seq[BlockMessage],
      deploysWithEffect: Seq[(Deploy, ExecutionEffect)],
      dag: BlockDagRepresentation[F],
      runtimeManager: RuntimeManager[Task],
      time: Option[Long] = None
  )(
      implicit scheduler: Scheduler
  ): F[Either[Throwable, (StateHash, StateHash, Seq[InternalProcessedDeploy])]] =
    for {
      possiblePreStateHash <- computeParentsPostState[F](parents, dag, runtimeManager, time)
      res <- possiblePreStateHash match {
              case Right(preStateHash) =>
                ToAbstractContext[F]
                  .fromTask(
                    runtimeManager
                      .computeState(preStateHash, deploysWithEffect, time)
                  )
                  .map {
                    case (postStateHash, processedDeploy) =>
                      Right(preStateHash, postStateHash, processedDeploy)
                  }
              case Left(err) =>
                Left(err).pure[F]
            }
    } yield res

  private def computeParentsPostState[F[_]: Monad: BlockStore](
      parents: Seq[BlockMessage],
      dag: BlockDagRepresentation[F],
      runtimeManager: RuntimeManager[Task],
      time: Option[Long]
  )(implicit scheduler: Scheduler): F[Either[Throwable, StateHash]] = {
    val parentTuplespaces = parents.flatMap(p => ProtoUtil.tuplespace(p).map(p -> _))

    parentTuplespaces match {
      //no parents to base off of, so use default
      case Seq() =>
        Right(runtimeManager.emptyStateHash).leftCast[Throwable].pure[F]

      //For a single parent we look up its checkpoint
      case Seq((_, parentStateHash)) =>
        Right(parentStateHash).leftCast[Throwable].pure[F]

      //In the case of multiple parents we need
      //to apply all of the deploys that have been
      //made in all of the branches of the DAG being
      //merged. This is done by computing uncommon ancestors
      //and applying the deploys in those blocks.
      case (initParent, initStateHash) +: _ =>
        dag.deriveOrdering(0L).flatMap { implicit ordering: Ordering[BlockMetadata] => // TODO: Replace with an actual starting number
          for {
            parentsMetadata    <- parents.toList.traverse(b => dag.lookup(b.blockHash).map(_.get))
            indexedParents     = parentsMetadata.toVector
            uncommonAncestors  <- DagOperations.uncommonAncestors[F](indexedParents, dag)
            initParentMetadata <- dag.lookup(initParent.blockHash)
            initIndex          = indexedParents.indexOf(initParentMetadata)
            //filter out blocks that already included by starting from the chosen initParent
            blocksToApply = uncommonAncestors
              .filterNot { case (_, set) => set.contains(initIndex) }
              .keys
              .toVector
              .sorted //ensure blocks to apply is topologically sorted to maintain any causal dependencies
            maybeBlocks <- blocksToApply.traverse(b => BlockStore[F].get(b.blockHash))
            _           = assert(maybeBlocks.forall(_.isDefined))
            blocks      = maybeBlocks.flatten
            deploys     = blocks.flatMap(_.getBody.deploys.flatMap(ProcessedDeployUtil.toInternal))
          } yield
            runtimeManager
              .replayComputeState(initStateHash, deploys, time)
              .runSyncUnsafe(Duration.Inf) match {
              case result @ Right(_) => result.leftCast[Throwable]
              case Left((_, status)) =>
                val parentHashes = parents.map(p => Base16.encode(p.blockHash.toByteArray).take(8))
                Left(
                  new Exception(
                    s"Failed status while computing post state of $parentHashes: $status"
                  )
                )
            }
        }
    }
  }

  private[casper] def computeBlockCheckpointFromDeploys[F[_]: Monad: BlockStore: ExecutionEngineService: ToAbstractContext](
      b: BlockMessage,
      genesis: BlockMessage,
      dag: BlockDagRepresentation[F],
      runtimeManager: RuntimeManager[Task]
  )(
      implicit scheduler: Scheduler
  ): F[Either[Throwable, (StateHash, StateHash, Seq[InternalProcessedDeploy])]] =
    for {
      parents <- ProtoUtil.unsafeGetParents[F](b)

      deploys: Seq[Deploy] = ProtoUtil.deploys(b).flatMap(_.deploy)
      deploysEffect <- deploys.toList
                        .foldM[F, Either[Throwable, Seq[(Deploy, ExecutionEffect)]]](
                          Seq()
                            .asRight[Throwable]
                        ) {
                          case (Left(e), _) =>
                            e.asLeft[Seq[(Deploy, ExecutionEffect)]]
                              .pure[F]
                          case (Right(acc), d) =>
                            d.raw match {
                              case Some(r) =>
                                ToAbstractContext[F]
                                  .fromTask(
                                    runtimeManager
                                      .sendDeploy(
                                        ProtoUtil
                                          .deployDataToEEDeploy(r)
                                      )
                                  )
                                  .map {
                                    case Left(e) =>
                                      e.asLeft[Seq[(Deploy, ExecutionEffect)]]
                                    case Right(effect) =>
                                      (acc :+ (d, effect))
                                        .asRight[Throwable]
                                  }
                              case None =>
                                (acc :+ (d, ExecutionEffect()))
                                  .asRight[Throwable]
                                  .pure[F]
                            }
                        }
      _ = assert(
        parents.nonEmpty || (parents.isEmpty && b == genesis),
        "Received a different genesis block."
      )
      result <- deploysEffect match {
                 case Left(e) =>
                   e.asLeft[(StateHash, StateHash, Seq[InternalProcessedDeploy])].pure[F]
                 case Right(d) =>
                   computeDeploysCheckpoint[F](
                     parents,
                     d,
                     dag,
                     runtimeManager
                   )
               }
    } yield result
}
