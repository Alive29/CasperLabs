package io.casperlabs.comm.transport

import org.scalatest._
import java.io.File
import java.nio.file._
import io.casperlabs.comm._, CommError.CommErr
import io.casperlabs.comm.rp.ProtocolHelper
import cats._, cats.data._, cats.implicits._
import io.casperlabs.comm.protocol.routing._
import com.google.protobuf.ByteString
import cats.effect.Sync
import org.scalacheck.Gen
import org.scalatest.prop.GeneratorDrivenPropertyChecks
import scala.util.Random

class PacketStoreRestoreSpec
    extends FunSpec
    with Matchers
    with BeforeAndAfterEach
    with GeneratorDrivenPropertyChecks {

  import PacketOps._

  var tempFolder: Path = null

  override def beforeEach(): Unit =
    tempFolder = Files.createTempDirectory("casperlabs")

  override def afterEach(): Unit =
    tempFolder.toFile.delete()

  describe("Packet store & restore") {
    it("should store and restore to the original Packet") {
      forAll(contentGen) { content: Array[Byte] =>
        // given
        val parent = tempFolder.resolve("inner").resolve("folder")
        val packet = Packet(BlockMessage.id, ByteString.copyFrom(content))
        // when
        val storedIn = packet.store[Id](parent).right.get
        val restored = PacketOps.restore[Id](storedIn).right.get
        // then
        storedIn.toFile.exists() shouldBe (true)
        storedIn.getParent shouldBe (parent)
        packet shouldBe (restored)
      }
    }
  }

  val contentGen =
    for (n <- Gen.choose(10, 50000))
      yield Array.fill(n)((Random.nextInt(256) - 128).toByte)

  implicit val syncId: Sync[Id] = io.casperlabs.catscontrib.effect.implicits.syncId
}
