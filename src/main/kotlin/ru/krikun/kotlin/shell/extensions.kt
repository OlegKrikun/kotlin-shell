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
suspend inline fun Call.lines(successExitCode: Int = 0): List<String>? {
    val list = mutableListOf<String>()
    return when (output().collectWithExitCode { list.add(it) }) {
        successExitCode -> list
        else -> null
    }
}

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

