package com.whatap.apk2project

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.versionOption
import com.whatap.apk2project.commands.AnalyzeCommand
import com.whatap.apk2project.commands.DecompileCommand
import com.whatap.apk2project.commands.FixCommand
import com.whatap.apk2project.commands.GenerateCommand
import com.whatap.apk2project.commands.VerifyCommand
import com.whatap.apk2project.utils.Logger

class Apk2Project : CliktCommand(
    name = "apk2project",
    help = """
        APK to Gradle Project Converter

        Reverse engineer Android APK files into buildable Gradle projects.

        Examples:
          apk2project generate app.apk --output ./project
          apk2project analyze app.apk
          apk2project decompile app.apk
          apk2project verify ./project
    """.trimIndent(),
    printHelpOnEmptyArgs = true
) {
    private val noColor by option("--no-color", help = "Disable colored output")
        .flag(default = false)

    init {
        versionOption("1.0.0")
    }

    override fun run() {
        Logger.useColors = !noColor
    }
}

fun main(args: Array<String>) {
    Apk2Project()
        .subcommands(
            GenerateCommand(),
            AnalyzeCommand(),
            DecompileCommand(),
            VerifyCommand(),
            FixCommand()
        )
        .main(args)
}
