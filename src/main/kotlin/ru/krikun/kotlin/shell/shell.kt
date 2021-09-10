package ru.krikun.kotlin.shell

import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.DEFAULT_CONCURRENCY
import kotlinx.coroutines.flow.Flow
import ru.krikun.kotlin.shell.internal.CallImpl
import ru.krikun.kotlin.shell.internal.ParallelCallImpl
import ru.krikun.kotlin.shell.internal.SudoCallImpl
import ru.krikun.kotlin.shell.internal.SudoParallelCallImpl
import ru.krikun.kotlin.shell.internal.Worker
import java.io.Closeable
import java.io.File

class Shell(
    workingDir: File = currentSystemWorkingDir(),
    environment: Map<String, String> = mapOf(),
    executable: String = SH,
    exitOnError: Boolean = true
) : Closeable {
    private val worker = Worker(workingDir, environment, executable, exitOnError)

    fun call(cmd: String): Call = CallImpl(worker, cmd)

    fun call(vararg cmd: String): Call = CallImpl(worker, cmd.joinCommandSequential())

    fun asUser(user: String, cmd: String): Call = SudoCallImpl(worker, user, cmd)

    fun asUser(user: String, vararg cmd: String): Call = SudoCallImpl(worker, user, cmd.joinCommandSequential())

    fun parallel(cmd: List<String>): ParallelCall = ParallelCallImpl(worker, cmd)

    fun parallelAsUser(user: String, cmd: List<String>): ParallelCall = SudoParallelCallImpl(worker, user, cmd)

    fun exit(): Int = worker.exit()

    override fun close() {
        exit()
    }

    suspend operator fun String.invoke(): Int? = call(this).execute()

    suspend operator fun Iterable<String>.invoke(): Int? = call(joinCommandSequential()).execute()

    suspend inline operator fun String.invoke(
        crossinline action: suspend (String) -> Unit
    ): Int? = call(this).output(action)

    suspend inline operator fun Iterable<String>.invoke(
        crossinline action: suspend (String) -> Unit
    ): Int? = call(joinCommandSequential()).output(action)

    suspend fun String.asUser(user: String): Int? = asUser(user, this).execute()

    suspend fun Iterable<String>.asUser(user: String): Int? = asUser(user, joinCommandSequential()).execute()

    suspend inline fun String.asUser(
        user: String,
        crossinline action: suspend (String) -> Unit
    ) = asUser(user, this).output(action)

    suspend inline fun Iterable<String>.asUser(
        user: String,
        crossinline action: suspend (String) -> Unit
    ) = asUser(user, joinCommandSequential()).output(action)

    fun Iterable<String>.joinCommandSequential() = joinToString(" && ")
    private fun Array<out String>.joinCommandSequential() = joinToString(" && ")

    companion object {
        const val SH = "/usr/bin/env sh"
    }
}

sealed class Output {
    data class Line(val data: String) : Output()
    data class Error(val data: String) : Output()
    data class ExitCode(val data: Int?) : Output()
}

interface Call {
    suspend fun execute(): Int?
    fun output(): Flow<Output>
}

interface ParallelCall {
    @OptIn(FlowPreview::class)
    suspend fun execute(concurrency: Int = DEFAULT_CONCURRENCY): List<Int?>
    suspend fun output(): Flow<Flow<Output>>
}
