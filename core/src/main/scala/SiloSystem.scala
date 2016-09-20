package silt

import scala.pickling._
import Defaults._

import scala.spores._

import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration
import scala.collection.mutable
import scala.collection.concurrent.TrieMap

import java.util.concurrent.atomic.AtomicInteger


object SiloSystem {
  def apply(): SiloSystem =
    new silt.actors.SystemImpl
    // new silt.netty.SystemImpl
}

trait SiloSystem {
  self: SiloSystemInternal =>

  def fromClass[T](clazz: Class[_], host: Host): Future[SiloRef[T]] =
    initRequest[T, InitSilo](host, { (refId: Int) =>
      println(s"fromClass: register location of $refId")
      InitSilo(clazz.getName(), refId)
    })

  def fromFun[T](host: Host)(fun: Spore[Unit, LocalSilo[T]])(implicit p: Pickler[InitSiloFun[T]]): Future[SiloRef[T]] =
    initRequest[T, InitSiloFun[T]](host, { (refId: Int) =>
      println(s"fromFun: register location of $refId")
      InitSiloFun(fun, refId)
    })

  // the idea is that empty silos can only be filled using pumpTos.
  // we'll detect issues like trying to fill a silo that's been constructed
  // *not* using pumpTo at runtime (before running the topology, though!)
  //
  // to push checking further to compile time, could think of
  // using phantom types to detect whether a ref has been target of non-pumpTo!
  def emptySilo[T](host: Host): SiloRef[T] = {
    val refId = refIds.incrementAndGet()
    location += (refId -> host)
    new graph.EmptySiloRef[T](refId, host)(this)
  }

  def waitUntilAllClosed(): Unit
  def waitUntilAllClosed(tm1: FiniteDuration, tm2: FiniteDuration): Unit
}

// abstracts from different network layer backends
private[silt] trait SiloSystemInternal {

  // map ref ids to host locations
  val location: mutable.Map[Int, Host] = new TrieMap[Int, Host]

  val seqNum = new AtomicInteger(10)

  val refIds = new AtomicInteger(0)

  val emitterIds = new AtomicInteger(0)

  // idea: return Future[SelfDescribing]
  def send[T <: ReplyMessage : Pickler](host: Host, msg: T): Future[Any]

  def initRequest[T, V <: ReplyMessage : Pickler](host: Host, mkMsg: Int => V): Future[SiloRef[T]]

}
