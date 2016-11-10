### Introduction

Event sourcing aims to capture all changes to the _state_ of a microservice as a sequence of _events_. The persistence mechanism used to store these events is referred to as an _event journal_. This is usually a NoSQL or SQL database, but it could also be a bespoke solution.

A microservice's state can then be recreated by _replaying_ the sequence of events _read_ from an event journal. Before the microservice can _write_ an event to the journal it must _validate_ that the event can be applied to the current state. This validation may involve network or disk I/O.

### Resources

Each _resource collection_ e.g. `/posts` `/comments` and `/links` will usually have it's own event journal. The state of a given _resource_ e.g. `/posts/:id`, `/comments/:id` and `/links/:id` can be recreated by replaying events with a given _identifier_ in the respective journal. To recreate the state of an entire resource collection requires all events in a journal to be replayed. This can lead to reduced read performance so a separate query model is often maintained (see CQRS).

### Representations

In much the same way that the _representation_ e.g. `application/json` or `application/xml` of a resource could, and perhaps should, vary so could the representation of events. This is especially true if the content or structure of events changes over time. The representation may start of as something human readable, say JSON, and then transition to binary, say Protobuf, if or when performance becomes an issue.

### Implementation

To implement event sourcing in Scala then, at a minimum, the types `State`, `Event` and `EventJournal` need to be represented. The functions `Seq[Event] => Option[State]` and `(Event, Option[State]) => Future[Option[State]]` are also needed. These types and functions can be defined using generic traits:

###### CanReplay.scala
```scala
trait CanReplay[Event, State] {
  def replay(s: Seq[Event]): Option[State]
}
```

###### CanValidate.scala
```scala
trait CanValidate[Event, State] {
  def validate(e: Event, s: Option[State]): Future[Option[State]]
}
```

It would also be nice to support reading and writing multiple `Event` and `Identifier` representations:

###### CanRead.scala
```scala
trait CanRead[Model, Rep] {
  def read(a: Rep): Model
}
```

###### CanWrite.scala
```scala
trait CanWrite[Model, Rep] {
  def write(a: Model): Rep
}
```

With these generic traits it is possible to define the following generic `EventJournal`:

###### EventJournal.scala
```scala
trait EventJournal[Identifier, Event, IdentifierRep, EventRep] {
  def events(
      resourceId: Identifier)(implicit
      canWriteIdentifier: CanWrite[Identifier, IdentifierRep],
      canReadEvent: CanRead[Event, EventRep]): Future[Seq[Event]]

  def read[State](
      resourceId: Identifier)(implicit
      canReplay: CanReplay[Event, State],
      canWriteIdentifier: CanWrite[Identifier, IdentifierRep],
      canReadEvent: CanRead[Event, EventRep]): Future[Option[State]]

  def write[State](
      resourceId: Identifier,
      event: Event)(implicit
      canReplay: CanReplay[Event, State],
      canValidate: CanValidate[Event, State],
      canReadEvent: CanRead[Event, EventRep],
      canWriteEvent: CanWrite[Event, EventRep],
      canWriteIdentifier: CanWrite[Identifier, IdentifierRep]): Future[Option[State]]
}
```

And an example implementation using Slick:

###### SlickEventJournal.scala
```scala
class SlickEventJournal[
  Identifier,
  Event : ClassTag,
  IdentifierRep : JdbcType,
  EventRep : JdbcType](
    private val driver: JdbcDriver,
    private val database: Database)(implicit
    private val context: ExecutionContext)
  extends EventJournal[Identifier, Event, IdentifierRep, EventRep] with Initializable {

  private val eventName = implicitly[ClassTag[Event]].runtimeClass.getSimpleName
  private val underscoreEventName = CaseFormat.UPPER_CAMEL.to(CaseFormat.LOWER_UNDERSCORE, eventName)
  private val name = s"${underscoreEventName}_journal"

  import driver.api._

  private case class EventRow(resourceId: IdentifierRep, seqNo: Long, event: EventRep)

  private class EventsTable(tag: Tag) extends Table[EventRow](tag, name) {
    def resourceId = column[IdentifierRep]("resource_id")
    def seqNo = column[Long]("seq_no")
    def event = column[EventRep]("event")
    def * = (resourceId, seqNo, event) <> (EventRow.tupled, EventRow.unapply)
    def pk = primaryKey(s"${tableName}_pk", (resourceId, seqNo))
  }

  private val events = TableQuery[EventsTable]

  def events(
      resourceId: Identifier)(implicit
      canWriteIdentifier: CanWrite[Identifier, IdentifierRep],
      canReadEvent: CanRead[Event, EventRep]): Future[Seq[Event]] = {
    database.
      run(events.
        filter(e => e.resourceId === canWriteIdentifier.write(resourceId)).
        sortBy(e => e.seqNo).result).
      map(es => es.map(e => canReadEvent.read(e.event)).toVector)
  }

  def read[State](
      resourceId: Identifier)(implicit
      canReplay: CanReplay[Event, State],
      canWriteIdentifier: CanWrite[Identifier, IdentifierRep],
      canReadEvent: CanRead[Event, EventRep]): Future[Option[State]] = {
    database.
      run(events.
        filter(e => e.resourceId === canWriteIdentifier.write(resourceId)).
        sortBy(e => e.seqNo).
        result).
      map(es => es.map(e => canReadEvent.read(e.event)).toVector).
      map(canReplay.replay)
  }

  def write[State](
      resourceId: Identifier,
      event: Event)(implicit
      canReplay: CanReplay[Event, State],
      canValidate: CanValidate[Event, State],
      canReadEvent: CanRead[Event, EventRep],
      canWriteEvent: CanWrite[Event, EventRep],
      canWriteIdentifier: CanWrite[Identifier, IdentifierRep]): Future[Option[State]] = {
    val resourceEvents = events.filter(e => e.resourceId === canWriteIdentifier.write(resourceId))

    val isValid = resourceEvents.result.
      map(es => es.map(e => canReadEvent.read(e.event)).toVector).
      map(canReplay.replay).
      flatMap(state => DBIO.from(canValidate.validate(event, state)))

    val writeIfValid = isValid.flatMap { state =>
      resourceEvents.map(e => e.seqNo).max.result.
        map(o => o.map(_ + 1).getOrElse(0L)).
        flatMap(s => events +=
          EventRow(canWriteIdentifier.write(resourceId), s, canWriteEvent.write(event))).
        map(_ => state)
    }

    database.run(writeIfValid.transactionally)
  }

  override def init(): Future[Unit] = {
    database.run(MTable.getTables).flatMap { ts =>
      if (ts.map(t => t.name.name).exists(n => n == name)) {
        Future.successful(()) // NOP
      } else {
        database.run(events.schema.create)
      }
    }
  }
}
```

Using the `JdbcType` type class means that the `SlickEventJournal` can read and write identifier and event representations of type `String`, `UUID`, `Array[Byte]` and many more. This enables the support of binary and human readable representations for identifiers and events. The extensive use of implicits on methods makes for verbose method definitions, but also means that client code need only provide (define or import) a minimal set of implementations. In order to use the `SlickEventJournal` the client code must provide implementations for the type classes defined above. Here are example definitions that make use of Play's JSON library to generate event representations:

###### Post.scala
```scala
case class Post(id: UUID, body: String)

object Post {
  import play.api.libs.json._
  val MaxBody = 1024
  private val reads = Json.reads[Post].
    filter(p => p.body.length < MaxBody)
  private val writes = Json.writes[Post]
  implicit val format = Format(reads, writes)
}
```

###### PostEvent.scala
```scala
sealed trait PostEvent

case class PostCreated(id: UUID, body: String) extends PostEvent
case class PostUpdated(id: UUID, body: String) extends PostEvent
case class PostDeleted(id: UUID) extends PostEvent

object PostEvent {
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

  private def update(event: PostEvent, state: Option[Post]): Option[Post] = {
    event match {
      case e: PostCreated => Some(Post(e.id, e.body))
      case e: PostUpdated => state.map(p => p.copy(body = e.body))
      case e: PostDeleted => None
    }
  }

  implicit object CanReplayPostEvent extends CanReplay[PostEvent, Post] {
    override def replay(es: Seq[PostEvent]): Option[Post] = {
      @tailrec
      def loop(es: Seq[PostEvent], state: Option[Post]): Option[Post] = {
        es.headOption match {
          case Some(e) => loop(es.tail, update(e, state))
          case None => state
        }
      }
      loop(es, None)
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
```

A pleasant result of using type classes is that all of the methods defined in them are pure functions. More concretely, they are referentially transparent and, by definition, do not cause side-effects. This makes unit testing the `replay` and `validate` methods trivial. Testing the `SlickEventJournal` is slightly less trivial however. With that in mind here is one approach to testing the `SlickEventJournal` using Scalatest's new `AsyncWordSpec`:

###### SlickEventJournalSpec.scala
```scala
class SlickEventJournalSpec extends AsyncWordSpec with MustMatchers {
  import PostEvent._
  val uuid = UUID.randomUUID()
  val body = "Test body"
  val createEvent = PostCreated(uuid, body)
  val updateEvent = PostUpdated(uuid, body.reverse)
  val deleteEvent = PostDeleted(uuid)

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
```

### Final thoughts

The above code aims to strike a balance between compile time type-safety and ease of use. It could be made more type-safe, however, by using an event "pack" to ensure that all events are handled. This could be implemented using a HList. This would not prevent a bad actor (whether that be another microservice, DBA or failing disk) from corrupting the event journal. In addition the above code ignores snapshotting. That said it should be reasonable trivial to update `CanReplay` to implement a replay function along the lines of: `(Seq[Event], Option[State) => Option[State]`. It would also be possible to reduce the amount of boilerplate serialisation code using scala macros. In terms of future work it should be possible to create an `AkkaEventJournal` that makes use of `akka-actor`, `akka-cluster`, `akka-cluster-sharding` and `akka-persistence` for the persistence.
