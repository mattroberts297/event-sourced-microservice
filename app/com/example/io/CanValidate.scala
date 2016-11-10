package com.example.io

import scala.concurrent.Future

trait CanValidate[Event, State] {
  def validate(e: Event, s: Option[State]): Future[Option[State]]
}
