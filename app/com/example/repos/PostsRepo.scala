package com.example.repos

import java.util.UUID

import scala.concurrent.Future

import com.example.views.Post

trait PostsRepo {
  def create(id: UUID, body: String): Future[Option[Post]]
  def read(id: UUID): Future[Option[Post]]
  def update(id: UUID, body: String): Future[Unit]
  def delete(id: UUID): Future[Unit]
}
