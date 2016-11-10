package com.example.repos

import java.util.UUID
import javax.inject.Inject

import scala.annotation.tailrec
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.collection.immutable._

import com.example.exceptions.ConflictException
import com.example.journals.EventJournal
import com.example.events._
import com.example.exceptions.NotFoundException
import com.example.views.Post

class EventSourcedPostsRepo @Inject() private[repos](
  private val journal: EventJournal[UUID, PostEvent, UUID, String])(
  implicit private val context: ExecutionContext) extends PostsRepo {

  import PostEvent._

  def create(id: UUID, body: String): Future[Option[Post]] = {
    journal.write[Post](id, PostCreated(id, body))
  }

  def read(id: UUID): Future[Option[Post]] = {
    journal.read[Post](id)
  }

  def update(id: UUID, body: String): Future[Unit] = {
    journal.write[Post](id, PostUpdated(id, body)).map(_ => ())
  }

  def delete(id: UUID): Future[Unit] = {
    journal.write[Post](id, PostDeleted(id)).map(_ => ())
  }

  private def update(event: PostEvent, state: Option[Post]): Option[Post] = {
    event match {
      case e: PostCreated => Some(Post(e.id, e.body))
      case e: PostUpdated => state.map(p => p.copy(body = e.body))
      case e: PostDeleted => None
    }
  }

  private def validate(event: PostEvent, state: Option[Post]): Future[Option[Post]] = {
    event match {
      case e: PostCreated if state.isDefined => Future.failed(new ConflictException)
      case e: PostUpdated if state.isEmpty => Future.failed(new NotFoundException)
      case e: PostDeleted if state.isEmpty => Future.failed(new NotFoundException)
      case _ => Future.successful(update(event, state))
    }
  }
}
