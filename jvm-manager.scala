//> using scala 3.3.7
//> using platform native
//> using nativeVersion 0.4.17

import scala.scalanative.unsafe.*
import scala.scalanative.unsigned.*
import scala.scalanative.libc.*
import scala.scalanative.posix.unistd
import scala.scalanative.posix.signal
import scala.collection.mutable.ArrayBuffer
import scala.util.{Try, Success, Failure}
import scala.scalanative.posix.sys.wait

@link("c")
@extern
object Native {
  def popen(command: CString, mode: CString): Ptr[stdio.FILE] = extern
  def pclose(stream: Ptr[stdio.FILE]): CInt = extern
  def kill(pid: CInt, sig: CInt): CInt = extern
  def system(command: CString): CInt = extern
}

case class JvmProcess(pid: Int, mainClass: String, pwd: String)

object JvmManager {

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
    val processes = ArrayBuffer[JvmProcess]()

    // Use jps -l to get PID and main class
    val jpsOutput = executeCommand("jps -l")
    val jpsLines = jpsOutput.split("\n").filter(_.nonEmpty)

    for (line <- jpsLines) {
      val parts = line.split("\\s+", 2)
      if (parts.length == 2) {
        val pid = parts(0).toIntOption.getOrElse(0)
        val mainClass = parts(1)

        // Skip the jps process itself and invalid PIDs
        if (pid > 0 && !mainClass.contains("jps")) {
          // Get PWD using lsof
          val pwd = getPwd(pid)
          processes += JvmProcess(pid, mainClass, pwd)
        }
      }
    }

    processes.toList
  }

  def getPwd(pid: Int): String = {
    // Try using lsof first (works on macOS)
    val lsofOutput = executeCommand(s"lsof -a -p $pid -d cwd -Fn 2>/dev/null")
    val lsofResult = lsofOutput.split("\n")
      .find(_.startsWith("n"))
      .map(_.substring(1))
      .filter(_.nonEmpty)

    lsofResult.getOrElse {
      // Fallback: try pwdx (Linux)
      val pwdxOutput = executeCommand(s"pwdx $pid 2>/dev/null")
      if (pwdxOutput.contains(":")) {
        pwdxOutput.split(":")(1).trim
      } else {
        "N/A"
      }
    }
  }

  def killProcess(pid: Int): Boolean = Zone { implicit z =>
    // Send SIGTERM (15)
    Native.kill(pid, 15) == 0
  }

  def clearScreen(): Unit = {
    // Move cursor to top-left and clear entire screen
    print("\u001b[H\u001b[2J")
    stdio.fflush(stdio.stdout)
  }

  def drawTable(processes: List[JvmProcess], selectedIndex: Int): Unit = {
    clearScreen()

    print("=" * 120 + "\r\n")
    print("JVM Process Manager\r\n")
    print("=" * 120 + "\r\n")
    print("\r\n")

    if (processes.isEmpty) {
      print("No JVM processes found.\r\n")
      print("\r\n")
      print("Press 'q' to quit\r\n")
      stdio.fflush(stdio.stdout)
      return
    }

    // Header
    print(f"${""}%-3s  ${"PID"}%-8s  ${"Main Class"}%-50s  ${"Working Directory"}%-50s\r\n")
    print("-" * 120 + "\r\n")

    // Rows
    for ((proc, idx) <- processes.zipWithIndex) {
      val marker = if (idx == selectedIndex) ">" else " "
      val mainClassTrunc = if (proc.mainClass.length > 50) proc.mainClass.take(47) + "..." else proc.mainClass
      val pwdTrunc = if (proc.pwd.length > 50) "..." + proc.pwd.takeRight(47) else proc.pwd

      print(f"$marker%-3s  ${proc.pid}%-8d  $mainClassTrunc%-50s  $pwdTrunc%-50s\r\n")
    }

    print("\r\n")
    print("-" * 120 + "\r\n")
    print("Controls: ↑/k = Up | ↓/j = Down | Enter/Space = Kill selected | r = Refresh | q = Quit\r\n")
    stdio.fflush(stdio.stdout)
  }

  def setRawMode(): Unit = Zone { implicit z =>
    Native.system(c"stty raw -echo 2>/dev/null")
  }

  def resetTerminal(): Unit = Zone { implicit z =>
    Native.system(c"stty sane 2>/dev/null")
  }

  def readKey(): Int = {
    stdio.getchar()
  }

  def main(args: Array[String]): Unit = {
    var running = true
    var selectedIndex = 0
    var processes = getJvmProcesses()

    // Initial draw
    drawTable(processes, selectedIndex)

    // Set terminal to raw mode
    setRawMode()

    try {
      while (running) {
        val key = readKey()

        key match {
          case 113 | 81 => // 'q' | 'Q'
            running = false

          case 114 | 82 => // 'r' | 'R'
            processes = getJvmProcesses()
            selectedIndex = 0
            drawTable(processes, selectedIndex)

          case 107 => // 'k'
            if (processes.nonEmpty) {
              selectedIndex = if (selectedIndex > 0) selectedIndex - 1 else processes.length - 1
              drawTable(processes, selectedIndex)
            }

          case 106 => // 'j'
            if (processes.nonEmpty) {
              selectedIndex = if (selectedIndex < processes.length - 1) selectedIndex + 1 else 0
              drawTable(processes, selectedIndex)
            }

          case 10 | 32 => // '\n' | ' '
            if (processes.nonEmpty && selectedIndex < processes.length) {
              val proc = processes(selectedIndex)
              resetTerminal()
              println()
              print(s"Kill process ${proc.pid} (${proc.mainClass})? (y/N): ")
              stdio.fflush(stdio.stdout)

              val confirm = scala.io.StdIn.readLine().toLowerCase

              if (confirm == "y" || confirm == "yes") {
                if (killProcess(proc.pid)) {
                  println(s"Process ${proc.pid} killed successfully.")
                } else {
                  println(s"Failed to kill process ${proc.pid}. You may need sudo.")
                }
                Thread.sleep(1500)
              }

              processes = getJvmProcesses()
              selectedIndex = if (selectedIndex >= processes.length && processes.nonEmpty) processes.length - 1 else selectedIndex
              if (selectedIndex < 0) selectedIndex = 0
              setRawMode()
              drawTable(processes, selectedIndex)
            }

          case 27 => // Escape sequence start
            val next1 = readKey()
            if (next1 == 91) { // '['
              val next2 = readKey()
              next2 match {
                case 65 => // 'A' - Up arrow
                  if (processes.nonEmpty) {
                    selectedIndex = if (selectedIndex > 0) selectedIndex - 1 else processes.length - 1
                    drawTable(processes, selectedIndex)
                  }
                case 66 => // 'B' - Down arrow
                  if (processes.nonEmpty) {
                    selectedIndex = if (selectedIndex < processes.length - 1) selectedIndex + 1 else 0
                    drawTable(processes, selectedIndex)
                  }
                case _ =>
                  // Ignore other escape sequences
              }
            }

          case _ =>
            // Ignore other keys
        }
      }
    } finally {
      resetTerminal()
      clearScreen()
      println("Goodbye!")
    }
  }
}
