package io.casperlabs.node

import org.http4s._
import org.http4s.dsl.io._
import monix.eval.Task

object VersionInfo {
  val get: String =
    s"CasperLabs node ${BuildInfo.version} (${BuildInfo.gitHeadCommit.getOrElse("commit # unknown")})"

  def service = HttpRoutes.of[Task] {
    case GET -> Root => Ok(get)
  }
}
