package shmu

object Main {
  val usage =
    s"""|Usage: shmu [options] cmd
        |
        | `cmd' is an arbitrary command that will yield a shell.
        |
        |Options:
        |  -h/--host  STRING   The address to bind to (default: "${new Opts().host}")
        |  -p/--port  INT      The port to listen on (default: ${new Opts().port})
        |  --prompt   STRING   The prompt of the remote shell (default: "${new Opts().remotePrompt}")
        |  --debug/-d          Enable debug tracing
        """.stripMargin

  def usageAndExit() {
    System.err.println(usage)
    System.exit(2)
  }

  def enableDebug() {
    System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "debug")
  }

  def main(args: Array[String]) {
    if (args.length < 1)
      usageAndExit()
    var opts = new Opts()
    var mCmd : Option[String] = None
    def parseOpts(rest : List[String]) {
      rest match {
        case "--debug" :: xs =>
          enableDebug()
          parseOpts(xs)
        case "-d" :: xs =>
          enableDebug()
          parseOpts(xs)
        case "--prompt" :: value :: xs =>
          opts.remotePrompt = value
          parseOpts(xs)
        case "--port" :: value :: xs =>
          opts.port = value.toInt
          parseOpts(xs)
        case "-p" :: value :: xs =>
          opts.port = value.toInt
          parseOpts(xs)
        case "--host" :: value :: xs =>
          opts.host = value
          parseOpts(xs)
        case "-h" :: value :: xs =>
          opts.host = value
          parseOpts(xs)
        case x :: xs =>
          mCmd match {
            case None => mCmd = Some(x)
            case _ => usageAndExit()
          }
          parseOpts(xs)
        case Nil => opts
      }
    }
    parseOpts(args.toList)
    mCmd match {
      case Some(cmd) =>
        new Shmu(cmd, opts).run
      case _ =>
        System.err.println("No command given!")
        usageAndExit()
    }
  }
}
