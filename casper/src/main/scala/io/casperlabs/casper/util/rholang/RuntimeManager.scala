package io.casperlabs.casper.util.rholang

import cats.effect.Concurrent
import cats.syntax.applicative._
import cats.syntax.apply._
import cats.syntax.either._
import cats.syntax.functor._
import com.google.protobuf.ByteString
import io.casperlabs.casper.protocol._
import io.casperlabs.casper.util.rholang.RuntimeManager.StateHash
import io.casperlabs.ipc.{CommutativeEffects, ExecutionEffect, Deploy => IPCDeploy}
import io.casperlabs.models._
import io.casperlabs.shared.{Log, LogSource}
import io.casperlabs.smartcontracts.ExecutionEngineService

class RuntimeManager[F[_]: Concurrent] private (
    val executionEngineService: ExecutionEngineService[F],
    val emptyStateHash: StateHash
) {
  // TODO: This function should return more information than just StateHash.
  // We need also check whether the cost of execution is the same as one published
  // by original validator and whether result of running each deploy is the same (failure vs success)
  def replayComputeState(
      hash: StateHash,
      terms: Seq[InternalProcessedDeploy],
      time: Option[Long] = None
  ): F[Either[(Option[Deploy], Failed), StateHash]] =
    hash.asRight[(Option[Deploy], Failed)].pure

  def computeState(
      hash: StateHash,
      terms: Seq[(Deploy, ExecutionEffect)],
      time: Option[Long] = None
  )(implicit log: Log[F]): F[(StateHash, Seq[InternalProcessedDeploy])] = {
    // todo using maximum commutative rules
    val commutativeEffects = CommutativeEffects(terms.flatMap(_._2.transformMap))

    log.debug("FIXME: Implement cost accounting!") *>
      executionEngineService
        .executeEffects(commutativeEffects)
        .map {
          case Right(_) =>
            (hash, terms.map {
              case (deploy, _) =>
                InternalProcessedDeploy(deploy, 0, Succeeded)
            })
          case Left(err) =>
            (hash, terms.map {
              case (deploy, _) =>
                InternalProcessedDeploy(deploy, 0, DeployResult.fromErrors(err))
            })
        }
  }

  // todo this should be complemented
  def computeBonds(hash: StateHash)(implicit log: Log[F]): F[Seq[Bond]] =
    // FIXME: Implement bonds!
    testBonds.pure[F]

  private var testBonds = Seq.empty[Bond]

  // By allowing to inject bonds we can enable some tests that only fail because
  // they reject blocks due to having no bonds to validate with.
  def withTestBonds(bonds: Seq[Bond]): this.type = {
    testBonds = bonds
    this
  }
  def withTestBonds(bonds: Map[Array[Byte], Long]): this.type = withTestBonds {
    bonds.toSeq.map {
      case (validator, stake) =>
        Bond(ByteString.copyFrom(validator), stake)
    }
  }

  def sendDeploy(d: IPCDeploy): F[Either[Throwable, ExecutionEffect]] =
    executionEngineService.sendDeploy(d)
}

object RuntimeManager {
  type StateHash = ByteString

  def fromExecutionEngineService[F[_]: Concurrent](
      executionEngineService: ExecutionEngineService[F]
  ): RuntimeManager[F] =
    //TODO define 'emptyStateHash'
    new RuntimeManager(executionEngineService, ByteString.EMPTY)
}
