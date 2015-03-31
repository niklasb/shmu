package shmu

object Main {
  val defaultOpts = Map(
    'port -> 9191,
    'host -> "127.0.0.1",
    'prompt -> "$ "
  )

  val usage =
      s"""|Usage: shmu -p PORT -h HOST cmd
          |
          |Defaults:
          |  PORT ${defaultOpts('port)}
          |  HOST ${defaultOpts('host)}""".stripMargin

  def usageAndExit() {
    System.err.println(usage)
    System.exit(2)
  }

  def main(args: Array[String]) {
    if (args.length < 1)
      usageAndExit()
    var opts = new Opts()
    var mCmd : Option[String] = None
    def parseOpts(rest : List[String]) {
      rest match {
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
