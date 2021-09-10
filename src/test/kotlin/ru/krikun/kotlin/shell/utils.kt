package ru.krikun.kotlin.shell

import java.io.File
import kotlin.io.path.createTempDirectory

fun createTempDir(directory: File? = null): File = createTempDirectory(directory?.toPath()).toFile()
