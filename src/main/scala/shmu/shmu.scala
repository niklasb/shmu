package shmu

import akka.actor._
import akka.actor.ActorDSL._
import akka.actor.ActorSystem
import akka.io.{ IO, Tcp }
import akka.io.Inet.SO.ReuseAddress
import akka.pattern.ask
import akka.util.{ ByteString, Timeout }
import akka.util.Timeout
import base64._
import java.io._
import java.net._
import java.util.concurrent.TimeUnit
import org.slf4j._
import scala.collection.mutable.ArrayBuffer
import scala.io.Source
import scala.sys.process._

class Opts {
  var clientPrompt = "(prompt) "
  var remotePrompt = "$ "
  var host = "127.0.0.1"
  var port = 9191
}

class Shmu(cmd: String, opts: Opts) {
  type StreamId = Int
  def encodeUtf8(s: String) = s.getBytes("UTF-8")
  def decodeUtf8(s: Array[Byte]) = new String(s, "UTF-8")

  val shellPayload = """PS='%s'; while true; do echo -n "$PS"; read cmd; eval "$cmd"; done"""
  val separator = "----"

  def mkShellMultiplexer(): String = {
    val source = Source.fromURL(getClass.getResource("/shmu.sh"))
    val b64 = new String(base64.Encode(source.mkString))
    "mkdir -p /tmp; " +
    """read shmu; echo "$shmu" | base64 -d > /tmp/shmu.sh; """ +
    "sh /tmp/shmu.sh\n" + b64 + "\n"
  }

  // muxer -> conn handler
  case class ReqExec(cmd: String)
  case class ReqKill(streamId: StreamId)
  case class ReqExit()
  case class ReqSend(streamId: StreamId, data: Array[Byte])
  // input loop -> muxer
  case class RemoteFinished(streamId: StreamId, exitCode: Int)
  case class RemoteData(num: Int, data: Array[Byte])
  // muxer -> conn handler
  case class RespExec(streamId: StreamId)
  case class Finished(exitCode: Int)
  case class GotData(data: Array[Byte])

  class Muxer(out: OutputStream) extends Actor {
    val logger = LoggerFactory.getLogger("Muxer")
    var curIndex = 0
    var clients = Map[StreamId, ActorRef]()
    def send(cmd: Any*) {
      val data = encodeUtf8(cmd.map(_.toString).mkString("", "\n", "\n"))
      logger.debug(s"Sending data ${ByteString(data)}")
      out.write(data)
      out.flush()
    }
    def receive = {
      case ReqExec(cmd) =>
        curIndex += 1
        logger.info(s"Got ReqExec($cmd), assigning stream ID $curIndex")
        send("exec", cmd)
        clients += curIndex -> sender()
        sender() ! RespExec(curIndex)
      case ReqKill(streamId) =>
        logger.info(s"Got ReqKill($streamId)")
        send("kill", streamId)
      case ReqExit() =>
        logger.info(s"Got ReqExit")
        // TODO do something meaningful, like kill everything
        send("exit")
      case ReqSend(streamId, data) =>
        logger.debug(s"Got ReqSend($streamId, ${ByteString(data)})")
        send("data", streamId, decodeUtf8(base64.Encode(data)))
      case RemoteFinished(streamId, exitCode) =>
        logger.info(s"Got RemoteFinished($streamId, $exitCode)")
        val Some(other) = clients.get(streamId)
        other ! Finished(exitCode)
      case RemoteData(streamId, data) =>
        logger.debug(s"Got RemoteData($streamId, ${ByteString(data)})")
        val Some(other) = clients.get(streamId)
        other ! GotData(data)
      case msg =>
        logger.error(s"Unknown message: $msg")
    }
  }
  object Muxer {
    def props(out: OutputStream): Props =
      Props(new Muxer(out))
  }

  class ConnHandler(conn: ActorRef, muxer: ActorRef) extends Actor {
    val logger = LoggerFactory.getLogger("ConnHandler")
    context watch conn  // if conn dies, not worth living anymore:(
    implicit val timeout = Timeout(10, TimeUnit.SECONDS)
    muxer ! ReqExec("""read cmd; eval "$cmd"""")
    def withStreamId(streamId: StreamId): Receive = {
      case Finished(exitCode) =>
        logger.debug(s"Stream $streamId finished with exit code $exitCode")
        self ! Tcp.Close
      case GotData(data) =>
        logger.debug(s"Data stream $streamId -> user: ${ByteString(data)}")
        conn ! Tcp.Write(ByteString(data))
      case Tcp.Received(data) =>
        logger.debug(s"Data user -> stream $streamId: $data")
        muxer ! ReqSend(streamId, data.toArray[Byte])
      case _: Tcp.ConnectionClosed =>
        logger.debug(s"Client closed connection to stream $streamId")
        muxer ! ReqKill(streamId)
      case Terminated =>
        logger.debug(s"Stream $streamId terminated")
    }
    def receive = {
      case RespExec(streamId) =>
        logger.debug(s"Got stream ID $streamId")
        muxer ! ReqSend(streamId, encodeUtf8(shellPayload.format(opts.clientPrompt) + "\n"))
        context.become(withStreamId(streamId))
    }
  }
  object ConnHandler {
    def props(conn: ActorRef, muxer: ActorRef): Props =
      Props(new ConnHandler(conn, muxer))
  }

  class Server(muxer: ActorRef) extends Actor {
    val logger = LoggerFactory.getLogger("Server")
    import context.system

    IO(Tcp) ! Tcp.Bind(self, new InetSocketAddress(opts.host, opts.port),
                        options = List(new ReuseAddress(true)))
    def receive = {
      case Tcp.CommandFailed(_: Tcp.Bind) =>
        logger.error("Could not bind, exiting")
        context stop self
      case Tcp.Connected(remote, local) =>
        logger.info(s"Got connection from $remote, spawning handler")
        val handler = context.actorOf(ConnHandler.props(sender, muxer))
        sender ! Tcp.Register(handler)
      case Tcp.Bound(_) =>
      case msg =>
        logger.error(s"Unknown message: $msg")
    }
  }
  object Server {
    def props(muxer: ActorRef): Props =
      Props(new Server(muxer))
  }

  val logger = LoggerFactory.getLogger("Shmu")

  def waitFor(out: InputStream, s: Array[Byte]) {
    var buf = ArrayBuffer[Byte]()
    while (!buf.endsWith(s)) {
      val nxt = out.read()
      if (nxt < 0)
        throw new Exception("Unexpected EOF")
      buf += nxt.toByte
    }
  }

  def waitForPrompt(out: InputStream) {
    logger.info(s"Waiting for remote prompt '${opts.remotePrompt}'...")
    waitFor(out, encodeUtf8(opts.remotePrompt))
  }

  def waitForMultiplexer(out: InputStream) {
    waitFor(out, encodeUtf8(separator + "\n"))
  }

  def inputLoop(lines: Iterator[String], muxer: ActorRef) {
    while (lines.hasNext) {
      val cmd = lines.next()
      logger.debug(s"Got new line from other side: $cmd")
      cmd match {
        case "data" =>
          val streamId = lines.next().toInt
          val b64 = lines.takeWhile(_ != separator).mkString("")
          val Right(data) = base64.Decode(b64)
          muxer ! RemoteData(streamId, data)
        case "finish" =>
          val streamId = lines.next().toInt
          val exitCode = lines.next().toInt
          muxer ! RemoteFinished(streamId, exitCode)
        case cmd =>
          throw new Exception(s"Unexpected command: $cmd")
      }
    }
  }

  def run() {
    var mIn: Option[OutputStream] = None
    logger.info("Starting subprocess")
    Seq("sh", "-c", cmd).run(new ProcessIO(
      in => { mIn = Some(in) },
      out => {
        waitForPrompt(out)
        logger.info("Sending multiplexer payload...")
        val Some(in) = mIn
        in.write(encodeUtf8(mkShellMultiplexer))
        in.flush()
        waitForMultiplexer(out)
        logger.info(s"Got it, starting TCP server on ${opts.host}:${opts.port}")
        implicit val system = ActorSystem("shmu-tcp")
        val muxer = system.actorOf(Muxer.props(in))
        val server = system.actorOf(Server.props(muxer))
        inputLoop(scala.io.Source.fromInputStream(out).getLines(), muxer)
      },
      err => {
        for (line <- scala.io.Source.fromInputStream(err).getLines)
          logger.warn(s"Unexpected data from stderr: $line")
      }
    ))
  }
}
