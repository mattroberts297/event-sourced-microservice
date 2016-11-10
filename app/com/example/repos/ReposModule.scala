package com.example.repos

import com.google.inject.AbstractModule

class ReposModule extends AbstractModule {
  override def configure(): Unit = {
    bind(classOf[PostsRepo]).to(classOf[EventSourcedPostsRepo])
  }
}
