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

fun shell(
    workingDir: File,
    environment: Map<String, String> = mapOf(),
    block: suspend Shell.() -> Unit
): Int = runBlocking { Shell(workingDir, environment).apply { block() }.exit() }

class Shell(workingDir: File, environment: Map<String, String>) {
    private val worker = Worker(workingDir, environment)

    fun call(cmd: String): Call = RegularCall(worker, cmd)

    fun asUser(user: String, cmd: String): Call = SudoCall(worker, user, cmd)

    operator fun String.invoke() = call(this)

    suspend fun exit() = worker.exit()
}

interface Call {
    suspend fun execute()
    fun result(): Flow<String>
}

suspend fun Call.print() = result().collect { println(it) }

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

    private val commandEndMarker = UUID.randomUUID().toString()

    private val process = ProcessBuilder("/bin/sh").apply {
        directory(workingDir)
        environment().putAll(environment)
    }.start()

    private val main = broadcast {
        val errorJob = launch {
            process.errorStream
                .asRawFlow { Raw.Error(it) }
                .collect {
                    send(it)
                }
        }
        val outputJob = launch {
            process.inputStream
                .asRawFlow { Raw.Line(it) }
                .collect { send(it) }
        }
        awaitClose {
            errorJob.cancel()
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
                is Raw.Error -> System.err.println(it.data).let { null }
                is Raw.ExitCode -> when (val code = it.data ?: 1) {
                    0 -> null
                    else -> exitProcess(code)
                }
                Raw.Terminate -> null
            }
        }

    suspend fun run(cmd: String) = main.asFlow()
        .onStart { processInput.send(cmd.withEndMarker()) }
        .takeWhile { it != Raw.Terminate }
        .collect { output ->
            when (output) {
                is Raw.Error -> System.err.println(output.data)
                is Raw.ExitCode -> (output.data ?: 1).takeIf { it != 0 }?.let { exitProcess(it) }
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

    private inline fun InputStream.asRawFlow(crossinline block: (String) -> Raw) = bufferedReader()
        .lineSequence()
        .asFlow()
        .transform { emitNextLine(it, block) }

    private suspend inline fun FlowCollector<Raw>.emitNextLine(
        line: String,
        block: (String) -> Raw
    ) = when {
        line.startsWith(commandEndMarker) -> {
            emit(Raw.ExitCode(line.replaceBefore("|", "").substring(1).toIntOrNull()))
            emit(Raw.Terminate)
        }
        else -> emit(block(line))
    }

    private fun BufferedWriter.appendLine(line: String) = apply {
        write(line)
        newLine()
    }

    private fun String.withEndMarker(): String {
        return "$this && echo \"$commandEndMarker|$?\" || echo \"$commandEndMarker|$?\" 1>&2"
    }

    private sealed class Raw {
        data class Line(val data: String) : Raw()

        data class Error(val data: String) : Raw()

        data class ExitCode(val data: Int?) : Raw()

        object Terminate : Raw()
    }
}