@file:Suppress("ComplexRedundantLet")

package ru.krikun.kotlin.shell

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapMerge
import kotlinx.coroutines.flow.flattenMerge
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShellTest {
    private val dir = createTempDir().apply { deleteOnExit() }
    private val exitCodeCheck: (Int?) -> Unit = { assertEquals(0, it) }
    private val attemptRange = 1..1000

    @JvmField
    @Rule
    val timeout = Timeout.seconds(20)

    @Test
    fun `single shell call`() = shell {
        val result = call("echo foo").lines()
        assertNotNull(result)
        assertTrue(result.size == 1, result.toString())
        assertEquals("foo", result.first())
    }.let(exitCodeCheck)

    @Test
    fun `git shell call`() = shell(dir) {
        "git clone git@github.com:OlegKrikun/kotlin-shell.git"().let(exitCodeCheck)
        "cd kotlin-shell"().let(exitCodeCheck)
        val result = call("ls").lines()
        assertNotNull(result)
        assertTrue(result.any { it == "README.md" })
    }.let(exitCodeCheck)

    @Test
    fun `multiline output`() = shell(dir) {
        val result = call("echo a && echo b && echo c").lines()
        assertEquals(listOf("a", "b", "c"), result)
    }.let(exitCodeCheck)

    @Test
    fun `command with no stdout output`() = shell(dir) {
        val result = call("true").lines()
        assertEquals(emptyList(), result)
    }.let(exitCodeCheck)

    @Test
    fun `command with special characters in output`() = shell(dir) {
        val result = call("echo 'hello world!@#%^&*()'").lines()
        assertNotNull(result)
        assertEquals("hello world!@#%^&*()", result.single())
    }.let(exitCodeCheck)

    @Test
    fun `command with spaces in arguments`() = shell(dir) {
        "mkdir -p 'dir with spaces'"().let(exitCodeCheck)
        "touch 'dir with spaces/my file.txt'"().let(exitCodeCheck)
        val result = call("ls 'dir with spaces'").lines()
        assertNotNull(result)
        assertTrue(result.any { it == "my file.txt" })
    }.let(exitCodeCheck)

    @Test
    fun `command with pipe`() = shell(dir) {
        val result = call("echo 'foo bar baz' | tr ' ' '\\n'").lines()
        assertEquals(listOf("foo", "bar", "baz"), result)
    }.let(exitCodeCheck)

    @Test
    fun `command with redirect`() = shell(dir) {
        "echo redirect-test > redirect.txt"().let(exitCodeCheck)
        val result = call("cat redirect.txt").lines()
        assertEquals(listOf("redirect-test"), result)
    }.let(exitCodeCheck)

    @Test
    fun `command with subshell`() = shell(dir) {
        val result = call("echo \$(echo nested)").lines()
        assertEquals(listOf("nested"), result)
    }.let(exitCodeCheck)

    @Test
    fun `long output`() = shell(dir) {
        val result = call("seq 1 500").lines()
        assertNotNull(result)
        assertEquals(500, result.size)
        assertEquals("1", result.first())
        assertEquals("500", result.last())
    }.let(exitCodeCheck)

    @Test
    fun `execute returns zero for success`() = shell(dir) {
        val code = call("true").execute()
        assertEquals(0, code)
    }.let(exitCodeCheck)

    @Test
    fun `execute returns non-zero for failure`() = shell(
        workingDir = dir,
        exitOnError = false,
    ) {
        val code = call("false").execute()
        assertEquals(1, code)
    }.let(exitCodeCheck)

    @Test
    fun `execute returns specific exit code`() = shell(
        workingDir = dir,
        exitOnError = false,
    ) {
        val code = call("(exit 42)").execute()
        assertEquals(42, code)
    }.let(exitCodeCheck)

    @Test
    fun `lines returns null on failure`() = shell(
        workingDir = dir,
        exitOnError = false,
    ) {
        val result = call("echo out; false").lines()
        assertNull(result)
    }.let(exitCodeCheck)

    @Test
    fun `lines with custom successExitCode`() = shell(
        workingDir = dir,
        exitOnError = false,
    ) {
        val result = call("(exit 2)").lines(successExitCode = 2)
        assertNotNull(result)
    }.let(exitCodeCheck)

    @Test
    fun `exit code 127 for command not found`() = shell(
        workingDir = dir,
        exitOnError = false,
    ) {
        val code = call("nonexistent_command_xyz").execute()
        assertEquals(127, code)
    }.let(exitCodeCheck)

    @Test
    fun `stderr captured as Output Error`() = shell(
        workingDir = dir,
        exitOnError = false,
    ) {
        // Use failing command so marker goes to stderr (deterministic)
        val outputs = call("echo err >&2; false").output().toList()
        val errors = outputs.filterIsInstance<Output.Error>()
        assertTrue(errors.any { it.data == "err" })
    }.let(exitCodeCheck)

    @Test
    fun `stderr with exit code`() = shell(
        workingDir = dir,
        exitOnError = false,
    ) {
        val outputs = call("echo err >&2; false").output().toList()
        val exitCode = outputs.filterIsInstance<Output.ExitCode>()
        assertEquals(1, exitCode.single().data)
    }.let(exitCodeCheck)

    @Test
    fun `collectWithExitCode collects stderr text`() = shell(
        workingDir = dir,
        exitOnError = false,
    ) {
        val collected = mutableListOf<String>()
        val exitCode = call("echo err >&2; false")
            .output { collected.add(it) }
        assertEquals(1, exitCode)
        assertTrue(collected.contains("err"))
    }.let(exitCodeCheck)

    @Test
    fun `string invoke operator returns exit code`() = shell(dir) {
        val code = "echo hello"()
        assertEquals(0, code)
    }.let(exitCodeCheck)

    @Test
    fun `string invoke with action callback`() = shell(dir) {
        val collected = mutableListOf<String>()
        "echo callback-test" { collected.add(it) }
        assertEquals(listOf("callback-test"), collected)
    }.let(exitCodeCheck)

    @Test
    fun `iterable invoke joins with and-and`() = shell(dir) {
        "mkdir iterTest"().let(exitCodeCheck)
        val code = listOf(
            "touch iterTest/a",
            "touch iterTest/b",
        )()
        assertEquals(0, code)
        val result = call("ls iterTest").lines()
        assertNotNull(result)
        assertTrue(result.contains("a"))
        assertTrue(result.contains("b"))
    }.let(exitCodeCheck)

    @Test
    fun `iterable invoke with action callback`() = shell(dir) {
        val collected = mutableListOf<String>()
        listOf("echo iter1", "echo iter2").invoke { collected.add(it) }
        assertEquals(listOf("iter1", "iter2"), collected)
    }.let(exitCodeCheck)

    @Test
    fun `sequential shell call`() = shell(dir) {
        val result = call(
            "mkdir sequentialTest",
            "touch sequentialTest/sequentialTest1",
            "touch sequentialTest/sequentialTest2",
            "ls sequentialTest",
        ).lines()
        assertNotNull(result)
        assertTrue(result.any { it == "sequentialTest1" })
        assertTrue(result.any { it == "sequentialTest2" })
    }.let(exitCodeCheck)

    @Test
    fun `sequential vararg stops on first failure`() = shell(
        workingDir = dir,
        exitOnError = false,
    ) {
        val code = call("true", "false", "echo never").execute()
        assertEquals(1, code)
    }.let(exitCodeCheck)

    @Test
    fun `custom environment variables`() = shell(
        workingDir = dir,
        environment = mapOf("MY_VAR" to "my_value"),
    ) {
        val result = call("echo \$MY_VAR").lines()
        assertEquals(listOf("my_value"), result)
    }.let(exitCodeCheck)

    @Test
    fun `export persists between commands`() = shell(dir) {
        "export PERSIST_VAR=persisted"().let(exitCodeCheck)
        val result = call("echo \$PERSIST_VAR").lines()
        assertEquals(listOf("persisted"), result)
    }.let(exitCodeCheck)

    @Test
    fun `cd persists between commands`() = shell(dir) {
        "mkdir cdTest"().let(exitCodeCheck)
        "cd cdTest"().let(exitCodeCheck)
        val result = call("pwd").lines()?.single()
        assertNotNull(result)
        assertTrue(result.endsWith("cdTest"))
    }.let(exitCodeCheck)

    @Test
    fun `working directory is set correctly`() = shell(
        workingDir = dir,
    ) {
        val result = call("pwd").lines()?.single()
        assertEquals(dir.canonicalPath, result?.let { java.io.File(it).canonicalPath })
    }.let(exitCodeCheck)

    @Test
    fun `output flow contains Line and ExitCode`() = shell(dir) {
        val outputs = call("echo hello").output().toList()
        assertTrue(outputs.any { it is Output.Line && it.data == "hello" })
        assertTrue(outputs.any { it is Output.ExitCode && it.data == 0 })
    }.let(exitCodeCheck)

    @Test
    fun `output flow ExitCode is last element`() = shell(dir) {
        val outputs = call("echo a && echo b").output().toList()
        assertTrue(outputs.last() is Output.ExitCode)
    }.let(exitCodeCheck)

    @Test
    fun `exitCode extension returns correct value`() = shell(
        workingDir = dir,
        exitOnError = false,
    ) {
        assertEquals(0, call("true").output().exitCode())
        assertEquals(1, call("false").output().exitCode())
    }.let(exitCodeCheck)

    @Test
    fun `shell as closeable via use`() = runBlocking {
        val result = Shell(dir).use { s ->
            s.call("echo use-test").lines()
        }
        assertEquals(listOf("use-test"), result)
    }

    @Test
    fun `multiple commands on same shell`() = shell(dir) {
        (1..10).forEach { i ->
            val result = call("echo cmd$i").lines()
            assertEquals(listOf("cmd$i"), result)
        }
    }.let(exitCodeCheck)

    @Test
    fun `exit shell call`() = runBlocking {
        (1..10).map { Shell(dir) }.forEach { assertEquals(it.exit(), 0) }
    }

    @Test
    fun `flood shell call`() = shell(dir) {
        "mkdir floodTest"()
        val write = attemptRange.map { "echo test > floodTest/floodTest$it" }
        val read = attemptRange.map { "cat floodTest/floodTest$it" }
        write.forEach { it().let(exitCodeCheck) }
        read.forEach { it { assertEquals("test", it) }.let(exitCodeCheck) }
    }.let(exitCodeCheck)

    @Test
    fun `async flood shell call`() = runBlocking {
        shell(dir) {
            "mkdir asyncTest"()
            val write = attemptRange.map { "echo test > asyncTest/asyncTest$it" }
            val read = attemptRange.map { "cat asyncTest/asyncTest$it" }
            write.map { async { it() } }.awaitAll().forEach(exitCodeCheck)
            read.map { async { call(it).lines()?.single() } }
                .awaitAll().forEach { assertEquals("test", it) }
        }.let(exitCodeCheck)
    }

    @Test
    fun `parallel shell call`() = shell(dir) {
        "mkdir parallelTest"().let(exitCodeCheck)
        "cd parallelTest"().let(exitCodeCheck)
        "export PTEST=parallelTest"().let(exitCodeCheck)
        val write = attemptRange.map { "echo \$PTEST > parallelTest$it" }
        val read = attemptRange.map { "cat parallelTest$it" }
        parallel(write).execute(4).forEach(exitCodeCheck)
        parallel(read).output(4) {
            assertEquals("parallelTest", it)
        }.forEach(exitCodeCheck)
    }.let(exitCodeCheck)

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `parallel flowOn shell call`() = shell(dir) {
        "mkdir parallelTest2"().let(exitCodeCheck)
        "cd parallelTest2"().let(exitCodeCheck)
        "export PTEST=parallelTest"().let(exitCodeCheck)
        val write = attemptRange.map { "echo \$PTEST > parallelTest$it" }
        val read = attemptRange.map { "cat parallelTest$it" }
        parallel(write).output()
            .flowOn(Dispatchers.IO)
            .flattenMerge(4)
            .filterIsInstance<Output.ExitCode>()
            .map { it.data }
            .flowOn(Dispatchers.IO)
            .toList()
            .forEach(exitCodeCheck)
        parallel(read).output()
            .flowOn(Dispatchers.IO)
            .flatMapMerge(4) {
                it.filterIsInstance<Output.Line>()
            }
            .flowOn(Dispatchers.IO)
            .collect { assertEquals("parallelTest", it.data) }
    }.let(exitCodeCheck)

    @Test
    fun `parallel DEFAULT_CONCURRENCY shell call`() = shell(dir) {
        "mkdir parallelTest3"().let(exitCodeCheck)
        "cd parallelTest3"().let(exitCodeCheck)
        "export PTEST=parallelTest"().let(exitCodeCheck)
        val write = attemptRange.map { "echo \$PTEST > parallelTest$it" }
        val read = attemptRange.map { "cat parallelTest$it" }
        parallel(write).execute().forEach(exitCodeCheck)
        parallel(read).output {
            assertEquals("parallelTest", it)
        }.forEach(exitCodeCheck)
    }.let(exitCodeCheck)

    @Test
    fun `parallel single command`() = shell(dir) {
        val codes = parallel(listOf("echo solo")).execute()
        assertEquals(listOf(0), codes)
    }.let(exitCodeCheck)

    @Test
    fun `parallel execute returns all exit codes`() = shell(
        workingDir = dir,
        exitOnError = false,
    ) {
        val codes = parallel(
            listOf("true", "(exit 1)", "(exit 2)"),
        ).execute(3)
        assertEquals(3, codes.size)
        assertTrue(codes.contains(0))
        assertTrue(codes.contains(1))
        assertTrue(codes.contains(2))
    }.let(exitCodeCheck)

    @Test
    fun `parallel lines returns null on any failure`() = shell(
        workingDir = dir,
        exitOnError = false,
    ) {
        val result = parallel(
            listOf("echo ok", "false"),
        ).lines()
        assertNull(result)
    }.let(exitCodeCheck)

    @Test
    fun `parallel lines returns output on all success`() = shell(dir) {
        val result = parallel(
            listOf("echo a", "echo b", "echo c"),
        ).lines()
        assertNotNull(result)
        assertEquals(3, result.size)
        assertTrue(result.containsAll(listOf("a", "b", "c")))
    }.let(exitCodeCheck)

    @Test
    fun `parallel preserves environment`() = shell(dir) {
        "export PAR_ENV=par_value"().let(exitCodeCheck)
        val result = parallel(
            listOf("echo \$PAR_ENV"),
        ).lines()
        assertNotNull(result)
        assertTrue(result.contains("par_value"))
    }.let(exitCodeCheck)

    @Test
    fun `parallel preserves working directory`() = shell(dir) {
        "mkdir parDirTest"().let(exitCodeCheck)
        "cd parDirTest"().let(exitCodeCheck)
        val result = parallel(listOf("pwd")).lines()
        assertNotNull(result)
        assertTrue(result.single().endsWith("parDirTest"))
    }.let(exitCodeCheck)

    @Test
    fun `semaphore released after flow cancellation`() = shell(dir) {
        call("echo line1 && echo line2").output().first()

        val result = call("echo ok").lines()
        assertEquals(listOf("ok"), result)
    }.let(exitCodeCheck)

    @Test
    fun `failed command does not block next command`() = shell(
        workingDir = dir,
        exitOnError = false,
    ) {
        val exitCode = "false"()
        assertEquals(1, exitCode)

        val result = call("echo still-alive").lines()
        assertEquals(listOf("still-alive"), result)
    }.let(exitCodeCheck)

    @Test
    fun `multiple failures in a row`() = shell(
        workingDir = dir,
        exitOnError = false,
    ) {
        repeat(5) {
            val code = "false"()
            assertEquals(1, code)
        }
        val result = call("echo recovered").lines()
        assertEquals(listOf("recovered"), result)
    }.let(exitCodeCheck)

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `parallel workers cleaned up on cancellation`() = shell(dir) {
        "mkdir cancelTest"().let(exitCodeCheck)
        val cmds = (1..20).map { "echo cancel$it" }

        val collected = parallel(cmds).output()
            .flatMapMerge(4) { it.filterIsInstance<Output.Line>() }
            .take(2)
            .toList()

        assertEquals(2, collected.size)

        val result = call("echo ok").lines()
        assertEquals(listOf("ok"), result)
    }.let(exitCodeCheck)

    @Test
    fun `multiple shell instances do not leak file descriptors`() =
        runBlocking {
            (1..50).forEach { _ ->
                val s = Shell(dir)
                s.call("echo ok").lines()
                assertEquals(0, s.exit())
            }
        }

    @Test
    fun `command does not hang when process is killed`() = runBlocking {
        val s = Shell(dir, exitOnError = false)
        val pid = s.call("echo \$\$").lines()?.single()
        assertNotNull(pid)

        Runtime.getRuntime().exec(arrayOf("kill", "-9", pid))
        Thread.sleep(200)

        val result = s.call("echo test").lines()
        assertNull(result)
    }

    @Test
    fun `rapid sequential commands`() = shell(dir) {
        val results = (1..100).map { i ->
            call("echo $i").lines()?.single()
        }
        assertEquals(
            (1..100).map { it.toString() },
            results,
        )
    }.let(exitCodeCheck)

    @Test
    fun `flow collected multiple times`() = shell(dir) {
        val c = call("echo reuse")
        val r1 = c.lines()
        val r2 = c.lines()
        assertEquals(listOf("reuse"), r1)
        assertEquals(listOf("reuse"), r2)
    }.let(exitCodeCheck)

    @Test
    fun `output with empty lines`() = shell(dir) {
        val result = call("printf 'a\\n\\nb\\n'").lines()
        assertNotNull(result)
        assertEquals(listOf("a", "", "b"), result)
    }.let(exitCodeCheck)

    @Test
    fun `command with tab characters`() = shell(dir) {
        val result = call("printf 'col1\\tcol2'").lines()
        assertNotNull(result)
        assertEquals("col1\tcol2", result.single())
    }.let(exitCodeCheck)

    @Test
    fun `command with heredoc-like multiline`() = shell(dir) {
        val result = call("printf 'line1\\nline2\\nline3'").lines()
        assertEquals(listOf("line1", "line2", "line3"), result)
    }.let(exitCodeCheck)

    @Test
    fun `env command returns variables`() = shell(
        workingDir = dir,
        environment = mapOf("TEST_K" to "TEST_V"),
    ) {
        val result = call("echo \$TEST_K").lines()
        assertEquals(listOf("TEST_V"), result)
    }.let(exitCodeCheck)

    @Test
    fun `variable overwrite`() = shell(dir) {
        "export X=1"().let(exitCodeCheck)
        assertEquals(listOf("1"), call("echo \$X").lines())
        "export X=2"().let(exitCodeCheck)
        assertEquals(listOf("2"), call("echo \$X").lines())
    }.let(exitCodeCheck)

    @Test
    fun `exit returns zero for clean shell`() = runBlocking {
        val s = Shell(dir)
        assertEquals(0, s.exit())
    }
}
