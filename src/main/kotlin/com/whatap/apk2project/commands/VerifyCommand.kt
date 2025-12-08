package com.whatap.apk2project.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.file
import com.whatap.apk2project.models.VerificationResult
import com.whatap.apk2project.utils.Logger
import com.whatap.apk2project.verifier.ProjectVerifier
import kotlinx.coroutines.runBlocking
import java.io.File

class VerifyCommand : CliktCommand(
    name = "verify",
    help = "Verify if a generated project can build"
) {
    private val projectDir by argument("PROJECT", help = "Path to the project directory")
        .file(mustExist = true, canBeFile = false)

    private val verbose by option("-v", "--verbose", help = "Verbose output")
        .flag(default = false)

    override fun run(): Unit = runBlocking {
        Logger.verbose = verbose

        // Basic validation
        val buildGradle = File(projectDir, "app/build.gradle")
        if (!buildGradle.exists()) {
            val rootBuildGradle = File(projectDir, "build.gradle")
            if (!rootBuildGradle.exists()) {
                Logger.error("Not a valid Android project: ${projectDir.absolutePath}")
                Logger.info("Expected 'app/build.gradle' or 'build.gradle' to exist")
                return@runBlocking
            }
        }

        val verifier = ProjectVerifier()
        val result = verifier.verify(projectDir)

        when (result) {
            is VerificationResult.Success -> {
                if (result.buildable) {
                    Logger.header("Verification Passed")
                    Logger.success("Project appears buildable!")
                    Logger.info("All ${result.steps.count { it.passed }}/${result.steps.size} checks passed")
                } else {
                    Logger.header("Verification Complete")
                    Logger.warn("Project may need fixes")
                    Logger.info("${result.steps.count { it.passed }}/${result.steps.size} checks passed")
                }
            }

            is VerificationResult.Failure -> {
                Logger.header("Verification Failed")
                Logger.error(result.error)

                val failedSteps = result.steps.filter { !it.passed }
                if (failedSteps.isNotEmpty()) {
                    Logger.info("Failed steps:")
                    failedSteps.forEach { step ->
                        Logger.error("  ✗ ${step.name}: ${step.message}")
                        step.suggestions.forEach { suggestion ->
                            Logger.step("    → $suggestion")
                        }
                    }
                }
            }
        }
    }
}
