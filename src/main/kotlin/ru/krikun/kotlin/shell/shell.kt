package ru.krikun.kotlin.shell

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.broadcast
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.single
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.BufferedWriter
import java.io.File
import java.io.InputStream
import java.util.UUID
import kotlin.system.exitProcess

class Shell(workingDir: File, environment: Map<String, String> = mapOf(), exitOnError: Boolean = true) {
    private val worker = Worker(workingDir, environment, exitOnError)

    fun call(cmd: String): Call = RegularCall(worker, cmd)

    fun asUser(user: String, cmd: String): Call = SudoCall(worker, user, cmd)

    suspend fun exit(): Int = worker.exit()

    suspend operator fun String.invoke(): Int? = call(this).execute()

    suspend inline operator fun String.invoke(
        crossinline action: suspend (String) -> Unit
    ): Int? = call(this).result(action)

    suspend fun String.asUser(user: String): Int? = asUser(user, this).execute()

    suspend inline fun String.asUser(
        user: String,
        crossinline action: suspend (String) -> Unit
    ) = asUser(user, this).result(action)
}

sealed class Output {
    data class Line(val data: String) : Output()
    data class ExitCode(val data: Int?) : Output()
    object Terminate : Output()
}

interface Call {
    suspend fun execute(): Int?
    fun result(): Flow<Output>
}

private class RegularCall(private val worker: Worker, private val cmd: String) : Call {
    override suspend fun execute() = worker.run(cmd)
    override fun result() = worker.runWithResult(cmd)
}

private class SudoCall(private val worker: Worker, private val user: String, private val cmd: String) : Call {
    override suspend fun execute() = worker.run(cmd.asUser(user))
    override fun result() = worker.runWithResult(cmd.asUser(user))
    private fun String.asUser(user: String) = "sudo -u $user $this"
}

private class Worker(
    workingDir: File,
    environment: Map<String, String>,
    private val exitOnError: Boolean
) : CoroutineScope {
    override val coroutineContext = Dispatchers.Default + Job()

    private val endMarker = UUID.randomUUID().toString()

    private val process = ProcessBuilder("/bin/sh").apply {
        directory(workingDir)
        environment().putAll(environment)
        redirectErrorStream(true)
    }.start()

    private val processOutput = broadcast {
        val outputJob = launch {
            process.inputStream.asRawFlow().collect { send(it) }
        }
        awaitClose {
            outputJob.cancel()
        }
    }

    private val processInput = Channel<String>().also { channel ->
        launch {
            process.outputStream.bufferedWriter().use { writer ->
                channel.consumeEach {
                    writer.appendLine(it).flush()
                }
            }
            channel.close()
        }
    }

    fun runWithResult(cmd: String) = processOutput.asFlow()
        .onStart { processInput.send(cmd.withEndMarker()) }
        .onEach { raw -> raw.takeIf { exitOnError }?.let { (it as? Output.ExitCode)?.exitOnError() } }
        .takeWhile { it != Output.Terminate }

    suspend fun run(cmd: String) = runWithResult(cmd).filterIsInstance<Output.ExitCode>().single().data

    suspend fun exit() = processInput.send("exit").let { process.await() }

    private suspend fun Process.await() = suspendCancellableCoroutine<Int> {
        it.invokeOnCancellation { destroy() }
        try {
            it.resumeWith(Result.success(waitFor()))
        } catch (e: Exception) {
            it.resumeWith(Result.failure(e))
        }
    }

    private fun InputStream.asRawFlow() = bufferedReader()
        .lineSequence()
        .asFlow()
        .transform { transformLine(it) }

    private suspend fun FlowCollector<Output>.transformLine(line: String) = when {
        line.contains(endMarker) -> with(line.split(endMarker, limit = 2)) {
            if (size > 1) {
                val lineContent = first()
                if (lineContent.isNotEmpty()) {
                    emit(Output.Line(first()))
                }
            }
            emit(Output.ExitCode(last().toIntOrNull()))
            emit(Output.Terminate)
        }
        else -> emit(Output.Line(line))
    }

    private fun BufferedWriter.appendLine(line: String) = apply {
        write(line)
        newLine()
    }

    private fun String.withEndMarker(): String {
        return "$this && echo \"$endMarker$?\" || echo \"$endMarker$?\" 1>&2"
    }

    private fun Output.ExitCode.exitOnError() = (data ?: 1).takeIf { it != 0 }?.let { exitProcess(it) }
}