package ru.krikun.kotlin.shell.internal

import ru.krikun.kotlin.shell.Call
import ru.krikun.kotlin.shell.ParallelCall
import ru.krikun.kotlin.shell.exitCode
import ru.krikun.kotlin.shell.exitCodeList

internal class CallImpl(private val worker: Worker, private val cmd: String) : Call {
    override suspend fun execute() = output().exitCode()
    override fun output() = worker.run(cmd)
}

internal class SudoCallImpl(
    private val worker: Worker,
    private val user: String,
    private val cmd: String,
) : Call {
    override suspend fun execute() = output().exitCode()
    override fun output() = worker.run(cmd.asUser(user))
}

internal class ParallelCallImpl(
    private val worker: Worker,
    private val cmd: List<String>,
) : ParallelCall {
    override suspend fun execute(concurrency: Int) = output().exitCodeList(concurrency)
    override fun output() = worker.run(cmd)
}

internal class SudoParallelCallImpl(
    private val worker: Worker,
    private val user: String,
    private val cmd: List<String>,
) : ParallelCall {
    override suspend fun execute(concurrency: Int) = output().exitCodeList(concurrency)
    override fun output() = worker.run(cmd.map { it.asUser(user) })
}

private fun String.asUser(user: String): String {
    require(
        user.isNotEmpty() &&
            user.all {
                it.isLetterOrDigit() || it == '_' || it == '-' || it == '.'
            },
    ) { "Invalid user name: $user" }
    val escaped = replace("'", "'\\''")
    return "sudo -u $user -- sh -c '$escaped'"
}
