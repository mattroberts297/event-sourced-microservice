package com.example.events

import java.util.UUID

import scala.annotation.tailrec
import scala.collection.immutable._
import scala.concurrent.Future
import scala.util.Try

import com.example.exceptions.ConflictException
import com.example.exceptions.NotFoundException
import com.example.io.CanRead
import com.example.io.CanReplay
import com.example.io.CanValidate
import com.example.io.CanWrite
import com.example.views.Post

sealed trait PostEvent

case class PostCreated(id: UUID, body: String) extends PostEvent
case class PostUpdated(id: UUID, body: String) extends PostEvent
case class PostDeleted(id: UUID) extends PostEvent

object PostEvent {
  // TODO Create macro for this.
  import play.api.libs.json._

  implicit val eventWrites = new Writes[PostEvent] {
    def writes(e: PostEvent) = e match {
      case e: PostCreated => Json.obj("id" -> e.id, "type" -> "post_created", "body" -> e.body)
      case e: PostUpdated => Json.obj("id" -> e.id, "type" -> "post_updated", "body" -> e.body)
      case e: PostDeleted => Json.obj("id" -> e.id, "type" -> "post_deleted")
    }
  }

  implicit val eventReads = new Reads[PostEvent] {
    override def reads(j: JsValue): JsResult[PostEvent] = Try {
      (j \ "type").as[String] match {
        case "post_created" => PostCreated((j \ "id").as[UUID], (j \ "body").as[String])
        case "post_updated" => PostUpdated((j \ "id").as[UUID], (j \ "body").as[String])
        case "post_deleted" => PostDeleted((j \ "id").as[UUID])
      }
    } map { case e => JsSuccess(e) } getOrElse { JsError() }
  }

  implicit object CanReadPostEvent extends CanRead[PostEvent, String] {
    override def read(a: String): PostEvent = Json.fromJson[PostEvent](Json.parse(a)).get
  }

  implicit object CanWritePostEvent extends CanWrite[PostEvent, String] {
    override def write(a: PostEvent): String = Json.stringify(Json.toJson(a))
  }

  implicit object CanWriteUUID extends CanWrite[UUID, UUID] {
    override def write(a: UUID): UUID = a
  }

  implicit object CanWriteUUIDAsString extends CanWrite[UUID, String] {
    override def write(a: UUID): String = a.toString
  }

  private def update(event: PostEvent, state: Option[Post]): Option[Post] = {
    event match {
      case e: PostCreated => Some(Post(e.id, e.body))
      case e: PostUpdated => state.map(p => p.copy(body = e.body))
      case e: PostDeleted => None
    }
  }

  implicit object CanReplayPostEvent extends CanReplay[PostEvent, Post] {
    override def replay(es: Seq[PostEvent]): Option[Post] = {
      replay(es, None)
    }

    @tailrec
    def replay(es: Seq[PostEvent], s: Option[Post]): Option[Post] = {
      es.headOption match {
        case Some(e) => replay(es.tail, update(e, s))
        case None => s
      }
    }
  }

  implicit object CanValidatePostEvent extends CanValidate[PostEvent, Post] {
    override def validate(event: PostEvent, state: Option[Post]): Future[Option[Post]] = {
      event match {
        case e: PostCreated if state.isDefined => Future.failed(new ConflictException)
        case e: PostUpdated if state.isEmpty => Future.failed(new NotFoundException)
        case e: PostDeleted if state.isEmpty => Future.failed(new NotFoundException)
        case _ => Future.successful(update(event, state))
      }
    }
  }
}
