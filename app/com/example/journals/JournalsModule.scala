package com.example.journals

import java.util.UUID

import scala.reflect.ClassTag

import com.google.inject.AbstractModule
import com.google.inject.TypeLiteral
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import com.example.events.PostEvent
import slick.driver.JdbcDriver
import slick.jdbc.JdbcBackend.Database

class JournalsModule extends AbstractModule {
  override def configure(): Unit = {
    val config = ConfigFactory.load()

    def urlToDriver(url: String): JdbcDriver = {
      if (url.startsWith("jdbc:h2")) slick.driver.H2Driver
      else if (url.startsWith("jdbc:sqlite")) slick.driver.SQLiteDriver
      else if (url.startsWith("jdbc:derby")) slick.driver.DerbyDriver
      else if (url.startsWith("jdbc:postgresql")) slick.driver.PostgresDriver
      else if (url.startsWith("jdbc:mysql")) slick.driver.MySQLDriver
      else throw new RuntimeException(s"No driver for url: $url")
    }

    bind(classOf[Config]).toInstance(config)

    bind(classOf[Database]).toInstance(Database.forConfig(s"slick.events.db"))

    val driver = urlToDriver(config.getString("slick.events.db.url"))
    bind(classOf[JdbcDriver]).toInstance(driver)
    bind(new TypeLiteral[slick.jdbc.JdbcType[UUID]]() {}).toInstance(driver.api.uuidColumnType)
    bind(new TypeLiteral[slick.jdbc.JdbcType[String]]() {}).toInstance(driver.api.stringColumnType)

    bind(new TypeLiteral[ClassTag[PostEvent]] {}).toInstance(ClassTag(classOf[PostEvent]))

    bind(new TypeLiteral[EventJournal[UUID, PostEvent, UUID, String]] {}).
      to(new TypeLiteral[SlickEventJournal[UUID, PostEvent, UUID, String]] {}).
      asEagerSingleton()

    bind(classOf[Initializable]).
      to(new TypeLiteral[SlickEventJournal[UUID, PostEvent, UUID, String]] {}).
      asEagerSingleton()

    bind(classOf[Initializer]).to(classOf[FireAndForgetInitializer]).asEagerSingleton()
  }
}
