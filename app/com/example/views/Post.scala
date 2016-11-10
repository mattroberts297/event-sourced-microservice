package com.example.views

import java.util.UUID

import scala.collection.immutable._

import play.api.libs.json.Format
import play.api.libs.json.Json

case class Comment(id: UUID, body: String)

object Comment {
  val MaxComment = 256
  private val reads = Json.reads[Comment].
    filter(c => c.body.length < MaxComment)
  private val writes = Json.writes[Comment]
  implicit val format = Format(reads, writes)
}

case class Post(id: UUID, body: String) // TODO Add comments: Seq[Comment]

object Post {
  import play.api.libs.json._
  val MaxBody = 1024
  private val reads = Json.reads[Post].
    filter(p => p.body.length < MaxBody)
  private val writes = Json.writes[Post]
  implicit val format = Format(reads, writes)
}
