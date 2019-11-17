package ru.krikun.kotlin.shell.internal

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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.single
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.newFixedThreadPoolContext
import kotlinx.coroutines.sync.Semaphore
import ru.krikun.kotlin.shell.Output
import java.io.BufferedWriter
import java.io.File
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.exitProcess

internal class Worker(
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
}

internal suspend inline fun Worker.run(list: List<String>): Flow<Flow<Output>> = flow {
    val workingDir = run("pwd").filterIsInstance<Output.Line>().single().data.let { File(it) }
    val environment = run("env").filterIsInstance<Output.Line>().toList().associate {
        it.data.split("=", limit = 2).let { (key, value) -> key to value }
    }

    val queue = ConcurrentLinkedQueue<Worker>()
    val count = AtomicInteger(list.size)
    for (cmd in list) {
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
