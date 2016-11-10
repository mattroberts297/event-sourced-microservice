package com.example.commands

case class UpdatePost(body: String)

object UpdatePost {
  import play.api.libs.json._
  val MaxBody = 1024
  private val reads = Json.reads[UpdatePost].
    filter(p => p.body.length < MaxBody)
  private val writes = Json.writes[UpdatePost]
  implicit val format = Format(reads, writes)
}