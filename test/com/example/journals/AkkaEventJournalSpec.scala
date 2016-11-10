package com.example.journals

import java.util.UUID

import scala.concurrent.Await
import scala.concurrent.duration._

import akka.actor.ActorSystem
import akka.testkit.TestKit
import com.typesafe.config.ConfigFactory
import com.example.events._
import com.example.views.Post
import org.scalatest.AsyncWordSpec
import org.scalatest.AsyncWordSpecLike
import org.scalatest.BeforeAndAfterAll
import org.scalatest.MustMatchers
import org.scalatest.ParallelTestExecution

class AkkaEventJournalSpec extends TestKit(ActorSystem("test")) with AsyncWordSpecLike with MustMatchers with BeforeAndAfterAll {
  import PostEvent._
  val uuid = UUID.randomUUID()
  val body = "Test body"
  val createEvent = PostCreated(uuid, body)
  val updateEvent = PostUpdated(uuid, body.reverse)
  val deleteEvent = PostDeleted(uuid)

  "AkkaEventJournal::read" when {
    "no events have been written" must {
      "return None" in {
        withJournal { journal =>
          journal.read(uuid).map(es => es must be (None))
        }
      }
    }

    "one event have been written" must {
      "return Some(Post(...))" in {
        withJournal { journal =>
          journal.write(uuid, createEvent).
            flatMap(_ => journal.read(uuid)).
            map(es => es must be (Some(Post(uuid, body))))
        }
      }
    }

    "two events have been written" must {
      "return Some(Post(...))" in {
        withJournal { journal =>
          journal.write(uuid, createEvent).
            flatMap(_ => journal.write(uuid, updateEvent)).
            flatMap(_ => journal.read(uuid)).
            map(es => es must be (Some(Post(uuid, body.reverse))))
        }
      }
    }

    "three events have been written" must {
      "return None" in {
        withJournal { journal =>
          journal.write(uuid, createEvent).
            flatMap(_ => journal.write(uuid, updateEvent)).
            flatMap(_ => journal.write(uuid, deleteEvent)).
            flatMap(_ => journal.read(uuid)).
            map(es => es must be (None))
        }
      }
    }
  }

  def withJournal[T](thunk: AkkaEventJournal[UUID, PostEvent, String, Post] => T): T = {
    val name: String  = s"posts-${UUID.randomUUID()}"
    val journal = new AkkaEventJournal[UUID, PostEvent, String, Post](name)
    thunk(journal)
  }

  override def beforeAll(): Unit = {
    import slick.driver.H2Driver.api._
    val createTables: DBIO[Unit] = DBIO.seq(
      sqlu"DROP TABLE IF EXISTS PUBLIC.journal;",
      sqlu"CREATE TABLE IF NOT EXISTS PUBLIC.journal ( ordering BIGINT AUTO_INCREMENT, persistence_id VARCHAR(255) NOT NULL, sequence_number BIGINT NOT NULL, deleted BOOLEAN DEFAULT FALSE, tags VARCHAR(255) DEFAULT NULL, message BYTEA NOT NULL, PRIMARY KEY(persistence_id, sequence_number) ); ",
      sqlu"DROP TABLE IF EXISTS PUBLIC.snapshot;",
      sqlu"CREATE TABLE IF NOT EXISTS PUBLIC.snapshot ( persistence_id VARCHAR(255) NOT NULL, sequence_number BIGINT NOT NULL, created BIGINT NOT NULL, snapshot BYTEA NOT NULL, PRIMARY KEY(persistence_id, sequence_number) );"
    )
    val database = Database.forConfig("slick.db")
    Await.result(database.run(createTables), 5 seconds)
  }

  override def afterAll(): Unit = {
    Await.result(system.terminate(), 5 seconds)
  }
}
