package ru.krikun.kotlin.shell

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import java.io.File

fun shell(
    workingDir: File,
    environment: Map<String, String> = mapOf(),
    exitOnError: Boolean = true,
    block: suspend Shell.() -> Unit
): Int = runBlocking { Shell(workingDir, environment, exitOnError).apply { block() }.exit() }

suspend inline fun Call.output(crossinline action: suspend (String) -> Unit): Int? {
    return output().collectWithExitCode(action)
}

suspend fun Call.printOutput() = output { println(it) }

/**
 * Return list of string or null if error code is not success.
 */
suspend inline fun Call.lines(successErrorCode: Int = 0): List<String>? {
    val list = mutableListOf<String>()
    return when (output().collectWithExitCode { list.add(it) }) {
        successErrorCode -> list
        else -> null
    }
}

suspend inline fun Flow<Output>.collectWithExitCode(crossinline action: suspend (String) -> Unit): Int? {
    var exitCode: Int? = null
    onEach { (it as? Output.ExitCode)?.let { code -> exitCode = code.data } }
        .mapNotNull { (it as? Output.Line)?.data }
        .collect(action)
    return exitCode
}

