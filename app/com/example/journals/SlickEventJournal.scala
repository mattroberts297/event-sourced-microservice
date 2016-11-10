package com.example.journals

import javax.inject.Inject

import scala.collection.immutable._
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.reflect.ClassTag

import com.google.common.base.CaseFormat
import com.example.io.CanRead
import com.example.io.CanReplay
import com.example.io.CanValidate
import com.example.io.CanWrite
import slick.driver.JdbcDriver
import slick.jdbc.JdbcBackend.Database
import slick.jdbc.JdbcType
import slick.jdbc.meta.MTable

class SlickEventJournal[
  Identifier,
  Event : ClassTag,
  IdentifierRep : JdbcType,
  EventRep : JdbcType] @Inject() private[journals](
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
