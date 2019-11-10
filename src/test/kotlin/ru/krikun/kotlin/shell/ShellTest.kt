package ru.krikun.kotlin.shell

import kotlinx.coroutines.flow.toList
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ShellTest {
    private val dir = createTempDir().apply { deleteOnExit() }

    @Test
    fun `regular shell call`() = shell(dir) {
        val result = call("echo foo").result().toList()
        assertTrue(result.size == 1, result.toString())
        assertEquals("foo", result.first())
    }.let { assertEquals(0, it) }
}
