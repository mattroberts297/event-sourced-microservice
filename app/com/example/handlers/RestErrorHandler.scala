package com.example.handlers

import play.api.http.HttpErrorHandler
import play.api.mvc._
import play.api.mvc.Results._
import scala.concurrent._
import javax.inject.Singleton

import com.example.exceptions.ConflictException
import org.slf4j.LoggerFactory;

@Singleton
class RestErrorHandler extends HttpErrorHandler {
  val log = LoggerFactory.getLogger(this.getClass)

  def onClientError(request: RequestHeader, statusCode: Int, message: String) = Future.successful {
    Status(statusCode)
  }

  def onServerError(request: RequestHeader, exception: Throwable) = Future.successful {
    exception match {
      case c: ConflictException => Conflict
      case _ =>
        log.error(exception.getMessage, exception)
        InternalServerError
    }
  }
}
