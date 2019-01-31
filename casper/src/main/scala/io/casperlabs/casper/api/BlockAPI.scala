package io.casperlabs.casper.api

import cats.{Id, Monad}
import cats.data.StateT
import cats.effect.Sync
import cats.implicits._
import cats.mtl._
import cats.mtl.implicits._
import com.google.protobuf.ByteString
import io.casperlabs.blockstorage.{BlockDagRepresentation, BlockStore}
import io.casperlabs.casper.Estimator.BlockHash
import io.casperlabs.casper.MultiParentCasperRef.MultiParentCasperRef
import io.casperlabs.casper._
import io.casperlabs.casper.protocol._
import io.casperlabs.casper.util.ProtoUtil
import io.casperlabs.casper.util.rholang.RuntimeManager
import io.casperlabs.crypto.codec.Base16
import io.casperlabs.crypto.hash.Blake2b512Random
import io.casperlabs.graphz._
import io.casperlabs.shared.{Log, SyncLock}
import monix.execution.Scheduler
import scodec.Codec

import scala.collection.immutable
import io.casperlabs.catscontrib._
import io.casperlabs.catscontrib.ski._

object BlockAPI {

  private val createBlockLock = new SyncLock

  def deploy[F[_]: Monad: MultiParentCasperRef: Log](d: DeployData): F[DeployServiceResponse] = {
    def casperDeploy(implicit casper: MultiParentCasper[F]): F[DeployServiceResponse] =
      for {
        r <- MultiParentCasper[F].deploy(d)
        re <- r match {
               case Right(_)  => DeployServiceResponse(success = true, "Success!").pure[F]
               case Left(err) => DeployServiceResponse(success = false, err.getMessage).pure[F]
             }
      } yield re

    MultiParentCasperRef
      .withCasper[F, DeployServiceResponse](
        casperDeploy(_),
        DeployServiceResponse(success = false, s"Error: Casper instance not available")
      )
  }

  def createBlock[F[_]: Sync: MultiParentCasperRef: Log]: F[DeployServiceResponse] =
    MultiParentCasperRef.withCasper[F, DeployServiceResponse](
      casper =>
        // TODO: Use Bracket: See https://github.com/rchain/rchain/pull/1436#discussion_r215520914
        Monad[F].ifM(Sync[F].delay { createBlockLock.tryLock() })(
          for {
            maybeBlock <- casper.createBlock
            result <- maybeBlock match {
                       case err: NoBlock =>
                         DeployServiceResponse(success = false, s"Error while creating block: $err")
                           .pure[F]
                       case Created(block) => casper.addBlock(block).map(addResponse(_, block))
                     }
            _ <- Sync[F].delay { createBlockLock.unlock() }
          } yield result,
          DeployServiceResponse(success = false, "Error: There is another propose in progress.")
            .pure[F]
        ),
      DeployServiceResponse(success = false, "Error: Casper instance not available")
    )

  private def getMainChainFromTip[F[_]: Monad: MultiParentCasper: Log: SafetyOracle: BlockStore](
      depth: Int
  ): F[IndexedSeq[BlockMessage]] =
    for {
      dag       <- MultiParentCasper[F].blockDag
      estimates <- MultiParentCasper[F].estimator(dag)
      tip       = estimates.head
      mainChain <- ProtoUtil.getMainChainUntilDepth[F](tip, IndexedSeq.empty[BlockMessage], depth)
    } yield mainChain

  // TOOD extract common code from show blocks
  def visualizeBlocks[F[_]: Monad: Sync: MultiParentCasperRef: Log: SafetyOracle: BlockStore](
      d: Option[Int] = None
  ): F[String] = {

    type Effect[A] = StateT[Id, StringBuffer, A]
    implicit val ser = new StringSerializer[Effect]
    case class Acc(timeseries: List[Long] = List.empty, graph: Effect[Graphz[Effect]])

    def casperResponse(implicit casper: MultiParentCasper[F]): F[String] =
      for {
        dag         <- MultiParentCasper[F].blockDag
        maxHeight   <- dag.topoSort(0L).map(_.length - 1)
        depth       = d.getOrElse(maxHeight)
        startHeight = math.max(0, maxHeight - depth)
        topoSort    <- dag.topoSortTail(depth)
        acc <- topoSort.foldM(Acc(graph = Graphz[Effect]("DAG", DiGraph, rankdir = Some(BT)))) {
                case (acc, blockHashes) =>
                  for {
                    blocks    <- blockHashes.traverse(ProtoUtil.unsafeGetBlock[F])
                    timeEntry = blocks.head.getBody.getState.blockNumber
                    maybeLvl0 = if (timeEntry != 1) None
                    else
                      Some(for {
                        g       <- Graphz.subgraph[Effect](s"lvl0", DiGraph, rank = Some(Same))
                        _       <- g.node("0")
                        genesis = blocks.head.getHeader.parentsHashList.head
                        _       <- g.node(name = PrettyPrinter.buildString(genesis), shape = Msquare)
                        _       <- g.close
                      } yield g)

                    lvlGraph = for {
                      g <- Graphz.subgraph[Effect](s"lvl$timeEntry", DiGraph, rank = Some(Same))
                      _ <- g.node(timeEntry.toString)
                      _ <- blocks.traverse(
                            b => g.node(name = PrettyPrinter.buildString(b.blockHash), shape = Box)
                          )
                      _ <- g.close
                    } yield g
                    graph = for {
                      g <- acc.graph
                      _ <- maybeLvl0.getOrElse(().pure[Effect])
                      _ <- g.subgraph(lvlGraph)
                      _ <- blocks.traverse(
                            b =>
                              b.getHeader.parentsHashList.toList
                                .map(PrettyPrinter.buildString)
                                .traverse { parentHash =>
                                  g.edge(PrettyPrinter.buildString(b.blockHash) -> parentHash)
                                }
                          )
                    } yield g
                  } yield {
                    val timeEntries = timeEntry :: maybeLvl0.map(kp(0L)).toList
                    acc.copy(
                      timeseries = timeEntries ++ acc.timeseries,
                      graph = graph
                    )
                  }

              }
        result <- Sync[F].delay {

                   val times = acc.timeseries.sorted.map(_.toString)

                   val timeseries: Effect[Graphz[Effect]] = for {
                     g     <- Graphz.subgraph[Effect]("timeseries", DiGraph)
                     _     <- times.traverse(n => g.node(name = n, shape = PlainText))
                     edges = times.zip(times.drop(1))
                     _     <- edges.traverse(g.edge)
                     _     <- g.close
                   } yield g

                   val finalGraph: Effect[Graphz[Effect]] = for {
                     g <- acc.graph
                     _ <- g.subgraph(timeseries)
                     _ <- g.close
                   } yield g
                   finalGraph.runS(new StringBuffer).toString
                 }
      } yield result

    MultiParentCasperRef.withCasper[F, String](
      casperResponse(_),
      "no casper"
    )
  }

  def showBlocks[F[_]: Monad: MultiParentCasperRef: Log: SafetyOracle: BlockStore](
      depth: Int
  ): F[List[BlockInfoWithoutTuplespace]] = {
    def casperResponse(implicit casper: MultiParentCasper[F]) =
      for {
        dag <- MultiParentCasper[F].blockDag
        flattenedBlockInfosUntilDepth <- getFlattenedBlockInfosUntilDepth[F](
                                          depth,
                                          dag
                                        )
      } yield flattenedBlockInfosUntilDepth.reverse

    MultiParentCasperRef.withCasper[F, List[BlockInfoWithoutTuplespace]](
      casperResponse(_),
      List.empty[BlockInfoWithoutTuplespace]
    )
  }

  private def getFlattenedBlockInfosUntilDepth[F[_]: Monad: MultiParentCasper: Log: SafetyOracle: BlockStore](
      depth: Int,
      dag: BlockDagRepresentation[F]
  ): F[List[BlockInfoWithoutTuplespace]] =
    for {
      topoSort <- dag.topoSortTail(depth)
      result <- topoSort.foldM(List.empty[BlockInfoWithoutTuplespace]) {
                 case (blockInfosAtHeightAcc, blockHashesAtHeight) =>
                   for {
                     blocksAtHeight     <- blockHashesAtHeight.traverse(ProtoUtil.unsafeGetBlock[F])
                     blockInfosAtHeight <- blocksAtHeight.traverse(getBlockInfoWithoutTuplespace[F])
                   } yield blockInfosAtHeightAcc ++ blockInfosAtHeight
               }
    } yield result

  def showMainChain[F[_]: Monad: MultiParentCasperRef: Log: SafetyOracle: BlockStore](
      depth: Int
  ): F[List[BlockInfoWithoutTuplespace]] = {
    def casperResponse(implicit casper: MultiParentCasper[F]) =
      for {
        dag        <- MultiParentCasper[F].blockDag
        estimates  <- MultiParentCasper[F].estimator(dag)
        tip        = estimates.head
        mainChain  <- ProtoUtil.getMainChainUntilDepth[F](tip, IndexedSeq.empty[BlockMessage], depth)
        blockInfos <- mainChain.toList.traverse(getBlockInfoWithoutTuplespace[F])
      } yield blockInfos

    MultiParentCasperRef.withCasper[F, List[BlockInfoWithoutTuplespace]](
      casperResponse(_),
      List.empty[BlockInfoWithoutTuplespace]
    )
  }

  // TODO: Replace with call to BlockStore
  def findBlockWithDeploy[F[_]: Monad: MultiParentCasperRef: Log: SafetyOracle: BlockStore](
      user: ByteString,
      timestamp: Long
  ): F[BlockQueryResponse] = {
    def casperResponse(implicit casper: MultiParentCasper[F]): F[BlockQueryResponse] =
      for {
        dag                <- MultiParentCasper[F].blockDag
        allBlocksTopoSort  <- dag.topoSort(0L)
        maybeBlock         <- findBlockWithDeploy[F](allBlocksTopoSort.flatten.reverse, user, timestamp)
        blockQueryResponse <- maybeBlock.traverse(getFullBlockInfo[F])
      } yield
        blockQueryResponse.fold(
          BlockQueryResponse(
            status = s"Error: Failure to find block containing deploy signed by ${PrettyPrinter
              .buildString(user)} with timestamp ${timestamp.toString}"
          )
        )(
          blockInfo =>
            BlockQueryResponse(
              status = "Success",
              blockInfo = Some(blockInfo)
            )
        )

    MultiParentCasperRef.withCasper[F, BlockQueryResponse](
      casperResponse(_),
      BlockQueryResponse(status = "Error: Casper instance not available")
    )
  }

  private def findBlockWithDeploy[F[_]: Monad: Log: BlockStore](
      blockHashes: Vector[BlockHash],
      user: ByteString,
      timestamp: Long
  ): F[Option[BlockMessage]] =
    blockHashes.toStream
      .traverse(ProtoUtil.unsafeGetBlock[F](_))
      .map(blocks => blocks.find(ProtoUtil.containsDeploy(_, user, timestamp)))

  def showBlock[F[_]: Monad: MultiParentCasperRef: Log: SafetyOracle: BlockStore](
      q: BlockQuery
  ): F[BlockQueryResponse] = {
    def casperResponse(implicit casper: MultiParentCasper[F]) =
      for {
        dag        <- MultiParentCasper[F].blockDag
        maybeBlock <- getBlock[F](q, dag)
        blockQueryResponse <- maybeBlock match {
                               case Some(block) =>
                                 for {
                                   blockInfo <- getFullBlockInfo[F](block)
                                 } yield
                                   BlockQueryResponse(
                                     status = "Success",
                                     blockInfo = Some(blockInfo)
                                   )
                               case None =>
                                 BlockQueryResponse(
                                   status = s"Error: Failure to find block with hash ${q.hash}"
                                 ).pure[F]
                             }
      } yield blockQueryResponse

    MultiParentCasperRef.withCasper[F, BlockQueryResponse](
      casperResponse(_),
      BlockQueryResponse(status = "Error: Casper instance not available")
    )
  }

  private def getBlockInfo[A, F[_]: Monad: MultiParentCasper: SafetyOracle: BlockStore](
      block: BlockMessage,
      constructor: (
          BlockMessage,
          Long,
          Int,
          BlockHash,
          Long,
          BlockHash,
          Seq[BlockHash],
          Float,
          Float
      ) => F[A]
  ): F[A] =
    for {
      dag         <- MultiParentCasper[F].blockDag
      header      = block.header.getOrElse(Header.defaultInstance)
      version     = header.version
      deployCount = header.deployCount
      tsHash = ProtoUtil.tuplespace(block) match {
        case Some(hash) => hash
        case None       => ByteString.EMPTY
      }
      timestamp                = header.timestamp
      mainParent               = header.parentsHashList.headOption.getOrElse(ByteString.EMPTY)
      parentsHashList          = header.parentsHashList
      normalizedFaultTolerance <- SafetyOracle[F].normalizedFaultTolerance(dag, block.blockHash)
      initialFault             <- MultiParentCasper[F].normalizedInitialFault(ProtoUtil.weightMap(block))
      blockInfo <- constructor(
                    block,
                    version,
                    deployCount,
                    tsHash,
                    timestamp,
                    mainParent,
                    parentsHashList,
                    normalizedFaultTolerance,
                    initialFault
                  )
    } yield blockInfo

  private def getFullBlockInfo[F[_]: Monad: MultiParentCasper: SafetyOracle: BlockStore](
      block: BlockMessage
  ): F[BlockInfo] = getBlockInfo[BlockInfo, F](block, constructBlockInfo[F])
  private def getBlockInfoWithoutTuplespace[F[_]: Monad: MultiParentCasper: SafetyOracle: BlockStore](
      block: BlockMessage
  ): F[BlockInfoWithoutTuplespace] =
    getBlockInfo[BlockInfoWithoutTuplespace, F](block, constructBlockInfoWithoutTuplespace[F])

  private def constructBlockInfo[F[_]: Monad: MultiParentCasper: SafetyOracle: BlockStore](
      block: BlockMessage,
      version: Long,
      deployCount: Int,
      tsHash: BlockHash,
      timestamp: Long,
      mainParent: BlockHash,
      parentsHashList: Seq[BlockHash],
      normalizedFaultTolerance: Float,
      initialFault: Float
  ): F[BlockInfo] =
    for {
      tsDesc <- MultiParentCasper[F].storageContents(tsHash)
    } yield
      BlockInfo(
        blockHash = PrettyPrinter.buildStringNoLimit(block.blockHash),
        blockSize = block.serializedSize.toString,
        blockNumber = ProtoUtil.blockNumber(block),
        version = version,
        deployCount = deployCount,
        tupleSpaceHash = PrettyPrinter.buildStringNoLimit(tsHash),
        tupleSpaceDump = tsDesc,
        timestamp = timestamp,
        faultTolerance = normalizedFaultTolerance - initialFault,
        mainParentHash = PrettyPrinter.buildStringNoLimit(mainParent),
        parentsHashList = parentsHashList.map(PrettyPrinter.buildStringNoLimit),
        sender = PrettyPrinter.buildStringNoLimit(block.sender),
        shardId = block.shardId
      )

  private def constructBlockInfoWithoutTuplespace[F[_]: Monad: MultiParentCasper: SafetyOracle: BlockStore](
      block: BlockMessage,
      version: Long,
      deployCount: Int,
      tsHash: BlockHash,
      timestamp: Long,
      mainParent: BlockHash,
      parentsHashList: Seq[BlockHash],
      normalizedFaultTolerance: Float,
      initialFault: Float
  ): F[BlockInfoWithoutTuplespace] =
    BlockInfoWithoutTuplespace(
      blockHash = PrettyPrinter.buildStringNoLimit(block.blockHash),
      blockSize = block.serializedSize.toString,
      blockNumber = ProtoUtil.blockNumber(block),
      version = version,
      deployCount = deployCount,
      tupleSpaceHash = PrettyPrinter.buildStringNoLimit(tsHash),
      timestamp = timestamp,
      faultTolerance = normalizedFaultTolerance - initialFault,
      mainParentHash = PrettyPrinter.buildStringNoLimit(mainParent),
      parentsHashList = parentsHashList.map(PrettyPrinter.buildStringNoLimit),
      sender = PrettyPrinter.buildStringNoLimit(block.sender)
    ).pure[F]

  private def getBlock[F[_]: Monad: MultiParentCasper: BlockStore](
      q: BlockQuery,
      dag: BlockDagRepresentation[F]
  ): F[Option[BlockMessage]] =
    for {
      findResult <- BlockStore[F].find(h => {
                     Base16.encode(h.toByteArray).startsWith(q.hash)
                   })
    } yield
      findResult.headOption match {
        case Some((_, block)) =>
          Some(block)
        case None =>
          none[BlockMessage]
      }

  private def addResponse(status: BlockStatus, block: BlockMessage): DeployServiceResponse =
    status match {
      case _: InvalidBlock =>
        DeployServiceResponse(success = false, s"Failure! Invalid block: $status")
      case _: ValidBlock =>
        val hash = PrettyPrinter.buildString(block.blockHash)
        DeployServiceResponse(success = true, s"Success! Block $hash created and added.")
      case BlockException(ex) =>
        DeployServiceResponse(success = false, s"Error during block processing: $ex")
      case Processing =>
        DeployServiceResponse(
          success = false,
          "No action taken since other thread is already processing the block."
        )
    }

  def previewPrivateNames[F[_]: Monad: Log](
      user: ByteString,
      timestamp: Long,
      nameQty: Int
  ): F[PrivateNamePreviewResponse] = {
    val seed    = DeployData().withUser(user).withTimestamp(timestamp)
    val rand    = Blake2b512Random(DeployData.toByteArray(seed))
    val safeQty = nameQty min 1024
    val ids     = (0 until safeQty).map(_ => ByteString.copyFrom(rand.next()))
    PrivateNamePreviewResponse(ids).pure[F]
  }
}
