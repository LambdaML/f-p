package baby_spark.rdd

import java.lang.System
import scala.language.implicitConversions

import scala.collection.immutable.TreeMap
import scala.collection.generic._

import scala.spores._
import scala.pickling._
import Defaults._

import scala.concurrent.{Future, ExecutionContext, Await}
import scala.concurrent.duration.Duration

import SporePickler._

import scala.io.Source

import silt._


object RDD {
  // Trick from https://stackoverflow.com/questions/3225675/can-i-pimp-my-library-with-an-analogue-of-traversablelike-map-that-has-nicely
  type CanBuildTo[Elem, C] = CanBuildFrom[Nothing, Elem, C]

  def apply[T, S <: Traversable[T]](silos: Seq[SiloRef[S]]): RDD[T, S] =
    RDD(silos, None)

  def apply[T, S <: Traversable[T], A]
    (silos: Seq[SiloRef[S]], partitioner: Option[Partitioner[A]]): RDD[T, S] =
    RDD(silos, silos.map(_.host), partitioner)

  def apply[T, S <: Traversable[T], A](silos: Seq[SiloRef[S]], hosts: Seq[Host],
    partitioner: Option[Partitioner[A]]): RDD[T, S] = {
      val lPartitioner = partitioner
      new RDD[T, S](silos, hosts){
        type P = A
        override val partitioner = lPartitioner
      }
    }

  // Future isn't needed if instead the creation of a Silo returns a SiloRef directly
  def fromTextFile(filename: String, host: Host)(implicit system: SiloSystem,
    ec: ExecutionContext): Future[RDD[String, List[String]]] = {
    system.fromFun(host)(spore {
      val fl = filename
      _: Unit => {
        val lines = Source.fromFile(fl).mkString.split('\n').toList
        new LocalSilo[List[String]](lines)
      }
    }).map { x => RDD(List(x)) }
  }

  def fromTextFile(filename: String, hosts: Seq[Host])
    (implicit system: SiloSystem, ec: ExecutionContext): Future[RDD[String, List[String]]] = {

    val silos = hosts.zipWithIndex.map({ case (h, index) => {
      system.fromFun(h)(spore {
        val fl = filename
        val i = index
        val total = hosts.length
        _: Unit => {
          val lines = Source.fromFile(fl).mkString.split('\n')
          val splitting = Math.ceil(1.0 * lines.length/total).toInt
          val newLines = lines.grouped(splitting).toList
          new LocalSilo[List[String]](newLines(i).toList)
        }
      })
    }})
    Future.sequence(silos).map { x => RDD(x) }
  }

  def partition[T, S <: Traversable[T]]
    (silos: Seq[SiloRef[S]])(partitioner: Partitioner[T])
    (implicit cbf: CanBuildTo[T, S]): Seq[SiloRef[S]] = {

    def partF(partition: Int)(x: T): Boolean = partitioner.getPartition(x) == partition

    def shuffleSilo(silo1: SiloRef[S], silo2: SiloRef[S], partition: Int): SiloRef[S] = {

      val f = partF(partition) _
      silo2.flatMap(spore {
        val ls1 = silo1
        val lPartF = f
        val lcbf = cbf()
        s2 => {
          ls1.apply(spore {
            val ls2 = s2.filter(lPartF)
            val llPartF = lPartF
            val builder = lcbf
            s1 => {
              builder ++= s1
              builder ++= ls2
              builder.result()
            }
          })
        }
      })
    }

    val newSilos = silos.zipWithIndex.map { case (s1, index) =>
      val f = partF(index) _
      val filteredSilo = s1.apply(spore {
          val lPartF = f
          val builder = cbf()
          x => {
            builder ++= x.filter(lPartF)
            builder.result()
          }
        })
      silos.filter(_ != s1).fold(filteredSilo) {
        (currentSilo, otherSilo) => shuffleSilo(currentSilo, otherSilo, index)
      }
    }

    newSilos
  }

  implicit def mapRDD[K, V, S <: Traversable[(K, V)]](rdd: RDD[(K, V), S]): MapRDD[K, V, S] = {
    new MapRDD[K, V, S](rdd.silos, rdd.hosts){
      type P = rdd.P
      override val partitioner = rdd.partitioner
    }
  }
}

class RDD[T, S <: Traversable[T]] private[rdd](
  val silos: Seq[SiloRef[S]],
  val hosts: Seq[Host]) {

  import RDD._

  type P

  val partitioner: Option[Partitioner[P]] = None

  def map[B, V <: Traversable[B]](f: Spore[T, B])(implicit cbt: CanBuildTo[B, V]): RDD[B, V] = {
    RDD(silos.map {
      s => s.apply[V](spore {
        val localFunc = f
        implicit val lCbt = cbt
        content => {
          content.map[B, V](localFunc)(collection.breakOut(lCbt))
        }
      })
    })
  }

  def filter(f: Spore[T, Boolean])(implicit cbf: RDD.CanBuildTo[T, S]): RDD[T, S] = {
    val resList = silos.map {
      s => s.apply[S](spore {
        val lf = f
        val lcbf = cbf
        content => {
          val builder = lcbf()
          content.foreach { elem => {
            if (lf(elem)) {
              builder += elem
            }
          }}
          builder.result()
        }
      })
    }
    RDD(resList, partitioner)
  }

  def groupBy[K, IS <: Traversable[T], RS <: Traversable[(K, IS)]](f: Spore[T, K])
    (implicit cbf1: CanBuildTo[T, IS],
      cbf2: CanBuildTo[(K, IS), RS]): MapRDD[K, IS, RS] = {
    val resList = silos.map {
      s => s.apply[RS](spore {
        val lf = f
        val lcbf1 = cbf1
        val lcbf2 = cbf2
        content => {
          val res0 = content.groupBy(lf)
          val b1 = lcbf1()
          val b2 = lcbf2()
          b2.sizeHint(res0)
          res0.foreach{case (k, vals) => {
            b1 ++= vals
            b2 += (k -> b1.result())
            b1.clear()
          }}
          b2.result()
        }
      })
    }
    MapRDD[K, IS, RS](resList, hosts)
  }

  def flatMap[B, V <: Traversable[B]](f: Spore[T, Seq[B]])
    (implicit cbt1: CanBuildTo[B, V]): RDD[B, V] = {
    val resList = silos.map {
      s => s.apply[V](spore {
        val func = f
        implicit val lcbt1 = cbt1
        content => {
          content.flatMap[B, V](func)(collection.breakOut(lcbt1))
        }
      })
    }
    RDD(resList)
  }

  // TODO: handle the various case with the partitioner
  def union[B](other : RDD[T, S]): RDD[T, S] = RDD(silos ++ other.silos)

  def reduce(f: Spore2[T, T, T])(implicit ec: ExecutionContext): T = {
    val resList = silos.map {
      s => s.apply[T](spore {
        val reducer = f
        content => {
          content.reduce(reducer)
        }
      }).send()
    }
    val res = Future.sequence(resList)
    val res1 = Await.result(res, Duration.Inf)
    res1.reduce(f)
  }

  def cache()(implicit ec: ExecutionContext): RDD[T, S] = {
    val res = Future.sequence(silos.map(_.cache()))
    RDD(Await.result(res, Duration.Inf))
  }

  def collect()(implicit ec: ExecutionContext, cbf: CanBuildTo[T, S]): S = {
    Await.result(Future.sequence(silos.map {
      s => s.send()
    }), Duration.Inf).foldLeft(cbf())((builder, elem) => {
      builder ++= elem
      builder
    }).result()
  }

  def count()(implicit ec: ExecutionContext): Long = {
    val resList = silos.map {
      s => s.apply[Long](spore {
        content => content.size.longValue
      }).send()
    }
    val res = Future.sequence(resList)
    val res1 = Await.result(res, Duration.Inf)
    res1.reduce(_ + _)
  }

  // join[(K, (V, W)), (K, W), S2, FS](other)
  // def join[X, W, S2 <: Traversable[W], FS <: Traversable[X]]
  //   (other: RDD[W, S2])
  //   (implicit ec: ExecutionContext,
  //     cbf: CanBuildTo[X, FS]): RDD[X, FS] = {


  //   RDD(res, hosts, part)
  // }

  def partition(partitioner: Partitioner[T])(implicit cbf: CanBuildTo[T, S]): RDD[T, S] = {
    RDD(RDD.partition[T, S](silos)(partitioner), hosts, Some(partitioner))
    }
}
