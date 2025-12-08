package com.whatap.apk2project.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.types.path
import com.whatap.apk2project.fixer.CodeFixer
import com.whatap.apk2project.utils.Logger
import java.nio.file.Path

class FixCommand : CliktCommand(
    name = "fix",
    help = """
        Fix decompilation errors in Java source files.

        Applies automatic corrections to common JADX decompilation issues.

        Example:
          apk2project fix ./project
          apk2project fix ./project/app/src/main/java
    """.trimIndent()
) {
    private val projectPath by argument(
        name = "project",
        help = "Path to generated project or source directory"
    ).path(mustExist = true)

    override fun run() {
        Logger.header("Fixing Decompilation Errors")

        val sourceDir = findSourceDir(projectPath)
        if (sourceDir == null) {
            Logger.error("Could not find Java source directory")
            return
        }

        Logger.info("Source directory: $sourceDir")

        val fixer = CodeFixer()
        val result = fixer.fixSourceDirectory(sourceDir.toFile())

        Logger.info("────────────────────────────────────────────────")
        Logger.success("Fix complete!")
        Logger.info("Fixed files: ${result.fixedFiles}")
        Logger.info("Total fixes: ${result.totalFixes}")
        if (result.stubsGenerated > 0) {
            Logger.info("Stubs generated: ${result.stubsGenerated}")
        }
    }

    private fun findSourceDir(path: Path): Path? {
        // Check if this is directly a source directory
        val javaDir = path.resolve("app/src/main/java")
        if (javaDir.toFile().exists()) {
            return javaDir
        }

        // Check if path itself is the source directory
        if (path.toFile().isDirectory) {
            val files = path.toFile().listFiles { f -> f.extension == "java" }
            if (files != null && files.isNotEmpty()) {
                return path
            }
            // Check subdirectories for java files
            path.toFile().walkTopDown().find { it.extension == "java" }?.let {
                return it.parentFile.toPath()
            }
        }

        return null
    }
}
