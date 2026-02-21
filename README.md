# kotlin-shell

Coroutine-based Kotlin library for executing shell commands. Provides a persistent shell process
with state (working directory, environment variables) preserved between calls, Flow-based output
streaming, parallel execution, and sudo support.

## Installation

Add the GitHub Packages repository and dependency to your `build.gradle.kts`:

```kotlin
repositories {
    maven {
        url = uri("https://maven.pkg.github.com/OlegKrikun/kotlin-shell")
        credentials {
            username = project.findProperty("gpr.user") as String? ?: System.getenv("GITHUB_ACTOR")
            password = project.findProperty("gpr.key") as String? ?: System.getenv("GITHUB_TOKEN")
        }
    }
}

dependencies {
    implementation("ru.krikun.kotlin:kotlin-shell:<version>")
}
```

## Usage

### Basic execution

```kotlin
shell {
    "echo hello"()
    "ls -la" { println(it) }
}
```

The `shell` function creates a `Shell` instance backed by a persistent shell process
and runs the provided block inside `runBlocking`. Commands are executed via operator
syntax or explicit `call()`.

### Explicit call API

```kotlin
shell(workingDir = File("/tmp")) {
    call("echo hello").execute()          // returns exit code
    call("ls").output { println(it) }     // callback per line
    call("ls").printOutput()              // print to stdout
    val lines = call("cat file").lines()  // collect as List<String>?
}
```

`lines()` returns `null` if the command exits with a non-zero code.

### Sequential commands

Multiple commands joined with `&&` -- stops on first failure:

```kotlin
shell {
    call("mkdir build", "cd build", "cmake ..", "make").execute()
}
```

### Working directory and environment

The shell process is stateful. `cd`, `export`, and other side effects persist:

```kotlin
shell(
    workingDir = File("/home/user"),
    environment = mapOf("TOKEN" to "secret"),
) {
    "cd project"()
    "echo \$TOKEN"()  // "secret"
}
```

### Parallel execution

```kotlin
shell {
    val commands = listOf("echo a", "echo b", "echo c")

    parallel(commands).execute()                      // returns List<Int?>
    parallel(commands).execute(concurrency = 4)       // limit concurrency
    parallel(commands).output { println(it) }         // with output callback
    parallel(commands).lines()                        // collect all output lines
}
```

Each parallel command runs in its own worker process. Working directory and environment
are captured from the parent shell.

### Sudo

```kotlin
shell {
    asUser("root", "systemctl restart nginx").execute()
    "systemctl status nginx".asUser("root")
}
```

Username is validated (alphanumeric, `_`, `-`, `.` only), and the command is properly escaped.

### Flow-based output

`Call.output()` returns `Flow<Output>` where `Output` is:

```kotlin
sealed class Output {
    data class Line(val data: String) : Output()      // stdout
    data class Error(val data: String) : Output()     // stderr
    data class ExitCode(val data: Int?) : Output()    // final event
}
```

This allows standard Flow operators:

```kotlin
shell {
    call("seq 1 100").output()
        .filterIsInstance<Output.Line>()
        .map { it.data.toInt() }
        .filter { it % 2 == 0 }
        .collect { println(it) }
}
```

### Error handling

By default, a non-zero exit code throws `CommandFailedException`:

```kotlin
shell {
    try {
        "exit 1"()
    } catch (e: CommandFailedException) {
        println("Failed with code ${e.exitCode}")
    }
}
```

Disable this with `exitOnError = false` to handle exit codes manually:

```kotlin
shell(exitOnError = false) {
    val code = call("false").execute()  // returns 1, no exception
}
```
## License

See [LICENSE](LICENSE).
