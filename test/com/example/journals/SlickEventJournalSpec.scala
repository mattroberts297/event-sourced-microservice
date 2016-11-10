package com.example.journals

import java.util.UUID

import scala.concurrent.Future
import scala.concurrent.Promise
import scala.collection.immutable._

import com.example.events._
import com.example.io.CanReplay
import com.example.io.CanValidate
import com.example.views.Post
import org.scalatest.AsyncWordSpec
import org.scalatest.MustMatchers
import org.scalatest.ParallelTestExecution
import slick.driver.JdbcDriver
import slick.jdbc.JdbcBackend.Database

class SlickEventJournalSpec extends AsyncWordSpec with MustMatchers with ParallelTestExecution {
  implicit override def executionContext = scala.concurrent.ExecutionContext.Implicits.global

  import PostEvent._
  val uuid = UUID.randomUUID()
  val body = "Test body"
  val createEvent = PostCreated(uuid, body)
  val updateEvent = PostUpdated(uuid, body.reverse)
  val deleteEvent = PostDeleted(uuid)

  "SlickEventJournal::write" must {
    "pass previously written events to replay" in {
      withJournal { journal =>
        val expected = Seq(createEvent)
        val promise = Promise.apply[Seq[PostEvent]]()
        object StubCanReplay extends CanReplay[PostEvent, Post] {
          def replay(e: Seq[PostEvent]): Option[Post] = {
            promise.success(e)
            Some(Post(uuid, body))
          }
          def replay(es: Seq[PostEvent], s: Option[Post]): Option[Post] = {
            ???
          }
        }
        journal.init().
          flatMap(_ => journal.write[Post](uuid, createEvent)).
          flatMap(_ => journal.write[Post](uuid, updateEvent)(canReplay = StubCanReplay, canValidate = CanValidatePostEvent, canReadEvent = CanReadPostEvent, canWriteEvent = CanWritePostEvent, canWriteIdentifier = CanWriteUUID)).
          flatMap(_ => promise.future).
          map(actual => actual must be (expected))
      }
    }

    "pass the result of replay to validate" in {
      withJournal { journal =>
        val expected = Some(Post(uuid, body))
        def replay(e: Seq[PostEvent]): Option[Post] = expected
        val promise = Promise.apply[Option[Post]]()
        object StubCanValidate extends CanValidate[PostEvent, Post] {
          def validate(e: PostEvent, s: Option[Post]): Future[Option[Post]] = {
            promise.success(s).future
          }
        }
        journal.init().
          flatMap(_ => journal.write[Post](uuid, createEvent)).
          flatMap(_ => journal.write[Post](uuid, updateEvent)(canReplay = CanReplayPostEvent, canValidate = StubCanValidate, canReadEvent = CanReadPostEvent, canWriteEvent = CanWritePostEvent, canWriteIdentifier = CanWriteUUID)).
          flatMap(_ => promise.future).
          map(actual => actual must be (expected))
      }
    }

    "return the result of validate" in {
      withJournal { journal =>
        val write = journal.write[Post]_
        journal.init().flatMap(_ => write(uuid, createEvent)).map(e => e must be (Some(Post(uuid, body))))
      }
    }

    "not write if validate fails" in {
      pending
    }
  }

  "SlickEventJournal::events" when {
    "no events have been written" must {
      "return an empty Seq" in {
        withJournal { journal =>
          journal.init().
            flatMap(_ => journal.events(uuid)).
            map(es => es must be (Seq.empty))
        }
      }
    }

    "one event has been written" must {
      "return a Seq containing that event" in {
        withJournal { journal =>
          val write = journal.write[Post]_
          journal.init().
            flatMap(_ => write(uuid, createEvent)).
            flatMap(_ => journal.events(uuid)).
            map(es => es must be (Seq(createEvent)))
        }
      }
    }

    "three events have been written" must {
      "return a Seq containing those events in order" in {
        withJournal { journal =>
          val write = journal.write[Post]_
          journal.init().
            flatMap(_ => write(uuid, createEvent)).
            flatMap(_ => write(uuid, updateEvent)).
            flatMap(_ => write(uuid, deleteEvent)).
            flatMap(_ => journal.events(uuid)).
            map(es => es must be (Seq(createEvent, updateEvent, deleteEvent)))
        }
      }
    }
  }

  "SlickEventJournal::read" when {
    "no events have been written" must {
      "return None" in {
        withJournal { journal =>
          val read = journal.read[Post]_
          journal.init().
            flatMap(_ => read(uuid)).
            map(es => es must be (None))
        }
      }
    }

    "one event have been written" must {
      "return Some(Post(...))" in {
        withJournal { journal =>
          val write = journal.write[Post]_
          val read = journal.read[Post]_
          journal.init().
            flatMap(_ => write(uuid, createEvent)).
            flatMap(_ => read(uuid)).
            map(es => es must be (Some(Post(uuid, body))))
        }
      }
    }

    "two events have been written" must {
      "return Some(Post(...))" in {
        withJournal { journal =>
          val write = journal.write[Post]_
          val read = journal.read[Post]_
          journal.init().
            flatMap(_ => write(uuid, createEvent)).
            flatMap(_ => write(uuid, updateEvent)).
            flatMap(_ => read(uuid)).
            map(es => es must be (Some(Post(uuid, body.reverse))))
        }
      }
    }

    "three events have been written" must {
      "return None" in {
        withJournal { journal =>
          val write = journal.write[Post]_
          val read = journal.read[Post]_
          journal.init().
            flatMap(_ => write(uuid, createEvent)).
            flatMap(_ => write(uuid, updateEvent)).
            flatMap(_ => write(uuid, deleteEvent)).
            flatMap(_ => read(uuid)).
            map(es => es must be (None))
        }
      }
    }
  }

  def withJournal[T](thunk: SlickEventJournal[UUID, PostEvent, UUID, String] => T): T = {
    val driver: JdbcDriver = slick.driver.H2Driver
    val database: Database  = Database.forURL(s"jdbc:h2:mem:${UUID.randomUUID()};DB_CLOSE_DELAY=-1")
    val name: String  = "posts"
    import driver.api._
    val journal = new SlickEventJournal[UUID, PostEvent, UUID, String](driver, database)
    thunk(journal)
  }
}
