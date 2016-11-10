package com.example.journals

import scala.collection.immutable._
import scala.concurrent.Future

import com.example.io.CanRead
import com.example.io.CanReplay
import com.example.io.CanValidate
import com.example.io.CanWrite

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
