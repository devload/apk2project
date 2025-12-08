package com.whatap.apk2project.utils

object Logger {
    var verbose = false
    var useColors = true

    private const val RESET = "\u001B[0m"
    private const val RED = "\u001B[31m"
    private const val GREEN = "\u001B[32m"
    private const val YELLOW = "\u001B[33m"
    private const val BLUE = "\u001B[34m"
    private const val CYAN = "\u001B[36m"
    private const val BOLD = "\u001B[1m"

    fun info(message: String) {
        println(colorize("$BLUE[INFO]$RESET $message"))
    }

    fun success(message: String) {
        println(colorize("$GREEN[✓]$RESET $message"))
    }

    fun warn(message: String) {
        println(colorize("$YELLOW[⚠]$RESET $message"))
    }

    fun error(message: String) {
        println(colorize("$RED[✗]$RESET $message"))
    }

    fun debug(message: String) {
        if (verbose) {
            println(colorize("$CYAN[DEBUG]$RESET $message"))
        }
    }

    fun header(message: String) {
        println()
        println(colorize("$BOLD$BLUE$message$RESET"))
        println(colorize("$BLUE${"─".repeat(50)}$RESET"))
    }

    fun step(message: String) {
        println(colorize("   ├─ $message"))
    }

    fun lastStep(message: String) {
        println(colorize("   └─ $message"))
    }

    fun progress(current: Int, total: Int, message: String) {
        val percentage = if (total > 0) (current * 100 / total) else 0
        val filled = percentage / 4
        val empty = 25 - filled
        val bar = "█".repeat(filled) + "░".repeat(empty)
        print("\r   ├─ $message [$bar] $percentage%")
        if (current == total) println()
    }

    private fun colorize(text: String): String {
        return if (useColors) text else text.replace(Regex("\u001B\\[[;\\d]*m"), "")
    }
}

class ProgressBar(
    private val total: Int,
    private val message: String,
    private val width: Int = 30
) {
    private var current = 0

    fun update(value: Int) {
        current = value.coerceIn(0, total)
        render()
    }

    fun increment() {
        update(current + 1)
    }

    private fun render() {
        val percentage = if (total > 0) (current * 100 / total) else 0
        val filled = (percentage * width / 100).coerceIn(0, width)
        val empty = width - filled
        val bar = "█".repeat(filled) + "░".repeat(empty)
        print("\r   ├─ $message [$bar] $percentage% ($current/$total)")
        if (current == total) println()
    }

    fun complete() {
        update(total)
    }
}
