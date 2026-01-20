# JVM Process Manager

An interactive TUI (Text User Interface) for managing JVM processes, written in Scala 3.3.7 with Scala Native.

## Features

- Lists all running JVM processes with their PID, main class, and working directory
- Interactive table navigation with keyboard controls
- Kill processes with confirmation
- Real-time refresh capability
- Native binary (no JVM required to run)

## Requirements

- Scala-CLI
- JDK with `jps` command available
- `lsof` (macOS) or `pwdx` (Linux) for working directory detection

## Usage

Run the script directly with Scala-CLI:

```bash
scala-cli run jvm-manager.scala
```

Or compile to a native binary:

```bash
scala-cli package jvm-manager.scala -o jvm-manager --native
./jvm-manager
```

## Controls

- **↑** or **k** - Move selection up
- **↓** or **j** - Move selection down
- **Enter** or **Space** - Kill selected process (with confirmation)
- **r** - Refresh process list
- **q** - Quit

## Implementation Details

- Built with Scala Native 0.4.17 for native performance
- Uses POSIX system calls (`popen`, `kill`) for process management
- Terminal manipulation via ANSI escape codes
- Raw terminal mode for responsive keyboard input

## Example Output

```
========================================================================================================================
JVM Process Manager
========================================================================================================================

     PID       Main Class                                          Working Directory
------------------------------------------------------------------------------------------------------------------------
>    77793     scala.meta.metals.Main                              /Users/dev/Workspaces/GitHub/jvm-ls
     86069     my.app.MainClass                                    /Users/dev/projects/my-app
     70710     bloop.BloopServer                                   /Users/dev/Library/Caches/ScalaCli/bloop
     70975     com.intellij.idea.Main                              /Users/dev

------------------------------------------------------------------------------------------------------------------------
Controls: ↑/k = Up | ↓/j = Down | Enter/Space = Kill selected | r = Refresh | q = Quit
```
