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
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.BufferedWriter
import java.io.File
import java.io.InputStream
import java.util.UUID
import kotlin.system.exitProcess

class Shell(workingDir: File, environment: Map<String, String>) {
    private val worker = Worker(workingDir, environment)

    fun call(cmd: String): Call = RegularCall(worker, cmd)

    fun asUser(user: String, cmd: String): Call = SudoCall(worker, user, cmd)

    suspend fun exit(): Int = worker.exit()

    suspend operator fun String.invoke(): Unit = call(this).execute()

    suspend inline operator fun String.invoke(
        crossinline action: suspend (String) -> Unit
    ): Unit = call(this).result(action)

    suspend fun String.asUser(user: String): Unit = asUser(user, this).execute()

    suspend inline fun String.asUser(
        user: String,
        crossinline action: suspend (String) -> Unit
    ) = asUser(user, this).result(action)
}

fun shell(
    workingDir: File,
    environment: Map<String, String> = mapOf(),
    block: suspend Shell.() -> Unit
): Int = runBlocking { Shell(workingDir, environment).apply { block() }.exit() }

interface Call {
    suspend fun execute()
    fun result(): Flow<String>
}

suspend inline fun Call.result(crossinline action: suspend (String) -> Unit) = result().collect(action)

suspend fun Call.printResult() = result { println(it) }

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
    environment: Map<String, String>
) : CoroutineScope {
    override val coroutineContext = Dispatchers.Default + Job()

    private val endMarker = UUID.randomUUID().toString()

    private val process = ProcessBuilder("/bin/sh").apply {
        directory(workingDir)
        environment().putAll(environment)
        redirectErrorStream(true)
    }.start()

    private val main = broadcast {
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

    fun runWithResult(cmd: String) = main.asFlow()
        .onStart { processInput.send(cmd.withEndMarker()) }
        .takeWhile { it != Raw.Terminate }
        .mapNotNull {
            when (it) {
                is Raw.Line -> it.data
                is Raw.ExitCode -> processExitCode(it)
                Raw.Terminate -> null
            }
        }

    suspend fun run(cmd: String) = main.asFlow()
        .onStart { processInput.send(cmd.withEndMarker()) }
        .takeWhile { it != Raw.Terminate }
        .collect { output ->
            when (output) {
                is Raw.ExitCode -> processExitCode(output)
            }
        }

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

    private suspend fun FlowCollector<Raw>.transformLine(line: String) = when {
        line.contains(endMarker) -> with(line.split(endMarker, limit = 2)) {
            if (size > 1) {
                val lineContent = first()
                if (lineContent.isNotEmpty()) {
                    emit(Raw.Line(first()))
                }
            }
            emit(Raw.ExitCode(last().toIntOrNull()))
            emit(Raw.Terminate)
        }
        else -> emit(Raw.Line(line))
    }

    private fun BufferedWriter.appendLine(line: String) = apply {
        write(line)
        newLine()
    }

    private fun String.withEndMarker(): String {
        return "$this && echo \"$endMarker$?\" || echo \"$endMarker$?\" 1>&2"
    }

    private fun processExitCode(exitCode: Raw.ExitCode) = (exitCode.data ?: 1)
        .takeIf { it != 0 }
        ?.let { exitProcess(it) }

    private sealed class Raw {
        data class Line(val data: String) : Raw()

        data class ExitCode(val data: Int?) : Raw()

        object Terminate : Raw()
    }
}