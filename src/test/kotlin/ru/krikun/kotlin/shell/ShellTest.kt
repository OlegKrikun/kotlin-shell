@file:Suppress("ComplexRedundantLet")

package ru.krikun.kotlin.shell

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.flatMapMerge
import kotlinx.coroutines.flow.flattenMerge
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ShellTest {
    private val dir = createTempDir().apply { deleteOnExit() }
    private val exitCodeCheck: (Int?) -> Unit = { assertEquals(0, it) }

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
    fun `sequential shell call`() = shell(dir) {
        val result = call(
            "mkdir sequentialTest",
            "touch sequentialTest/sequentialTest1",
            "touch sequentialTest/sequentialTest2",
            "ls sequentialTest"
        ).lines()
        assertNotNull(result)
        assertTrue(result.any { it == "sequentialTest1" })
        assertTrue(result.any { it == "sequentialTest2" })
    }.let(exitCodeCheck)

    @Test
    fun `flood shell call`() = shell(dir) {
        "mkdir floodTest"()
        val range = 1..10000
        val write = range.map { "echo test > floodTest/floodTest$it" }
        val read = range.map { "cat floodTest/floodTest$it" }
        write.forEach { it().let(exitCodeCheck) }
        read.forEach { it { assertEquals("test", it) }.let(exitCodeCheck) }
    }.let(exitCodeCheck)

    @Test
    fun `async flood shell call`() = runBlocking {
        shell(dir) {
            "mkdir asyncTest"()
            val range = 1..10000
            val write = range.map { "echo test > asyncTest/asyncTest$it" }
            val read = range.map { "cat asyncTest/asyncTest$it" }
            write.map { async { it() } }.awaitAll().forEach(exitCodeCheck)
            read.map { async { call(it).lines()?.single() } }.awaitAll().forEach { assertEquals("test", it) }
        }.let(exitCodeCheck)
    }

    @Test
    fun `parallel shell call`() = shell(dir) {
        "mkdir parallelTest"().let(exitCodeCheck)
        "cd parallelTest"().let(exitCodeCheck)
        "export PTEST=parallelTest"().let(exitCodeCheck)
        val range = 1..10000
        val write = range.map { "echo \$PTEST > parallelTest$it" }
        val read = range.map { "cat parallelTest$it" }
        parallel(write).execute(4).forEach(exitCodeCheck)
        parallel(read).output(4) { assertEquals("parallelTest", it) }.forEach(exitCodeCheck)
    }.let(exitCodeCheck)

    @Test
    fun `parallel flowOn shell call`() = shell(dir) {
        "mkdir parallelTest2"().let(exitCodeCheck)
        "cd parallelTest2"().let(exitCodeCheck)
        "export PTEST=parallelTest"().let(exitCodeCheck)
        val range = 1..10000
        val write = range.map { "echo \$PTEST > parallelTest$it" }
        val read = range.map { "cat parallelTest$it" }
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
            .flatMapMerge(4) { it.filterIsInstance<Output.Line>() }
            .flowOn(Dispatchers.IO)
            .collect { assertEquals("parallelTest", it.data) }
    }.let(exitCodeCheck)

    @Test
    fun `parallel DEFAULT_CONCURRENCY shell call`() = shell(dir) {
        "mkdir parallelTest3"().let(exitCodeCheck)
        "cd parallelTest3"().let(exitCodeCheck)
        "export PTEST=parallelTest"().let(exitCodeCheck)
        val range = 1..10000
        val write = range.map { "echo \$PTEST > parallelTest$it" }
        val read = range.map { "cat parallelTest$it" }
        parallel(write).execute().forEach(exitCodeCheck)
        parallel(read).output { assertEquals("parallelTest", it) }.forEach(exitCodeCheck)
    }.let(exitCodeCheck)

    @Test
    fun `exit shell call`() = runBlocking {
        (1..10).map { Shell(dir) }.forEach { assertEquals(it.exit(), 0) }
    }
}
