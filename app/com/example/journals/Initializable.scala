package com.example.journals

import scala.concurrent.Future

trait Initializable {
  def init(): Future[Unit]
}
