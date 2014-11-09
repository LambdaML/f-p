package silt
package netty

import io.netty.bootstrap.ServerBootstrap
import io.netty.channel.{EventLoopGroup, ChannelInitializer, ChannelOption}
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioServerSocketChannel

import java.util.concurrent.{BlockingQueue, LinkedBlockingQueue}


class Server(port: Int, system: SystemImpl) {

  def run(): Unit = {
    println(s"SERVER: starting on port $port...")

    val bossGroup: EventLoopGroup = new NioEventLoopGroup
    val workerGroup: EventLoopGroup = new NioEventLoopGroup

    // Queue for transferring messages from the netty event loop to the server's event loop
    val queue: BlockingQueue[HandleIncoming] = new LinkedBlockingQueue[HandleIncoming]()
    // Thread that blocks on the queue
    val receptorRunnable = new ReceptorRunnable(queue, system)
    val receptorThread = new Thread(receptorRunnable)
    receptorThread.start()

    try {
      val b = new ServerBootstrap
      b.group(bossGroup, workerGroup)
       .channel(classOf[NioServerSocketChannel])
       .childHandler(new ChannelInitializer[SocketChannel]() {
          override def initChannel(ch: SocketChannel): Unit = {
            ch.pipeline().addLast(new ServerHandler(system, queue))
          }
       })
       .option(ChannelOption.SO_BACKLOG.asInstanceOf[ChannelOption[Any]], 128)
       .childOption(ChannelOption.SO_KEEPALIVE.asInstanceOf[ChannelOption[Any]], true)

      // Bind port and start accepting incoming connections
      b.bind(port).sync()

      system.latch.await()
      print(s"SERVER: shutting down...")

      receptorRunnable.shouldTerminate = true
      receptorThread.interrupt()
      receptorThread.join()

      println("DONE")
    } finally {
      workerGroup.shutdownGracefully()
      bossGroup.shutdownGracefully()
    }
  }
}

object Server {

  def main(args: Array[String]): Unit = {
    val port =
      if (args.length > 0) Integer.parseInt(args(0))
      else 8080

    val system = new SystemImpl

    new Server(port, system).run()
  }

}
