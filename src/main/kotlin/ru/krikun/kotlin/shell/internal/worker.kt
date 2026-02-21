package ru.krikun.kotlin.shell.internal

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.flow.single
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.newFixedThreadPoolContext
import kotlinx.coroutines.sync.Semaphore
import ru.krikun.kotlin.shell.CommandFailedException
import ru.krikun.kotlin.shell.Output
import java.io.BufferedWriter
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

private const val MAX_WORKER_THREAD = 3
private const val OUTPUT_BUFFER_SIZE = 4096

internal class Worker(
    workingDir: File,
    environment: Map<String, String>,
    private val executable: String,
    private val exitOnError: Boolean,
) {
    private val semaphore = Semaphore(1)

    private val process = WorkerProcess(workingDir, environment, executable)

    fun run(cmd: String): Flow<Output> = channelFlow {
        semaphore.acquire()
        try {
            val collectJob = launch {
                process.output
                    .onSubscription { process.input.send(cmd) }
                    .takeWhile { it != null }
                    .collect { output ->
                        output?.let {
                            it.takeIf { exitOnError }?.let { o ->
                                (o as? Output.ExitCode)
                                    ?.exitOnError()
                            }
                            send(it)
                        }
                    }
            }
            val deathJob = launch {
                process.processDeath.await()
                collectJob.cancel()
            }
            collectJob.join()
            deathJob.cancel()
        } finally {
            semaphore.release()
        }
    }

    fun run(list: List<String>): Flow<Flow<Output>> = flow {
        val allWorkers = ConcurrentLinkedQueue<Worker>()
        val pool = ConcurrentLinkedQueue<Worker>()
        val remaining = AtomicInteger(list.size)

        var success = false
        try {
            val wd = run("pwd")
                .filterIsInstance<Output.Line>()
                .single().data.let { File(it) }
            val env = run("env")
                .filterIsInstance<Output.Line>()
                .toList().associate {
                    it.data.split("=", limit = 2)
                        .let { (k, v) -> k to v }
                }

            for (cmd in list) {
                val innerFlow = flow<Output> {
                    val worker = pool.poll()
                        ?: Worker(wd, env, executable, exitOnError)
                            .also { allWorkers.add(it) }
                    try {
                        worker.run(cmd).collect { emit(it) }
                    } finally {
                        pool.offer(worker)
                        if (remaining.decrementAndGet() == 0) {
                            allWorkers.forEach { it.exit() }
                        }
                    }
                }
                emit(innerFlow)
            }
            success = true
        } finally {
            if (!success) {
                allWorkers.forEach { it.exit() }
            }
        }
    }

    fun exit() = process.exit()

    private fun Output.ExitCode.exitOnError() = (data ?: 1)
        .takeIf { it != 0 }
        ?.let { throw CommandFailedException(it) }

    private class WorkerProcess(
        workingDir: File,
        environment: Map<String, String>,
        executable: String,
    ) {
        @OptIn(DelicateCoroutinesApi::class)
        private val dispatcher = newFixedThreadPoolContext(
            MAX_WORKER_THREAD,
            "WorkerProcess",
        )

        private val scope = CoroutineScope(dispatcher + Job())

        private val process = ProcessBuilder(executable.split(" ")).apply {
            directory(workingDir)
            environment().putAll(environment)
        }.start()

        private val marker = UUID.randomUUID().toString()

        val processDeath = CompletableDeferred<Unit>()

        val output = MutableSharedFlow<Output?>(
            extraBufferCapacity = OUTPUT_BUFFER_SIZE,
        )

        init {
            scope.launch { readStream(process.inputStream) { Output.Line(it) } }
            scope.launch { readStream(process.errorStream) { Output.Error(it) } }
        }

        val input: SendChannel<String> = Channel<String>().also { channel ->
            scope.launch {
                try {
                    process.outputStream.bufferedWriter().use { writer ->
                        channel.consumeEach {
                            writer.line(it.withMarker(marker))
                        }
                        writer.line("exit")
                    }
                } catch (_: IOException) {
                    processDeath.complete(Unit)
                    channel.close()
                }
            }
        }

        fun exit(): Int {
            input.close()
            processDeath.complete(Unit)
            scope.cancel()
            val exitCode = process.waitFor()
            dispatcher.close()
            return exitCode
        }

        private suspend fun readStream(
            stream: InputStream,
            factory: (String) -> Output,
        ) = stream.bufferedReader().use { reader ->
            try {
                reader.lineSequence().forEach { processLine(it, factory) }
            } finally {
                processDeath.complete(Unit)
            }
        }

        private suspend fun processLine(
            line: String,
            factory: (String) -> Output,
        ) = when {
            line.contains(marker) -> {
                val list = line.split(marker, limit = 2)
                list.findLastOutputLine()?.let {
                    output.emit(factory(it))
                }
                output.emit(Output.ExitCode(list.findExitCode()))
                output.emit(null)
            }

            else -> output.emit(factory(line))
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
