package io.casperlabs.comm.gossiping

import cats._
import cats.implicits._
import cats.effect._
import com.google.protobuf.ByteString
import io.casperlabs.casper.consensus.{Block, BlockSummary}
import io.casperlabs.shared.Compression
import monix.tail.Iterant

/** Server side implementation talking to the storage. */
class GossipServiceImpl[F[_]: Sync](
    getBlock: ByteString => F[Block],
    maxMessageSize: Int
) extends GossipService[F] {
  import GossipServiceImpl.chunkIt

  def newBlocks(request: NewBlocksRequest): F[NewBlocksResponse] = ???

  def streamAncestorBlockSummaries(
      request: StreamAncestorBlockSummariesRequest
  ): Iterant[F, BlockSummary] = ???

  def streamDagTipBlockSummaries(
      request: StreamDagTipBlockSummariesRequest
  ): Iterant[F, BlockSummary] = ???

  def batchGetBlockSummaries(
      request: BatchGetBlockSummariesRequest
  ): F[BatchGetBlockSummariesResponse] = ???

  def getBlockChunked(request: GetBlockChunkedRequest): Iterant[F, Chunk] =
    Iterant.liftF {
      getBlock(request.blockHash)
    } flatMap { block =>
      val it = chunkIt(
        block.toByteArray,
        effectiveChunkSize(request.chunkSize),
        request.acceptedCompressionAlgorithms
      )

      Iterant.fromIterator(it)
    }

  def effectiveChunkSize(chunkSize: Int): Int =
    if (0 < chunkSize && chunkSize < maxMessageSize) chunkSize
    else maxMessageSize
}

object GossipServiceImpl {
  type Compressor = Array[Byte] => Array[Byte]

  val compressors: Map[String, Compressor] = Map(
    "lz4" -> Compression.compress
  )

  def chunkIt(
      data: Array[Byte],
      chunkSize: Int,
      acceptedCompressionAlgorithms: Seq[String]
  ): Iterator[Chunk] = {
    val (alg, content) = acceptedCompressionAlgorithms.map(_.toLowerCase).collectFirst {
      case alg if compressors contains alg =>
        alg -> compressors(alg)(data)
    } getOrElse {
      "" -> data
    }

    val header = Chunk.Header(
      // Sending the final length so the receiver knows how many chunks they are going to get.
      contentLength = content.length,
      compressionAlgorithm = alg
    )

    val chunks = content.sliding(chunkSize, chunkSize).map { arr =>
      Chunk().withData(ByteString.copyFrom(arr))
    }

    Iterator(Chunk().withHeader(header)) ++ chunks
  }
}
