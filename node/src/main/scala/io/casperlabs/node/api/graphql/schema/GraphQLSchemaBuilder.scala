package io.casperlabs.node.api.graphql.schema

import cats.implicits._
import io.casperlabs.blockstorage.BlockStore
import io.casperlabs.casper.MultiParentCasperRef.MultiParentCasperRef
import io.casperlabs.casper.SafetyOracle
import io.casperlabs.casper.api.BlockAPI
import io.casperlabs.catscontrib.MonadThrowable
import io.casperlabs.node.api.graphql.RunToFuture.ops._
import io.casperlabs.node.api.graphql._
import io.casperlabs.shared.Log
import sangria.schema._

private[graphql] class GraphQLSchemaBuilder[F[_]: Fs2SubscriptionStream: Log: RunToFuture: MultiParentCasperRef: SafetyOracle: BlockStore: FinalizedBlocksStream: MonadThrowable] {

  val requireFullBlockFields: Set[String] = Set("blockSizeBytes", "deployErrorCount", "deploys")

  def hasAtLeastOne(projections: Vector[ProjectedName], fields: Set[String]): Boolean = {
    def flatToSet(ps: Vector[ProjectedName], acc: Set[String]): Set[String] =
      if (ps.isEmpty) {
        acc
      } else {
        val h = ps.head
        flatToSet(ps.tail, acc + h.name) ++ flatToSet(h.children, acc)
      }

    flatToSet(projections, Set.empty).intersect(fields).nonEmpty
  }

  def createSchema: Schema[Unit, Unit] =
    Schema(
      query = ObjectType(
        "Query",
        fields[Unit, Unit](
          Field(
            "block",
            OptionType(blocks.types.BlockType),
            arguments = blocks.arguments.BlockHashPrefix :: Nil,
            resolve = Projector { (context, projections) =>
              BlockAPI
                .getBlockInfoOpt[F](
                  blockHashBase16 = context.arg(blocks.arguments.BlockHashPrefix),
                  full = hasAtLeastOne(projections, requireFullBlockFields)
                )
                .unsafeToFuture
            }
          ),
          Field(
            "dagSlice",
            ListType(blocks.types.BlockType),
            arguments = blocks.arguments.Depth :: blocks.arguments.MaxRank :: Nil,
            resolve = Projector { (context, projections) =>
              BlockAPI
                .getBlockInfosMaybeWithBlocks[F](
                  depth = context.arg(blocks.arguments.Depth),
                  maxRank = context.arg(blocks.arguments.MaxRank),
                  full = hasAtLeastOne(projections, requireFullBlockFields)
                )
                .unsafeToFuture
            }
          ),
          Field(
            "deploy",
            OptionType(blocks.types.DeployInfoType),
            arguments = blocks.arguments.DeployHash :: Nil,
            resolve =
              c => BlockAPI.getDeployInfoOpt[F](c.arg(blocks.arguments.DeployHash)).unsafeToFuture
          )
        )
      ),
      subscription = ObjectType(
        "Subscription",
        fields[Unit, Unit](
          Field.subs(
            "finalizedBlocks",
            blocks.types.BlockType,
            "Subscribes to new finalized blocks".some,
            resolve = { c =>
              // Projectors don't work with Subscriptions
              val requireFullBlock = c.query.renderCompact
                .split("[^a-zA-Z0-9]")
                .collect {
                  case s if s.trim.nonEmpty => s.trim
                }
                .toSet
                .intersect(requireFullBlockFields)
                .nonEmpty
              FinalizedBlocksStream[F].subscribe.evalMap { blockHash =>
                BlockAPI
                  .getBlockInfoWithBlock[F](
                    blockHash = blockHash,
                    full = requireFullBlock
                  )
                  .map(Action(_))
              }
            }
          )
        )
      ).some
    )
}
