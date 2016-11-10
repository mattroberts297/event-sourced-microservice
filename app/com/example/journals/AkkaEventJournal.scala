package com.example.journals

import scala.collection.immutable.Seq
import scala.concurrent.duration._
import scala.concurrent.Future
import scala.reflect.classTag
import scala.reflect.ClassTag
import scala.reflect.runtime.universe._

import akka.actor.ActorLogging
import akka.actor.ActorRef
import akka.actor.ActorSystem
import akka.actor.Props
import akka.actor.Status
import akka.cluster.sharding.ClusterSharding
import akka.cluster.sharding.ClusterShardingSettings
import akka.cluster.sharding.ShardRegion
import akka.cluster.sharding.ShardRegion.EntityId
import akka.event.LoggingReceive
import akka.pattern.pipe
import akka.pattern.ask
import akka.persistence.PersistentActor
import akka.util.Timeout
import com.example.io.CanRead
import com.example.io.CanReplay
import com.example.io.CanValidate
import com.example.io.CanWrite

// Hm, for Akka, it would appear the multi-param type classes need to be passed to ctor.
class AkkaEventJournal[Identifier, Event, EventRep : ClassTag, State](
    name: String)(implicit
    system: ActorSystem,
    canReplay: CanReplay[Event, State],
    canValidate: CanValidate[Event, State],
    canReadEvent: CanRead[Event, EventRep],
    canWriteEvent: CanWrite[Event, EventRep],
    canWriteIdentifier: CanWrite[Identifier, EntityId]) {

  private sealed trait Command {
    val id: Identifier
  }
  private case class Write(id: Identifier, event: Event) extends Command
  private case class Read(id: Identifier) extends Command



  private class EventSourcedActor extends PersistentActor {
    import context.dispatcher

    private var state: Option[State] = None

    override def receiveRecover: Receive = {
      // Yuk.
      case maybeRep if classTag[EventRep].runtimeClass.isInstance(maybeRep) =>
        maybeRep match {
          case rep: EventRep =>
            val event = canReadEvent.read(rep)
            state = canReplay.replay(Seq(event), state)
        }
    }

    override def receiveCommand: Receive = LoggingReceive {
      case Read(id) =>
        sender() ! state
      case Write(id, event) =>
        val replyTo = sender()
        canValidate.validate(event, state).pipeTo(self)
        context.become(receiveState(event, replyTo))
    }

    def receiveState(event: Event, replyTo: ActorRef): Receive = LoggingReceive {
      case o: Option[State] =>
        replyTo ! state
        persist(canWriteEvent.write(event)) { rep =>
          state = o
          replyTo ! state
          context.become(receiveCommand)
          unstashAll()
        }
      case Status.Failure(t) =>
        replyTo ! Status.Failure(t)
        context.become(receiveCommand)
        unstashAll()
      case _ =>
        stash()
    }

    override def persistenceId: String = s"${name}-${self.path.name}"
  }

  private object EventSourcedActor {
    val idExtractor: ShardRegion.ExtractEntityId = {
      case c: Command => (canWriteIdentifier.write(c.id), c)
    }

    val shardResolver: ShardRegion.ExtractShardId = {
      // TODO Put 50 in configuration.
      case c: Command => (math.abs(c.id.hashCode) % 50).toString
    }
  }

  private val region: ActorRef = ClusterSharding(system).start(
    typeName = name,
    entityProps = Props(new EventSourcedActor),
    settings = ClusterShardingSettings(system),
    extractEntityId = EventSourcedActor.idExtractor,
    extractShardId = EventSourcedActor.shardResolver)

  private implicit val timeout = Timeout(30 seconds)

  def write(id: Identifier, event: Event): Future[Option[State]] = {
    import system.dispatcher
    (region ? Write(id, event)).map { case o: Option[State] => o }
  }

  def read(id: Identifier): Future[Option[State]] = {
    import system.dispatcher
    (region ? Read(id)).map { case o: Option[State] => o }
  }
}
