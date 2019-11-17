package ru.krikun.kotlin.shell

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BroadcastChannel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.broadcast
import kotlinx.coroutines.channels.consume
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.flow.DEFAULT_CONCURRENCY
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.single
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.newFixedThreadPoolContext
import kotlinx.coroutines.sync.Semaphore
import java.io.BufferedWriter
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.exitProcess

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
    suspend fun execute(concurrency: Int = DEFAULT_CONCURRENCY): List<Int?>
    suspend fun output(): Flow<Flow<Output>>
}

private class CallImpl(private val worker: Worker, private val cmd: String) : Call {
    override suspend fun execute() = output().exitCode()
    override fun output() = worker.run(cmd)
}

private class SudoCallImpl(
    private val worker: Worker,
    private val user: String,
    private val cmd: String
) : Call {
    override suspend fun execute() = output().exitCode()
    override fun output() = worker.run(cmd.asUser(user))
}

private class ParallelCallImpl(private val worker: Worker, private val cmd: List<String>) : ParallelCall {
    override suspend fun execute(concurrency: Int) = output().exitCodeList(concurrency)
    override suspend fun output() = worker.run(cmd)
}

private class SudoParallelCallImpl(
    private val worker: Worker,
    private val user: String,
    private val cmd: List<String>
) : ParallelCall {
    override suspend fun execute(concurrency: Int) = output().exitCodeList(concurrency)
    override suspend fun output() = worker.run(cmd.map { it.asUser(user) })
}

private class Worker(
    workingDir: File,
    environment: Map<String, String>,
    val executable: String,
    val exitOnError: Boolean
) {
    private val semaphore = Semaphore(1)

    private val process = WorkerProcess(workingDir, environment, executable)

    fun run(cmd: String): Flow<Output> = flow<Output> {
        semaphore.acquire()
        process.output.openSubscription().apply {
            process.input.send(cmd)
            consume {
                var output = receive()
                while (output != null) {
                    output.takeIf { exitOnError }?.let { (it as? Output.ExitCode)?.exitOnError() }
                    emit(output)
                    output = receive()
                }
            }
        }
        semaphore.release()
    }

    fun exit() = process.exit()

    private fun Output.ExitCode.exitOnError() = (data ?: 1).takeIf { it != 0 }?.let { exitProcess(it) }
}

private class WorkerProcess(
    workingDir: File,
    environment: Map<String, String>,
    executable: String
) {
    private val scope = object : CoroutineScope {
        override val coroutineContext = newFixedThreadPoolContext(3, "WorkerProcess") + Job()
    }

    private val process = ProcessBuilder(executable.split(" ")).apply {
        directory(workingDir)
        environment().putAll(environment)
    }.start()

    private val marker = UUID.randomUUID().toString()

    val output: BroadcastChannel<Output?> = scope.broadcast {
        val stdJob = launch { output(process.inputStream) { Output.Line(it) } }
        val errJob = launch { output(process.errorStream) { Output.Error(it) } }
        awaitClose {
            stdJob.cancel()
            errJob.cancel()
        }
    }

    val input: SendChannel<String> = Channel<String>().also { channel ->
        scope.launch {
            process.outputStream.bufferedWriter().use { writer ->
                channel.consumeEach { writer.line("$it && echo $marker$? || echo $marker$? 1>&2") }
                writer.line("exit")
            }
        }
    }

    fun exit(): Int {
        input.close()
        scope.cancel()
        return process.waitFor()
    }

    private suspend fun ProducerScope<Output?>.output(
        stream: InputStream,
        factory: (String) -> Output
    ) = stream.bufferedReader().lineSequence().forEach { line ->
        when {
            line.contains(marker) -> {
                val list = line.split(marker, limit = 2)
                list.takeIf { it.size > 1 }?.first()?.takeIf { it.isNotEmpty() }?.let { send(factory(it)) }
                send(Output.ExitCode(list.last().toIntOrNull()))
                send(null)
            }
            else -> send(factory(line))
        }
    }

    private fun BufferedWriter.line(line: String) {
        write(line)
        newLine()
        flush()
    }
}

private fun String.asUser(user: String) = "sudo -u $user $this"

private suspend inline fun Worker.run(cmds: List<String>): Flow<Flow<Output>> = flow {
    val workingDir = run("pwd").filterIsInstance<Output.Line>().single().data.let { File(it) }
    val environment = run("env").filterIsInstance<Output.Line>().toList().associate {
        it.data.split("=", limit = 2).let { (key, value) -> key to value }
    }

    val queue = ConcurrentLinkedQueue<Worker>()
    val count = AtomicInteger(cmds.size)
    for (cmd in cmds) {
        val currentWorker = queue.poll() ?: Worker(workingDir, environment, executable, exitOnError)
        val flow = currentWorker.run(cmd).onCompletion {
            queue.offer(currentWorker)
            if (count.decrementAndGet() == 0) {
                queue.forEach { it.exit() }
            }
        }
        emit(flow)
    }
}
