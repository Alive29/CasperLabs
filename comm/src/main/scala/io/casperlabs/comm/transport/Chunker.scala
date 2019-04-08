package io.casperlabs.comm.transport

import io.casperlabs.comm.protocol.routing._
import io.casperlabs.comm.rp.ProtocolHelper
import io.casperlabs.shared.Compression._

object Chunker {

  def chunkIt(blob: Blob, maxMessageSize: Int): Iterator[Chunk] = {
    val raw      = blob.packet.content.toByteArray
    val kb500    = 1024 * 500
    val compress = raw.length > kb500
    val content  = if (compress) raw.compress else raw

    def header: Chunk =
      Chunk().withHeader(
        ChunkHeader()
          .withCompressed(compress)
          .withContentLength(raw.length)
          .withSender(blob.sender)
          .withTypeId(blob.packet.typeId)
      )
    val buffer    = 2 * 1024 // 2 kbytes for protobuf related stuff
    val chunkSize = maxMessageSize - buffer
    def data: Iterator[Chunk] =
      content.sliding(chunkSize, chunkSize).map { data =>
        Chunk().withData(ChunkData().withContentData(ProtocolHelper.toProtocolBytes(data)))
      }

    Iterator(header) ++ data
  }
}
