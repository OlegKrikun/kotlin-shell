@file:Suppress("ComplexRedundantLet")

package ru.krikun.kotlin.shell

import org.junit.Test
import kotlin.test.assertEquals

class ExecutableTest {
    private val dir = createTempDir().apply { deleteOnExit() }
    private val exitCodeCheck: (Int?) -> Unit = { assertEquals(0, it) }

    private val testBody: suspend Shell.() -> Unit = {
        val dir = createTempDir(directory = dir)
        "cd ${dir.absolutePath}"()
        "echo test > test.txt"()
        assertEquals("test", call("cat test.txt").lines()?.single())
    }

    @Test
    fun `executable sh`() = shell(dir, executable = "sh", block = testBody).let(exitCodeCheck)

    @Test
    fun `executable bin-sh`() = shell(dir, executable = "/bin/sh", block = testBody).let(exitCodeCheck)

    @Test
    fun `executable usr-bin-env sh`() = shell(dir, executable = "/usr/bin/env sh", block = testBody).let(exitCodeCheck)
}
