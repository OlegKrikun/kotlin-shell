package ru.krikun.kotlin.shell.internal

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.flow.single
import kotlinx.coroutines.flow.takeWhile
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

private const val MAX_WORKER_THREAD = 3

internal class Worker(
    workingDir: File,
    environment: Map<String, String>,
    private val executable: String,
    private val exitOnError: Boolean,
) {
    private val semaphore = Semaphore(1)

    private val process = WorkerProcess(workingDir, environment, executable)

    fun run(cmd: String): Flow<Output> = flow<Output> {
        semaphore.acquire()
        process.output
            .onSubscription { process.input.send(cmd) }
            .takeWhile { it != null }
            .collect { output ->
                output?.let {
                    it.takeIf { exitOnError }?.let { o -> (o as? Output.ExitCode)?.exitOnError() }
                    emit(it)
                }
            }
        semaphore.release()
    }

    fun run(list: List<String>): Flow<Flow<Output>> = flow {
        val workingDir = run("pwd").filterIsInstance<Output.Line>().single().data.let { File(it) }
        val environment = run("env").filterIsInstance<Output.Line>().toList().associate {
            it.data.split("=", limit = 2).let { (key, value) -> key to value }
        }

        val queue = ConcurrentLinkedQueue<Worker>()
        val count = AtomicInteger(list.size)
        for (cmd in list) {
            val currentWorker = queue.poll()
                ?: Worker(workingDir, environment, executable, exitOnError)
            val flow = currentWorker.run(cmd).onCompletion { _ ->
                queue.offer(currentWorker)
                if (count.decrementAndGet() == 0) {
                    queue.forEach { it.exit() }
                }
            }
            emit(flow)
        }
    }

    fun exit() = process.exit()

    private fun Output.ExitCode.exitOnError() = (data ?: 1)
        .takeIf { it != 0 }
        ?.let { exitProcess(it) }

    private class WorkerProcess(
        workingDir: File,
        environment: Map<String, String>,
        executable: String,
    ) {
        @OptIn(DelicateCoroutinesApi::class)
        private val scope = object : CoroutineScope {
            override val coroutineContext = newFixedThreadPoolContext(
                MAX_WORKER_THREAD,
                "WorkerProcess",
            ) + Job()
        }

        private val process = ProcessBuilder(executable.split(" ")).apply {
            directory(workingDir)
            environment().putAll(environment)
        }.start()

        private val marker = UUID.randomUUID().toString()

        val output = MutableSharedFlow<Output?>(extraBufferCapacity = Channel.UNLIMITED)

        init {
            scope.launch { readStream(process.inputStream) { Output.Line(it) } }
            scope.launch { readStream(process.errorStream) { Output.Error(it) } }
        }

        val input: SendChannel<String> = Channel<String>().also { channel ->
            scope.launch {
                process.outputStream.bufferedWriter().use { writer ->
                    channel.consumeEach { writer.line(it.withMarker(marker)) }
                    writer.line("exit")
                }
            }
        }

        fun exit(): Int {
            input.close()
            scope.cancel()
            return process.waitFor()
        }

        private suspend fun readStream(
            stream: InputStream,
            factory: (String) -> Output,
        ) = stream.bufferedReader().lineSequence().forEach { line ->
            when {
                line.contains(marker) -> {
                    val list = line.split(marker, limit = 2)
                    list.findLastOutputLine()?.let { output.emit(factory(it)) }
                    output.emit(Output.ExitCode(list.findExitCode()))
                    output.emit(null)
                }

                else -> output.emit(factory(line))
            }
        }

        private fun BufferedWriter.line(line: String) {
            write(line)
            newLine()
            flush()
        }

        private fun String.withMarker(
            marker: String,
        ) = "$this && echo $marker$? || echo $marker$? 1>&2"

        private fun List<String>.findLastOutputLine() = takeIf { it.size > 1 }
            ?.first()
            ?.takeIf { it.isNotEmpty() }

        private fun List<String>.findExitCode() = last().toIntOrNull()
    }
}
