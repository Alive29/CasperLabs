package io.casperlabs.node.configuration

import java.nio.file.{Path, Paths}

import io.casperlabs.comm.PeerNode
import cats.syntax.either._
import toml._
import toml.Codecs._
import toml.Toml._
import cats.syntax.either._
import io.casperlabs.shared.StoreType
import toml.util.RecordToMap

import scala.concurrent.duration.{Duration, FiniteDuration}
import scala.util.Try

private[configuration] object TomlReader {
  private implicit val bootstrapAddressCodec: Codec[PeerNode] =
    Codec {
      case (Value.Str(uri), _) =>
        PeerNode
          .fromAddress(uri)
          .map(u => Right(u))
          .getOrElse(Left((Nil, "can't parse the rnode bootstrap address")))
      case _ => Left((Nil, "the rnode bootstrap address should be a string"))
    }
  private implicit val pathCodec: Codec[Path] =
    Codec {
      case (Value.Str(uri), _) =>
        Try(Paths.get(uri.replace("$HOME", sys.props("user.home")))).toEither
          .leftMap(_ => (Nil, s"Can't parse the path $uri"))
      case _ => Left((Nil, "A path must be a string"))
    }
  private implicit val boolCodec: Codec[Boolean] = Codec {
    case (Value.Bool(value), _) => Right(value)
    case (value, _) =>
      Left((List.empty, s"Bool expected, $value provided"))
  }
  private implicit val finiteDurationCodec: Codec[FiniteDuration] = Codec {
    case (Value.Str(value), _) =>
      Duration(value) match {
        case fd: FiniteDuration => Either.right(fd)
        case _                  => Either.left((Nil, s"Failed to parse $value as FiniteDuration."))
      }
    case (value, _) =>
      Either.left((Nil, s"Failed to parse $value as FiniteDuration."))
  }
  private implicit val storeTypeCodec: Codec[StoreType] = Codec {
    case (Value.Str(value), _) =>
      StoreType
        .from(value)
        .map(Right(_))
        .getOrElse(Left(Nil, s"Failed to parse $value as StoreType"))
    case (value, _) =>
      Left(Nil, s"Failed to parse $value as StoreType")
  }

  def parse(raw: String): Either[String, ConfigurationSoft] =
    Toml
      .parse(raw)
      .map(rewriteKeysToCamelCase)
      .flatMap(
        table => {
          Toml
            .parseAs[ConfigurationSoft](table)
            .leftMap {
              case (address, message) =>
                s"$message at $address"
            }
        }
      )

  private def rewriteKeysToCamelCase(tbl: Value.Tbl): Value.Tbl = {
    def rewriteTbl(t: Value.Tbl): Value.Tbl =
      Value.Tbl(
        t.values.map {
          case (key, t1 @ Value.Tbl(_)) => (camelify(key), rewriteTbl(t1))
          case (key, a @ Value.Arr(_))  => (camelify(key), rewriteArr(a))
          case (key, value)             => (camelify(key), value)
        }
      )

    def rewriteArr(a: Value.Arr): Value.Arr =
      Value.Arr(
        a.values.map {
          case t1 @ Value.Tbl(_) => rewriteTbl(t1)
          case a @ Value.Arr(_)  => rewriteArr(a)
          case value             => value
        }
      )

    rewriteTbl(tbl)
  }

  private def camelify(name: String): String = {
    def loop(x: List[Char]): List[Char] = (x: @unchecked) match {
      case '-' :: '-' :: rest => loop('_' :: rest)
      case '-' :: c :: rest   => Character.toUpperCase(c) :: loop(rest)
      case '-' :: Nil         => Nil
      case c :: rest          => c :: loop(rest)
      case Nil                => Nil
    }

    loop(name.toList).mkString
  }
}
