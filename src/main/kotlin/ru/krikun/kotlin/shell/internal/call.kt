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
    private val cmd: String
) : Call {
    override suspend fun execute() = output().exitCode()
    override fun output() = worker.run(cmd.asUser(user))
}

internal class ParallelCallImpl(private val worker: Worker, private val cmd: List<String>) : ParallelCall {
    override suspend fun execute(concurrency: Int) = output().exitCodeList(concurrency)
    override suspend fun output() = worker.run(cmd)
}

internal class SudoParallelCallImpl(
    private val worker: Worker,
    private val user: String,
    private val cmd: List<String>
) : ParallelCall {
    override suspend fun execute(concurrency: Int) = output().exitCodeList(concurrency)
    override suspend fun output() = worker.run(cmd.map { it.asUser(user) })
}

private fun String.asUser(user: String) = "sudo -u $user $this"
