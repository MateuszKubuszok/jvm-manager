//> using scala 3.3.7
//> using platform native
//> using nativeVersion 0.4.17

import scala.scalanative.unsafe.*
import scala.scalanative.unsigned.*
import scala.scalanative.libc.*
import scala.scalanative.posix.unistd
import scala.scalanative.posix.sys.ioctl
import scala.scalanative.meta.LinktimeInfo
import scala.collection.mutable.ArrayBuffer

@link("c")
@extern
object Native {
  def popen(command: CString, mode: CString): Ptr[stdio.FILE] = extern
  def pclose(stream: Ptr[stdio.FILE]): CInt = extern
  def kill(pid: CInt, sig: CInt): CInt = extern
  def system(command: CString): CInt = extern
  // struct pollfd { int fd; short events; short revents; }
  def poll(fds: Ptr[CStruct3[CInt, CShort, CShort]], nfds: CUnsignedLong, timeout: CInt): CInt = extern
}

case class JvmProcess(pid: Int, mainClass: String, pwd: String)

// What the UI is currently doing with the selected process
enum Mode {
  case Browse
  case ConfirmKill(proc: JvmProcess)
  case Terminating(proc: JvmProcess, deadline: Long)
  case ConfirmForceKill(proc: JvmProcess)
  case ForceKilling(proc: JvmProcess, deadline: Long)
}

object JvmManager {

  val RefreshIntervalMs = 2000L
  val TermTimeoutMs = 5000L
  val KillTimeoutMs = 2000L

  val SIGTERM = 15
  val SIGKILL = 9
  val EPERM = 1
  val ESRCH = 3
  val POLLIN: CShort = 1.toShort

  def executeCommand(cmd: String): String = Zone { implicit z =>
    val output = new StringBuilder
    val file = Native.popen(toCString(cmd), c"r")

    if (file == null) {
      ""
    } else {
      val buffer = stackalloc[CChar](1024)
      while (stdio.fgets(buffer, 1024, file) != null) {
        output.append(fromCString(buffer))
      }

      Native.pclose(file)
      output.toString
    }
  }

  def getJvmProcesses(): List[JvmProcess] = {
    val found = ArrayBuffer[(Int, String)]()

    // Use jps -l to get PID and main class
    val jpsOutput = executeCommand("jps -l 2>/dev/null")
    val jpsLines = jpsOutput.split("\n").filter(_.nonEmpty)

    for (line <- jpsLines) {
      val parts = line.split("\\s+", 2)
      if (parts.length == 2) {
        val pid = parts(0).toIntOption.getOrElse(0)
        val mainClass = parts(1)

        // Skip the jps process itself and invalid PIDs
        if (pid > 0 && !mainClass.contains("jps")) {
          found += ((pid, mainClass))
        }
      }
    }

    val pwds = getPwds(found.map(_._1).toList)
    found.toList.map { case (pid, mainClass) => JvmProcess(pid, mainClass, pwds.getOrElse(pid, "N/A")) }
  }

  def getPwds(pids: List[Int]): Map[Int, String] = {
    if (pids.isEmpty) return Map.empty

    // A single lsof call for all PIDs (works on macOS and most Linuxes); output is "p<pid>" followed by "n<path>"
    val lsofOutput = executeCommand(s"lsof -a -d cwd -p ${pids.mkString(",")} -Fpn 2>/dev/null")
    val result = scala.collection.mutable.Map[Int, String]()
    var currentPid = -1
    for (line <- lsofOutput.split("\n") if line.nonEmpty) {
      line.head match {
        case 'p' => currentPid = line.tail.toIntOption.getOrElse(-1)
        case 'n' if currentPid > 0 && line.length > 1 => result(currentPid) = line.tail
        case _ =>
      }
    }

    // Fallback: try pwdx (Linux) for whatever lsof did not resolve
    for (pid <- pids if !result.contains(pid)) {
      val pwdxOutput = executeCommand(s"pwdx $pid 2>/dev/null")
      val idx = pwdxOutput.indexOf(':')
      if (idx >= 0 && pwdxOutput.substring(idx + 1).trim.nonEmpty) {
        result(pid) = pwdxOutput.substring(idx + 1).trim
      }
    }

    result.toMap
  }

  /** Sends a signal; returns None on success, or an error description. */
  def sendSignal(pid: Int, sig: Int): Option[String] = {
    if (Native.kill(pid, sig) == 0) None
    else errno.errno match {
      case EPERM => Some(s"Permission denied to signal process $pid. You may need sudo.")
      case ESRCH => Some(s"Process $pid no longer exists.")
      case e     => Some(s"Failed to signal process $pid (errno $e).")
    }
  }

  /** Signal 0 performs only the existence/permission check. */
  def isAlive(pid: Int): Boolean =
    Native.kill(pid, 0) == 0 || errno.errno == EPERM

  def now(): Long = System.currentTimeMillis()

  // ---------------------------------------------------------------------------
  // Terminal

  /** Returns (rows, cols) of the terminal. */
  def terminalSize(): (Int, Int) = {
    val ws = stackalloc[CStruct4[CUnsignedShort, CUnsignedShort, CUnsignedShort, CUnsignedShort]]()
    val TIOCGWINSZ: CLongInt = if (LinktimeInfo.isMac) 0x40087468L else 0x5413L
    if (ioctl.ioctl(unistd.STDOUT_FILENO, TIOCGWINSZ, ws.asInstanceOf[Ptr[Byte]]) == 0 && ws._1.toInt > 0 && ws._2.toInt > 0) {
      (ws._1.toInt, ws._2.toInt)
    } else {
      val rows = Option(System.getenv("LINES")).flatMap(_.toIntOption).getOrElse(24)
      val cols = Option(System.getenv("COLUMNS")).flatMap(_.toIntOption).getOrElse(80)
      (rows, cols)
    }
  }

  def setRawMode(): Unit = Zone { implicit z =>
    Native.system(c"stty raw -echo 2>/dev/null")
    // Alternate screen buffer, hidden cursor
    print("\u001b[?1049h\u001b[?25l")
    stdio.fflush(stdio.stdout)
  }

  def resetTerminal(): Unit = Zone { implicit z =>
    print("\u001b[?25h\u001b[?1049l")
    stdio.fflush(stdio.stdout)
    Native.system(c"stty sane 2>/dev/null")
  }

  /** Waits up to timeoutMs for a byte on stdin; returns -1 on timeout. */
  def readKey(timeoutMs: Int): Int = {
    val pfd = stackalloc[CStruct3[CInt, CShort, CShort]]()
    pfd._1 = unistd.STDIN_FILENO
    pfd._2 = POLLIN
    pfd._3 = 0.toShort
    if (Native.poll(pfd, 1.toULong, timeoutMs) > 0) {
      val buf = stackalloc[Byte]()
      if (unistd.read(unistd.STDIN_FILENO, buf, 1.toUInt) == 1) (!buf).toInt & 0xff else -1
    } else -1
  }

  // ---------------------------------------------------------------------------
  // Rendering

  /** Splits text into lines of at most `width` chars, preferring to break after separators. Never drops characters. */
  def wrap(text: String, width: Int): List[String] = {
    val w = width max 1
    val lines = ArrayBuffer[String]()
    var rest = text
    while (rest.length > w) {
      def lastBreakAfter(chars: String) = (w until (w / 2) by -1).find(i => chars.contains(rest.charAt(i - 1)))
      val breakAt = lastBreakAfter(" ").orElse(lastBreakAfter("/.-_$")).getOrElse(w)
      lines += rest.take(breakAt)
      rest = rest.drop(breakAt)
    }
    lines += rest
    lines.toList
  }

  def pad(s: String, width: Int): String = s + " " * (width - s.length).max(0)

  /** Splits the space available to the two text columns so that wrapping produces as few lines as possible. */
  def columnWidths(available: Int, cells: List[(String, String)]): (Int, Int) = {
    val needMain = cells.map(_._1.length).max
    val needPwd = cells.map(_._2.length).max
    if (needMain + needPwd <= available) (needMain, available - needMain)
    else {
      def height(len: Int, w: Int) = (len + w - 1) / w max 1
      val mainW = (1 until available).minBy { w =>
        val lines = cells.map { case (m, p) => height(m.length, w) max height(p.length, available - w) }.sum
        // ties go to the split closest to even
        (lines, (2 * w - available).abs)
      }
      (mainW, available - mainW)
    }
  }

  def render(processes: List[JvmProcess], selectedIndex: Int, scrollTop: Int, mode: Mode, status: String): Int = {
    val (rows, cols) = terminalSize()
    val sep = "  "
    val markerW = 2
    val pidW = (processes.map(_.pid.toString.length) :+ 3).max
    val available = (cols - markerW - pidW - 2 * sep.length) max 2
    val (mainW, pwdW) = columnWidths(available, ("Main Class", "Working Directory") :: processes.map(p => (p.mainClass, p.pwd)))

    def row(marker: String, pid: String, main: String, pwd: String): List[String] = {
      val mainLines = wrap(main, mainW)
      val pwdLines = wrap(pwd, pwdW)
      (0 until (mainLines.length max pwdLines.length)).toList.map { i =>
        val m = if (i == 0) marker else ""
        val p = if (i == 0) pid else ""
        val line = pad(m, markerW) + pad(p, pidW) + sep + pad(mainLines.lift(i).getOrElse(""), mainW) + sep + pwdLines.lift(i).getOrElse("")
        line
      }
    }

    val header = wrap("JVM Process Manager", cols) ++ List("=" * cols) ++ row("", "PID", "Main Class", "Working Directory") ++ List("-" * cols)

    val prompt = mode match {
      case Mode.Browse                 => ""
      case Mode.ConfirmKill(p)         => s"Kill process ${p.pid} (${p.mainClass}) with SIGTERM? (y/N)"
      case Mode.Terminating(p, _)      => s"Sent SIGTERM to ${p.pid}, waiting for it to exit..."
      case Mode.ConfirmForceKill(p)    => s"Process ${p.pid} did not exit after SIGTERM. Force kill with SIGKILL? (y/N)"
      case Mode.ForceKilling(p, _)     => s"Sent SIGKILL to ${p.pid}, waiting for it to exit..."
    }
    val controls = "Controls: ↑/k = Up | ↓/j = Down | Enter/Space = Kill selected | r = Refresh | q = Quit" +
      s" | auto-refresh every ${RefreshIntervalMs / 1000}s"
    val footer = List("-" * cols) ++
      (if (prompt.nonEmpty) wrap(prompt, cols) else Nil) ++
      (if (status.nonEmpty) wrap(status, cols) else Nil) ++
      wrap(controls, cols)

    val bodyHeight = (rows - header.length - footer.length) max 1
    val rendered = processes.zipWithIndex.map { case (proc, idx) =>
      row(if (idx == selectedIndex) ">" else "", proc.pid.toString, proc.mainClass, proc.pwd)
    }
    // When everything does not fit, one line is reserved for the "more above/below" indicator
    val overflows = rendered.map(_.length).sum > bodyHeight
    val listHeight = if (overflows) (bodyHeight - 1) max 1 else bodyHeight

    // Scroll so that the selected process is fully visible (if it fits at all)
    var top = scrollTop min selectedIndex max 0
    while (top < selectedIndex && rendered.slice(top, selectedIndex + 1).map(_.length).sum > listHeight) top += 1

    val body =
      if (processes.isEmpty) List("No JVM processes found.")
      else {
        val visible = ArrayBuffer[String]()
        var next = top
        while (next < rendered.length && visible.length + rendered(next).length <= listHeight) {
          visible ++= rendered(next)
          next += 1
        }
        // A single process taller than the screen: show as much of it as possible
        if (next == top) { visible ++= rendered(top).take(listHeight); next += 1 }
        if (overflows) {
          val above = if (top > 0) List(s"↑ $top more above") else Nil
          val below = if (next < rendered.length) List(s"↓ ${rendered.length - next} more below") else Nil
          visible.padTo(listHeight, "").toList ++ wrap((above ++ below).mkString("  "), cols).take(1)
        } else visible.toList
      }

    val screen = (header ++ body.padTo(bodyHeight, "") ++ footer).take(rows)
    print("\u001b[H" + screen.map(_ + "\u001b[K").mkString("\r\n") + "\u001b[J")
    stdio.fflush(stdio.stdout)
    top
  }

  // ---------------------------------------------------------------------------
  // Main loop

  def main(args: Array[String]): Unit = {
    var running = true
    var processes = getJvmProcesses()
    var lastRefresh = now()
    var selectedIndex = 0
    var scrollTop = 0
    var mode: Mode = Mode.Browse
    var status = ""
    var lastSize = (0, 0)
    var dirty = true

    // Keeps the selection on the same PID across refreshes
    def refresh(): Unit = {
      val selectedPid = processes.lift(selectedIndex).map(_.pid)
      processes = getJvmProcesses()
      lastRefresh = now()
      selectedIndex = selectedPid.map(pid => processes.indexWhere(_.pid == pid)).filter(_ >= 0)
        .getOrElse(selectedIndex min (processes.length - 1) max 0)
      dirty = true
    }

    def move(delta: Int): Unit = if (processes.nonEmpty) {
      selectedIndex = (selectedIndex + delta + processes.length) % processes.length
      dirty = true
    }

    setRawMode()

    try {
      while (running) {
        val size = terminalSize()
        if (size != lastSize) { lastSize = size; dirty = true }
        if (dirty) {
          scrollTop = render(processes, selectedIndex, scrollTop, mode, status)
          dirty = false
        }

        val key = readKey(100)

        // Kill progress is checked on every tick so the UI stays responsive while waiting
        mode match {
          case Mode.Terminating(p, deadline) =>
            if (!isAlive(p.pid)) { status = s"Process ${p.pid} terminated."; mode = Mode.Browse; refresh() }
            else if (now() > deadline) { mode = Mode.ConfirmForceKill(p); dirty = true }
          case Mode.ForceKilling(p, deadline) =>
            if (!isAlive(p.pid)) { status = s"Process ${p.pid} killed with SIGKILL."; mode = Mode.Browse; refresh() }
            else if (now() > deadline) {
              status = s"Process ${p.pid} is still running even after SIGKILL (it may be a zombie or in uninterruptible I/O)."
              mode = Mode.Browse
              refresh()
            }
          case _ =>
        }

        mode match {
          case Mode.ConfirmKill(p) if key >= 0 =>
            mode = Mode.Browse
            if (key == 'y' || key == 'Y') {
              sendSignal(p.pid, SIGTERM) match {
                case None      => mode = Mode.Terminating(p, now() + TermTimeoutMs)
                case Some(err) => status = err
              }
            } else status = "Kill cancelled."
            dirty = true

          case Mode.ConfirmForceKill(p) if key >= 0 =>
            mode = Mode.Browse
            if (key == 'y' || key == 'Y') {
              sendSignal(p.pid, SIGKILL) match {
                case None      => mode = Mode.ForceKilling(p, now() + KillTimeoutMs)
                case Some(err) => status = err
              }
            } else status = s"Process ${p.pid} left running."
            dirty = true

          case Mode.Terminating(_, _) | Mode.ForceKilling(_, _) =>
            if (key == 'q' || key == 'Q' || key == 3) running = false

          case _ =>
            key match {
              case 'q' | 'Q' | 3 => // 3 = Ctrl-C (raw mode does not generate SIGINT)
                running = false

              case 'r' | 'R' =>
                status = ""
                refresh()

              case 'k' => move(-1)
              case 'j' => move(1)

              case 13 | 10 | ' ' => // Enter (CR in raw mode) | Space
                processes.lift(selectedIndex).foreach { proc =>
                  status = ""
                  mode = Mode.ConfirmKill(proc)
                  dirty = true
                }

              case 27 => // Escape sequence start
                if (readKey(50) == '[') {
                  readKey(50) match {
                    case 'A' => move(-1) // Up arrow
                    case 'B' => move(1) // Down arrow
                    case _   => // Ignore other escape sequences
                  }
                }

              case _ =>
                // Ignore other keys (and poll timeouts)
            }
        }

        if (mode == Mode.Browse && now() - lastRefresh >= RefreshIntervalMs) refresh()
      }
    } finally {
      resetTerminal()
      println("Goodbye!")
    }
  }
}
