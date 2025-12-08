package com.whatap.apk2project.utils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

object ProcessUtils {

    data class ProcessResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
        val timedOut: Boolean = false
    ) {
        val isSuccess: Boolean get() = exitCode == 0 && !timedOut
    }

    suspend fun execute(
        command: List<String>,
        workDir: File? = null,
        timeoutSeconds: Long = 300,
        environment: Map<String, String> = emptyMap(),
        onOutput: ((String) -> Unit)? = null
    ): ProcessResult = withContext(Dispatchers.IO) {
        try {
            val processBuilder = ProcessBuilder(command)
                .directory(workDir)
                .redirectErrorStream(false)

            processBuilder.environment().putAll(environment)

            val process = processBuilder.start()

            val stdoutBuilder = StringBuilder()
            val stderrBuilder = StringBuilder()

            // Read stdout in separate thread
            val stdoutThread = Thread {
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        stdoutBuilder.appendLine(line)
                        onOutput?.invoke(line)
                    }
                }
            }

            // Read stderr in separate thread
            val stderrThread = Thread {
                process.errorStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        stderrBuilder.appendLine(line)
                    }
                }
            }

            stdoutThread.start()
            stderrThread.start()

            val completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)

            if (!completed) {
                process.destroyForcibly()
                return@withContext ProcessResult(
                    exitCode = -1,
                    stdout = stdoutBuilder.toString(),
                    stderr = stderrBuilder.toString(),
                    timedOut = true
                )
            }

            stdoutThread.join(5000)
            stderrThread.join(5000)

            ProcessResult(
                exitCode = process.exitValue(),
                stdout = stdoutBuilder.toString(),
                stderr = stderrBuilder.toString()
            )
        } catch (e: Exception) {
            ProcessResult(
                exitCode = -1,
                stdout = "",
                stderr = e.message ?: "Unknown error"
            )
        }
    }

    fun findExecutable(name: String): String? {
        // Check if it's an absolute path
        val file = File(name)
        if (file.exists() && file.canExecute()) {
            return file.absolutePath
        }

        // Search in PATH
        val pathEnv = System.getenv("PATH") ?: return null
        val paths = pathEnv.split(File.pathSeparator)

        for (path in paths) {
            val executable = File(path, name)
            if (executable.exists() && executable.canExecute()) {
                return executable.absolutePath
            }
            // Try with common extensions on Windows
            if (System.getProperty("os.name").lowercase().contains("windows")) {
                listOf(".exe", ".bat", ".cmd").forEach { ext ->
                    val execWithExt = File(path, "$name$ext")
                    if (execWithExt.exists() && execWithExt.canExecute()) {
                        return execWithExt.absolutePath
                    }
                }
            }
        }

        return null
    }

    fun isCommandAvailable(command: String): Boolean {
        return findExecutable(command) != null
    }
}
