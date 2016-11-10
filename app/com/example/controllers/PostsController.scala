package com.example.controllers

import play.api.mvc._
import java.util.UUID
import javax.inject.Inject

import scala.concurrent.ExecutionContext

import com.example.commands._
import com.example.repos.PostsRepo
import play.api.libs.json.Json

class PostsController @Inject() private[controllers](
  private val repo: PostsRepo)(
  implicit private val context: ExecutionContext) extends Controller {

  def create = Action.async(parse.tolerantJson) { request =>
    val CreatePost(id, body) = request.body.as[CreatePost]
    repo.create(id, body).map(_ => Created)
  }

  def read(id: UUID) = Action.async {
    repo.read(id).map(o => o.map(p => Ok(Json.toJson(p))).getOrElse(NotFound))
  }

  def update(id: UUID) = Action.async(parse.tolerantJson) { request =>
    val UpdatePost(body) = request.body.as[UpdatePost]
    repo.update(id, body).map(_ => Ok)
  }

  def delete(id: UUID) = Action.async {
    repo.delete(id).map(_ => NoContent)
  }
}
