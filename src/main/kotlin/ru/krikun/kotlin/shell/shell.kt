package ru.krikun.kotlin.shell

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.broadcast
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.single
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.BufferedWriter
import java.io.Closeable
import java.io.File
import java.util.UUID
import kotlin.system.exitProcess

class Shell(workingDir: File, environment: Map<String, String> = mapOf(), exitOnError: Boolean = true) : Closeable {
    private val worker = Worker(workingDir, environment, exitOnError)

    fun call(cmd: String): Call = RegularCall(worker, cmd)

    fun call(vararg cmd: String): Call = RegularCall(worker, cmd.joinCommandSequential())

    fun asUser(user: String, cmd: String): Call = SudoCall(worker, user, cmd)

    fun asUser(user: String, vararg cmd: String): Call = SudoCall(worker, user, cmd.joinCommandSequential())

    suspend fun exit(): Int = worker.exit()

    override fun close() {
        worker.close()
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

private class RegularCall(private val worker: Worker, private val cmd: String) : Call {
    override suspend fun execute() = worker.run(cmd)
    override fun output() = worker.runWithResult(cmd)
}

private class SudoCall(private val worker: Worker, private val user: String, private val cmd: String) : Call {
    override suspend fun execute() = worker.run(cmd.asUser(user))
    override fun output() = worker.runWithResult(cmd.asUser(user))
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
    }.start()

    private val processOutput = broadcast {
        val stdJob = launch {
            process.inputStream.bufferedReader().lineSequence().forEach { line -> output(line) { Output.Line(it) } }
        }
        val errJob = launch {
            process.errorStream.bufferedReader().lineSequence().forEach { line -> output(line) { Output.Error(it) } }
        }
        awaitClose {
            stdJob.cancel()
            errJob.cancel()
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

    fun runWithResult(cmd: String) = processOutput.openSubscription()
        .consumeAsFlow()
        .onStart { processInput.send(cmd.withEndMarker()) }
        .onEach { raw -> raw.takeIf { exitOnError }?.let { (it as? Output.ExitCode)?.exitOnError() } }
        .takeWhile { it != null }
        .transform { emit(it!!) }

    suspend fun run(cmd: String) = runWithResult(cmd).filterIsInstance<Output.ExitCode>().single().data

    fun close() {
        runBlocking(coroutineContext) { exit() }
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

    private suspend inline fun ProducerScope<Output?>.output(line: String, factory: (String) -> Output) = when {
        line.contains(endMarker) -> {
            val list = line.split(endMarker, limit = 2)
            list.takeIf { it.size > 1 }?.first()?.takeIf { it.isNotEmpty() }?.let { send(factory(it)) }
            send(Output.ExitCode(list.last().toIntOrNull()))
            send(null)
        }
        else -> send(factory(line))
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