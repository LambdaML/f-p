package baby_spark


import scala.concurrent._
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global

import scala.collection.immutable.TreeMap

import scalaz._
import Scalaz._

import scala.spores._
import scala.pickling._
import Defaults._
import SporePickler._

import baby_spark.rdd._

import com.typesafe.config.ConfigFactory

import silt._
import silt.actors._

object RDDExample {

  implicit def TreeMapSemigroup[K, V : Semigroup](implicit ordering: scala.Ordering[K]): Semigroup[TreeMap[K, V]] = new Semigroup[TreeMap[K, V]] with std.MapInstances with std.MapFunctions {
    def zero = new TreeMap[K, V]()(ordering)
    // Repetition of scalaz.std.Map: method apppend defined in mapMonoid
    override def append(m1: TreeMap[K, V], m2: => TreeMap[K, V]): TreeMap[K, V] = {
      val m2Instance = m2

      val (from, to, semigroup) = {
        if (m1.size > m2Instance.size) (m2Instance, m1, Semigroup[V].append(_:V, _:V))
        else (m1, m2Instance, (Semigroup[V].append(_: V, _: V)).flip)
      }

      from.foldLeft(to) {
        case (to, (k, v)) => to.updated(k, to.get(k).map(semigroup(_, v)).getOrElse(v))
      }
    }
  }

  def joinExample(system: SystemImpl, hosts: Seq[Host]): Unit = {
    implicit val sm = system
    val content = Await.result(RDD.fromTextFile("data/data.txt", hosts(0)), Duration.Inf)
    val lorem = Await.result(RDD.fromTextFile("data/lorem.txt", hosts(1)), Duration.Inf)
    val lorem2 = Await.result(RDD.fromTextFile("data/lorem.txt", hosts(2)), Duration.Inf)

    val contentWord = content.flatMap(line => {
      line.split(' ').toList
    }).map(word => (word.length, word))

    val loremWord = lorem.flatMap(line => {
      line.split(' ').toList
    }).map(word => (word.length, word))

    val loremWord2 = lorem2.flatMap(line => {
      line.split(' ').toList
    }).map(word => (word.length, word)).groupByKey[Set, TreeMap]()

    val res = contentWord.join[Set, TreeMap](loremWord).union(loremWord2).collectMap()

    println(s"Result... ${res}")
  }

  def mapExample(system: SystemImpl, hosts: Seq[Host]): Unit = {
    implicit val sm = system
    val content = Await.result(RDD.fromTextFile("data/data.txt", hosts(0)), Duration.Inf)
    val lineLength = content.map(line => line.length)
    val twiceLength = lineLength.map(_ * 2).collect()
    val twiceLength2 = lineLength.map(_ * 2).collect()
    println(s"line length1: ${twiceLength} | line length 2: ${twiceLength2}")
    val bigLines = lineLength.filter(l => l > 30).count()
    println(s"There is ${bigLines} lines bigger than 30 characters")

    val sizeLine = content.map[(Int, List[String]), TreeMap[Int, List[String]]](line => {
      val words = line.split(' ').toList
      (words.length, words)
    }).collect()

    println(s"Results.. ${sizeLine}")
  }

  def externalDependencyExample(system: SystemImpl): Unit = {
    val host = Host("127.0.0.1", 8090)

    trait WithName {
      def name: String
    }
    case class Person(name: String, age: Int) extends WithName

    val persons = for(i <- 0 until 100) yield Person(s"foo: {i}", i)

    val silo = Await.result(system.fromFun(host)(spore {
      val lPersons = persons
      _: Unit => {
        new LocalSilo[Person, List[Person]](lPersons.toList)
      }
    }), 30.seconds)

    println("Created externalDep local silo")

    def caseClass(): Unit = {

      val resSilo = silo.apply[String, List[String]](spore {
        ps => {
          ps.map(_.name)
        }
      }).send()

      val res = Await.result(resSilo, 10.seconds)
      println(s"Result of caseClass: ${res.size}")
    }

    def genericClass(): Unit = {
      class Gen[T <: WithName](val content: T) {
        def toName(): String = content.name
      }

      val sp = spore[List[Person], List[Gen[Person]]] {
        ps => {
          ps.map(new Gen(_))
        }
      }

      val resSilo = silo.apply[Gen[Person], List[Gen[Person]]](sp).send()

      val res = Await.result(resSilo, 10.seconds)
      println(s"Result of genClass: ${res.size}")
    }

    println("Running case class")
    caseClass()
    println("Running genClass")
    genericClass()
  }


  def main(args: Array[String]): Unit = {
    implicit val system = new SystemImpl
    val nActors = 3
    val started = system.start(nActors)
    val hosts = for (i <- 0 to nActors) yield { Host("127.0.0.1", 8090 + i)}

    Await.ready(started, 1.seconds)

    println("Running examples")
    externalDependencyExample(system)

    system.waitUntilAllClosed(30.seconds, 30.seconds)
  }
}
