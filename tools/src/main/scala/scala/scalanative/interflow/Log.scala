package scala.scalanative
package interflow

trait Log { self: Interflow =>
  private def show: Boolean =
    false

  def in[T](msg: String)(f: => T): T = {
    if (show) { log(msg) }
    context ::= msg
    try {
      val start = System.nanoTime
      val res   = f
      val end   = System.nanoTime
      if (show) { log(s"done $msg (${(end - start) / 1000000D})") }
      res
    } catch {
      case e: Throwable =>
        log("unwinding " + msg)
        throw e
    } finally {
      context = context.tail
    }
  }

  def log(msg: String): Unit =
    if (show) {
      println("  " * context.size + msg)
    }

  def debug[T](msg: String)(f: => T): T = {
    log(s"computing $msg")
    val res = f
    log(s"debug $msg = $res")
    res
  }
}
