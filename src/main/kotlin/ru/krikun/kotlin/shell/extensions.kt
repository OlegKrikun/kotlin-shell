package ru.krikun.kotlin.shell

import kotlinx.coroutines.flow.DEFAULT_CONCURRENCY
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.flattenMerge
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.single
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import java.io.File

fun shell(
    workingDir: File = currentSystemWorkingDir(),
    environment: Map<String, String> = mapOf(),
    executable: String = Shell.SH,
    exitOnError: Boolean = true,
    block: suspend Shell.() -> Unit
): Int = runBlocking { Shell(workingDir, environment, executable, exitOnError).apply { block() }.exit() }

suspend inline fun Call.output(crossinline action: suspend (String) -> Unit): Int? {
    return output().collectWithExitCode(action)
}

suspend fun Call.printOutput() = output { println(it) }

/**
 * Return list of string or null if exit code is not success.
 */
suspend fun Call.lines(successExitCode: Int = 0): List<String>? {
    val list = mutableListOf<String>()
    return when (output().collectWithExitCode { list.add(it) }) {
        successExitCode -> list
        else -> null
    }
}

suspend inline fun ParallelCall.output(
    concurrency: Int = DEFAULT_CONCURRENCY,
    crossinline action: suspend (String) -> Unit
): List<Int?> {
    return output().collectWithExitCodeList(concurrency, action)
}

suspend fun ParallelCall.printOutput() = output { println(it) }

/**
 * Return list of string or null if any of exit codes is not success.
 */
suspend inline fun ParallelCall.lines(successExitCode: Int = 0): List<String>? {
    val list = mutableListOf<String>()
    val codes = output().collectWithExitCodeList { list.add(it) }
    return when {
        codes.all { it == successExitCode } -> list
        else -> null
    }
}

suspend fun Flow<Output>.exitCode() = filterIsInstance<Output.ExitCode>().single().data

suspend fun Flow<Flow<Output>>.exitCodeList(concurrency: Int = DEFAULT_CONCURRENCY) = flattenMerge(concurrency)
    .filterIsInstance<Output.ExitCode>()
    .map { it.data }
    .toList()

suspend inline fun Flow<Output>.collectWithExitCode(crossinline action: suspend (String) -> Unit): Int? {
    var exitCode: Int? = null
    onEach { (it as? Output.ExitCode)?.let { code -> exitCode = code.data } }
        .mapNotNull {
            when (it) {
                is Output.Line -> it.data
                is Output.Error -> it.data
                else -> null
            }
        }
        .collect(action)
    return exitCode
}

suspend inline fun Flow<Flow<Output>>.collectWithExitCodeList(
    concurrency: Int = DEFAULT_CONCURRENCY,
    crossinline action: suspend (String) -> Unit
): List<Int?> {
    val exitCodeList = mutableListOf<Int?>()
    flattenMerge(concurrency)
        .onEach { (it as? Output.ExitCode)?.let { code -> exitCodeList.add(code.data) } }
        .mapNotNull {
            when (it) {
                is Output.Line -> it.data
                is Output.Error -> it.data
                else -> null
            }
        }
        .collect(action)
    return exitCodeList
}

fun currentSystemWorkingDir(): File = File(System.getProperty("user.dir"))
