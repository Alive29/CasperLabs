package io.casperlabs.client
import java.io.Closeable
import java.util.concurrent.TimeUnit
import com.google.protobuf.ByteString
import com.google.protobuf.empty.Empty
import io.casperlabs.crypto.codec.Base16
import io.casperlabs.casper.protocol._
import io.casperlabs.casper.consensus
import io.casperlabs.node.api.casper.{CasperGrpcMonix, DeployRequest, GetBlockRequest}
import io.casperlabs.node.api.control.{ControlGrpcMonix, ProposeRequest}
import io.grpc.{ManagedChannel, ManagedChannelBuilder}
import monix.eval.Task

import scala.util.Either

class GrpcDeployService(host: String, portExternal: Int, portInternal: Int)
    extends DeployService[Task]
    with Closeable {
  private val DefaultMaxMessageSize = 256 * 1024 * 1024

  private var externalConnected = false
  private var internalConnected = false

  private lazy val externalChannel: ManagedChannel = {
    externalConnected = true
    ManagedChannelBuilder
      .forAddress(host, portExternal)
      .usePlaintext()
      .maxInboundMessageSize(DefaultMaxMessageSize)
      .build()
  }

  private lazy val internalChannel: ManagedChannel = {
    internalConnected = true
    ManagedChannelBuilder
      .forAddress(host, portInternal)
      .usePlaintext()
      .maxInboundMessageSize(DefaultMaxMessageSize)
      .build()
  }

  private lazy val deployServiceStub  = CasperMessageGrpcMonix.stub(externalChannel)
  private lazy val casperServiceStub  = CasperGrpcMonix.stub(externalChannel)
  private lazy val controlServiceStub = ControlGrpcMonix.stub(internalChannel)

  def deploy(d: consensus.Deploy): Task[Either[Throwable, String]] =
    casperServiceStub.deploy(DeployRequest().withDeploy(d)).map(_ => "Success!").attempt

  def propose(): Task[Either[Throwable, String]] =
    controlServiceStub
      .propose(ProposeRequest())
      .map { response =>
        val hash = Base16.encode(response.blockHash.toByteArray).take(10)
        s"Success! Block $hash... created and added."
      }
      .attempt

  def showBlock(hash: String): Task[Either[Throwable, String]] = {
    val blockHash = ByteString.copyFrom(Base16.decode(hash))

    casperServiceStub
      .getBlock(GetBlockRequest(blockHash, GetBlockRequest.View.FULL))
      .map(_.toProtoString)
      .attempt
  }

  def queryState(q: QueryStateRequest): Task[Either[Throwable, String]] =
    deployServiceStub
      .queryState(q)
      .map(_.result)
      .attempt

  def visualizeDag(q: VisualizeDagQuery): Task[Either[Throwable, String]] =
    deployServiceStub.visualizeDag(q).attempt.map(_.map(_.content))

  def showBlocks(q: BlocksQuery): Task[Either[Throwable, String]] =
    deployServiceStub
      .showBlocks(q)
      .map { bi =>
        s"""
         |------------- block ${bi.blockNumber} ---------------
         |${bi.toProtoString}
         |-----------------------------------------------------
         |""".stripMargin
      }
      .toListL
      .map { bs =>
        val showLength =
          s"""
           |count: ${bs.length}
           |""".stripMargin

        Right(bs.mkString("\n") + "\n" + showLength)
      }

  override def close(): Unit = {
    if (internalConnected)
      internalChannel.shutdown().awaitTermination(10, TimeUnit.SECONDS)
    if (externalConnected)
      externalChannel.shutdown().awaitTermination(10, TimeUnit.SECONDS)
  }
}
