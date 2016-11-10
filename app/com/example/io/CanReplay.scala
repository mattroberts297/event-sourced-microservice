package com.example.io

import scala.collection.immutable._

trait CanReplay[Event, State] {
  def replay(s: Seq[Event]): Option[State]
  def replay(es: Seq[Event], s: Option[State]): Option[State]
}
