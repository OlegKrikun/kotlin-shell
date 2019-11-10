@file:Suppress("ComplexRedundantLet")

package ru.krikun.kotlin.shell

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ShellTest {
    private val dir = createTempDir().apply { deleteOnExit() }
    private val exitCodeCheck: (Int?) -> Unit = { assertEquals(0, it) }

    @Test
    fun `single shell call`() = shell(dir) {
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

}
