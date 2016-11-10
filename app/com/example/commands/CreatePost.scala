package com.example.commands

import java.util.UUID

case class CreatePost(id: UUID, body: String)

object CreatePost {
  import play.api.libs.json._
  val MaxBody = 1024
  private val reads = Json.reads[CreatePost].
    filter(p => p.body.length < MaxBody)
  private val writes = Json.writes[CreatePost]
  implicit val format = Format(reads, writes)
}