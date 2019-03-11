package io.casperlabs.node.configuration

import java.nio.file.{Path, Paths}

import scala.util.Try
import cats.syntax.either._
import io.casperlabs.comm.{CommError, PeerNode}
import io.casperlabs.shared.StoreType

import scala.concurrent.duration.Duration.Infinite
import scala.concurrent.duration._

private[configuration] trait Parser[A] {
  def parse(s: String): Either[String, A]
}

private[configuration] trait ParserImplicits {
  implicit val stringParser: Parser[String] = _.asRight[String]
  implicit val intParser: Parser[Int]       = s => Try(s.toInt).toEither.leftMap(_.getMessage)
  implicit val longParser: Parser[Long]     = s => Try(s.toLong).toEither.leftMap(_.getMessage)
  implicit val doubleParser: Parser[Double] = s => Try(s.toDouble).toEither.leftMap(_.getMessage)
  implicit val booleanParser: Parser[Boolean] = {
    case "true"  => true.asRight[String]
    case "false" => false.asRight[String]
    case s =>
      s"Failed to parse '$s' as Boolean, must be 'true' or 'false'"
        .asLeft[Boolean]
  }
  implicit val finiteDurationParser: Parser[FiniteDuration] = s =>
    Try(Duration(s)).toEither
      .leftMap(_.getMessage)
      .flatMap {
        case fd: FiniteDuration => fd.asRight[String]
        case _: Infinite        => "Got Infinite, expected FiniteDuration".asLeft[FiniteDuration]
      }
  implicit val pathParser: Parser[Path] = s =>
    Try(Paths.get(s.replace("$HOME", sys.props("user.home")))).toEither
      .leftMap(_.getMessage)
  implicit val peerNodeParser: Parser[PeerNode] = s =>
    PeerNode.fromAddress(s).leftMap(CommError.errorMessage)
  implicit val storeTypeParser: Parser[StoreType] = s =>
    StoreType
      .from(s)
      .fold(s"Failed to parse '$s' as StoreType".asLeft[StoreType])(_.asRight[String])
}

private[configuration] object Parser {
  def apply[A](implicit P: Parser[A]): Parser[A] = P
}
