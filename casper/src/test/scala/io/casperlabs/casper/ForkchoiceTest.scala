package io.casperlabs.casper

import com.google.protobuf.ByteString
import io.casperlabs.casper.protocol.{BlockMessage, Bond}
import org.scalatest.{FlatSpec, Matchers}
import io.casperlabs.catscontrib._
import Catscontrib._
import cats._
import cats.data._
import cats.effect.Bracket
import cats.implicits._
import cats.mtl.MonadState
import cats.mtl.implicits._
import coop.rchain.casper.helper.BlockDagStorageFixture
import io.casperlabs.blockstorage.BlockStore.BlockHash
import io.casperlabs.blockstorage.BlockStore
import io.casperlabs.casper.Estimator.{BlockHash, Validator}
import io.casperlabs.casper.helper.{BlockGenerator, BlockStoreFixture, IndexedBlockDag}
import io.casperlabs.casper.helper.BlockGenerator._
import io.casperlabs.catscontrib.TaskContrib._
import io.casperlabs.p2p.EffectsTestInstances.LogicalTime
import io.casperlabs.shared.Time
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global

import scala.collection.immutable.HashMap

class ForkchoiceTest
    extends FlatSpec
    with Matchers
    with BlockGenerator
    with BlockStoreFixture
    with BlockDagStorageFixture {
  "Estimator on empty latestMessages" should "return the genesis regardless of DAG" in withStore {
    implicit blockStore =>
      withIndexedBlockDagStorage { implicit blockDagStorage =>
        val v1      = ByteString.copyFromUtf8("Validator One")
        val v2      = ByteString.copyFromUtf8("Validator Two")
        val v1Bond  = Bond(v1, 2)
        val v2Bond  = Bond(v2, 3)
        val bonds   = Seq(v1Bond, v2Bond)
        val genesis = createBlock[Id](Seq(), ByteString.EMPTY, bonds)
        val b2 = createBlock[Id](
          Seq(genesis.blockHash),
          v2,
          bonds,
          HashMap(v1 -> genesis.blockHash, v2 -> genesis.blockHash)
        )
        val b3 = createBlock[Id](
          Seq(genesis.blockHash),
          v1,
          bonds,
          HashMap(v1 -> genesis.blockHash, v2 -> genesis.blockHash)
        )
        val b4 = createBlock[Id](
          Seq(b2.blockHash),
          v2,
          bonds,
          HashMap(v1 -> genesis.blockHash, v2 -> b2.blockHash)
        )
        val b5 = createBlock[Id](
          Seq(b2.blockHash),
          v1,
          bonds,
          HashMap(v1 -> b3.blockHash, v2 -> b2.blockHash)
        )
        val b6 = createBlock[Id](
          Seq(b4.blockHash),
          v2,
          bonds,
          HashMap(v1 -> b5.blockHash, v2 -> b4.blockHash)
        )
        val b7 = createBlock[Id](
          Seq(b4.blockHash),
          v1,
          bonds,
          HashMap(v1 -> b5.blockHash, v2 -> b4.blockHash)
        )
        val b8 = createBlock[Id](
          Seq(b7.blockHash),
          v1,
          bonds,
          HashMap(v1 -> b7.blockHash, v2 -> b4.blockHash)
        )

        val dag = blockDagStorage.getRepresentation
        val forkchoice = Estimator.tips[Id](
          dag,
          genesis.blockHash,
          Map.empty[Validator, BlockHash]
        )
        forkchoice.head should be(genesis)
      }
  }

  // See https://docs.google.com/presentation/d/1znz01SF1ljriPzbMoFV0J127ryPglUYLFyhvsb-ftQk/edit?usp=sharing slide 29 for diagram
  "Estimator on Simple DAG" should "return the appropriate score map and forkchoice" in withStore {
    implicit blockStore =>
      withIndexedBlockDagStorage { implicit blockDagStorage =>
        val v1      = ByteString.copyFromUtf8("Validator One")
        val v2      = ByteString.copyFromUtf8("Validator Two")
        val v1Bond  = Bond(v1, 2)
        val v2Bond  = Bond(v2, 3)
        val bonds   = Seq(v1Bond, v2Bond)
        val genesis = createBlock[Id](Seq(), ByteString.EMPTY, bonds)
        val b2 = createBlock[Id](
          Seq(genesis.blockHash),
          v2,
          bonds,
          HashMap(v1 -> genesis.blockHash, v2 -> genesis.blockHash)
        )
        val b3 = createBlock[Id](
          Seq(genesis.blockHash),
          v1,
          bonds,
          HashMap(v1 -> genesis.blockHash, v2 -> genesis.blockHash)
        )
        val b4 = createBlock[Id](
          Seq(b2.blockHash),
          v2,
          bonds,
          HashMap(v1 -> genesis.blockHash, v2 -> b2.blockHash)
        )
        val b5 = createBlock[Id](
          Seq(b2.blockHash),
          v1,
          bonds,
          HashMap(v1 -> b3.blockHash, v2 -> b2.blockHash)
        )
        val b6 = createBlock[Id](
          Seq(b4.blockHash),
          v2,
          bonds,
          HashMap(v1 -> b5.blockHash, v2 -> b4.blockHash)
        )
        val b7 = createBlock[Id](
          Seq(b4.blockHash),
          v1,
          bonds,
          HashMap(v1 -> b5.blockHash, v2 -> b4.blockHash)
        )
        val b8 = createBlock[Id](
          Seq(b7.blockHash),
          v1,
          bonds,
          HashMap(v1 -> b7.blockHash, v2 -> b4.blockHash)
        )

        val dag = blockDagStorage.getRepresentation

        val latestBlocks = HashMap[Validator, BlockHash](
          v1 -> b8.blockHash,
          v2 -> b6.blockHash
        )

        val forkchoice = Estimator.tips[Id](
          dag,
          genesis.blockHash,
          latestBlocks
        )
        forkchoice.head should be(b6)
        forkchoice(1) should be(b8)
      }
  }

  // See [[/docs/casper/images/no_finalizable_block_mistake_with_no_disagreement_check.png]]
  "Estimator on flipping forkchoice DAG" should "return the appropriate score map and forkchoice" in withStore {
    implicit blockStore =>
      withIndexedBlockDagStorage { implicit blockDagStorage =>
        val v1      = ByteString.copyFromUtf8("Validator One")
        val v2      = ByteString.copyFromUtf8("Validator Two")
        val v3      = ByteString.copyFromUtf8("Validator Three")
        val v1Bond  = Bond(v1, 25)
        val v2Bond  = Bond(v2, 20)
        val v3Bond  = Bond(v3, 15)
        val bonds   = Seq(v1Bond, v2Bond, v3Bond)
        val genesis = createBlock[Id](Seq(), ByteString.EMPTY, bonds)
        val b2 = createBlock[Id](
          Seq(genesis.blockHash),
          v2,
          bonds,
          HashMap(v1 -> genesis.blockHash, v2 -> genesis.blockHash, v3 -> genesis.blockHash)
        )
        val b3 = createBlock[Id](
          Seq(genesis.blockHash),
          v1,
          bonds,
          HashMap(v1 -> genesis.blockHash, v2 -> genesis.blockHash, v3 -> genesis.blockHash)
        )
        val b4 = createBlock[Id](
          Seq(b2.blockHash),
          v3,
          bonds,
          HashMap(v1 -> genesis.blockHash, v2 -> b2.blockHash, v3 -> b2.blockHash)
        )
        val b5 = createBlock[Id](
          Seq(b3.blockHash),
          v2,
          bonds,
          HashMap(v1 -> b3.blockHash, v2 -> b2.blockHash, v3 -> genesis.blockHash)
        )
        val b6 = createBlock[Id](
          Seq(b4.blockHash),
          v1,
          bonds,
          HashMap(v1 -> b3.blockHash, v2 -> b2.blockHash, v3 -> b4.blockHash)
        )
        val b7 = createBlock[Id](
          Seq(b5.blockHash),
          v3,
          bonds,
          HashMap(v1 -> b3.blockHash, v2 -> b5.blockHash, v3 -> b4.blockHash)
        )
        val b8 = createBlock[Id](
          Seq(b6.blockHash),
          v2,
          bonds,
          HashMap(v1 -> b6.blockHash, v2 -> b5.blockHash, v3 -> b4.blockHash)
        )

        val dag = blockDagStorage.getRepresentation

        val latestBlocks =
          HashMap[Validator, BlockHash](
            v1 -> b6.blockHash,
            v2 -> b8.blockHash,
            v3 -> b7.blockHash
          )

        val forkchoice = Estimator.tips[Id](
          dag,
          genesis.blockHash,
          latestBlocks
        )
        forkchoice.head should be(b8)
        forkchoice(1) should be(b7)
      }
  }
}
