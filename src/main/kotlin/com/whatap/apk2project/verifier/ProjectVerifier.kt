package com.whatap.apk2project.verifier

import com.whatap.apk2project.models.VerificationResult
import com.whatap.apk2project.models.VerificationStep
import com.whatap.apk2project.utils.Logger
import com.whatap.apk2project.utils.ProcessUtils
import java.io.File

class ProjectVerifier {

    suspend fun verify(projectDir: File): VerificationResult {
        Logger.header("Verifying Project")
        val steps = mutableListOf<VerificationStep>()

        // Step 1: Check project structure
        Logger.step("Checking project structure...")
        steps.add(verifyProjectStructure(projectDir))

        // Step 2: Check Gradle wrapper
        Logger.step("Checking Gradle wrapper...")
        steps.add(verifyGradleWrapper(projectDir))

        // Step 3: Gradle sync
        Logger.step("Running Gradle sync...")
        steps.add(runGradleSync(projectDir))

        // Step 4: Dependency resolution
        Logger.step("Resolving dependencies...")
        steps.add(resolveDependencies(projectDir))

        // Step 5: Compilation check (optional)
        Logger.step("Checking compilation...")
        steps.add(checkCompilation(projectDir))

        val passedSteps = steps.count { it.passed }
        val totalSteps = steps.size
        val buildable = passedSteps >= totalSteps - 1 // Allow 1 failure

        // Print summary
        Logger.header("Verification Summary")
        steps.forEach { step ->
            val icon = if (step.passed) "✓" else "✗"
            val status = if (step.passed) "PASSED" else "FAILED"
            Logger.info("$icon ${step.name}: $status")
            if (!step.passed && step.suggestions.isNotEmpty()) {
                step.suggestions.forEach { Logger.step("  → $it") }
            }
        }

        return if (buildable) {
            VerificationResult.Success(steps, buildable)
        } else {
            VerificationResult.Failure(
                "Project verification failed",
                steps
            )
        }
    }

    private fun verifyProjectStructure(projectDir: File): VerificationStep {
        val requiredPaths = listOf(
            "app/build.gradle",
            "app/src/main/AndroidManifest.xml",
            "app/src/main/java",
            "settings.gradle",
            "gradle.properties"
        )

        val missingPaths = requiredPaths.filter { path ->
            !File(projectDir, path).exists()
        }

        return if (missingPaths.isEmpty()) {
            VerificationStep(
                name = "Project Structure",
                passed = true,
                message = "All required files present"
            )
        } else {
            VerificationStep(
                name = "Project Structure",
                passed = false,
                message = "Missing files: ${missingPaths.joinToString(", ")}",
                suggestions = listOf("Ensure all project files were generated correctly")
            )
        }
    }

    private fun verifyGradleWrapper(projectDir: File): VerificationStep {
        val gradlew = File(projectDir, "gradlew")
        val gradlewBat = File(projectDir, "gradlew.bat")
        val wrapperProps = File(projectDir, "gradle/wrapper/gradle-wrapper.properties")

        return when {
            !gradlew.exists() || !wrapperProps.exists() -> VerificationStep(
                name = "Gradle Wrapper",
                passed = false,
                message = "Gradle wrapper not found",
                suggestions = listOf(
                    "Run 'gradle wrapper' in the project directory",
                    "Or copy gradle wrapper from another project"
                )
            )
            !gradlew.canExecute() -> {
                gradlew.setExecutable(true)
                VerificationStep(
                    name = "Gradle Wrapper",
                    passed = true,
                    message = "Gradle wrapper present (made executable)"
                )
            }
            else -> VerificationStep(
                name = "Gradle Wrapper",
                passed = true,
                message = "Gradle wrapper present and executable"
            )
        }
    }

    private suspend fun runGradleSync(projectDir: File): VerificationStep {
        val gradlew = getGradleExecutable(projectDir)

        val result = ProcessUtils.execute(
            command = listOf(gradlew, "tasks", "--console=plain", "-q"),
            workDir = projectDir,
            timeoutSeconds = 180
        )

        return if (result.isSuccess) {
            VerificationStep(
                name = "Gradle Sync",
                passed = true,
                message = "Gradle sync successful"
            )
        } else {
            val errorMessage = result.stderr.lines()
                .filter { it.contains("error", ignoreCase = true) || it.contains("failed", ignoreCase = true) }
                .take(3)
                .joinToString("\n")

            VerificationStep(
                name = "Gradle Sync",
                passed = false,
                message = "Gradle sync failed",
                details = errorMessage.ifEmpty { result.stderr.take(500) },
                suggestions = parseSyncErrorSuggestions(result.stderr)
            )
        }
    }

    private suspend fun resolveDependencies(projectDir: File): VerificationStep {
        val gradlew = getGradleExecutable(projectDir)

        val result = ProcessUtils.execute(
            command = listOf(gradlew, "dependencies", "--configuration", "releaseRuntimeClasspath", "--console=plain", "-q"),
            workDir = projectDir,
            timeoutSeconds = 300
        )

        if (result.isSuccess) {
            // Check for unresolved dependencies
            val unresolvedPattern = Regex("""FAILED|Could not resolve|Could not find""")
            val hasUnresolved = unresolvedPattern.containsMatchIn(result.stdout)

            return if (!hasUnresolved) {
                VerificationStep(
                    name = "Dependency Resolution",
                    passed = true,
                    message = "All dependencies resolved"
                )
            } else {
                val unresolved = result.stdout.lines()
                    .filter { it.contains("FAILED") || it.contains("Could not") }
                    .take(5)

                VerificationStep(
                    name = "Dependency Resolution",
                    passed = false,
                    message = "Some dependencies could not be resolved",
                    details = unresolved.joinToString("\n"),
                    suggestions = listOf(
                        "Check dependency versions in build.gradle",
                        "Some libraries may have different artifact names",
                        "Try removing unresolved dependencies"
                    )
                )
            }
        } else {
            return VerificationStep(
                name = "Dependency Resolution",
                passed = false,
                message = "Failed to check dependencies",
                details = result.stderr.take(500)
            )
        }
    }

    private suspend fun checkCompilation(projectDir: File): VerificationStep {
        val gradlew = getGradleExecutable(projectDir)

        // Try to compile Java sources only (faster than full build)
        val result = ProcessUtils.execute(
            command = listOf(gradlew, "compileDebugJavaWithJavac", "--console=plain", "-q"),
            workDir = projectDir,
            timeoutSeconds = 600
        )

        return if (result.isSuccess) {
            VerificationStep(
                name = "Compilation",
                passed = true,
                message = "Java compilation successful"
            )
        } else {
            val errors = parseCompilationErrors(result.stderr + result.stdout)

            VerificationStep(
                name = "Compilation",
                passed = false,
                message = "Compilation errors found: ${errors.size} errors",
                details = errors.take(10).joinToString("\n"),
                suggestions = listOf(
                    "Review decompiled source code for errors",
                    "Some obfuscated code may not compile",
                    "Check for missing imports or dependencies"
                )
            )
        }
    }

    private fun getGradleExecutable(projectDir: File): String {
        val gradlew = File(projectDir, "gradlew")
        return if (gradlew.exists() && gradlew.canExecute()) {
            gradlew.absolutePath
        } else {
            "gradle"
        }
    }

    private fun parseSyncErrorSuggestions(errorOutput: String): List<String> {
        val suggestions = mutableListOf<String>()

        when {
            errorOutput.contains("SDK location not found") ->
                suggestions.add("Set ANDROID_HOME environment variable")

            errorOutput.contains("minimum supported Gradle version") ->
                suggestions.add("Update Gradle wrapper version")

            errorOutput.contains("Unsupported class file major version") ->
                suggestions.add("Update JDK version or change Java compatibility")

            errorOutput.contains("Could not find") ->
                suggestions.add("Check repository configuration in settings.gradle")
        }

        if (suggestions.isEmpty()) {
            suggestions.add("Check build.gradle for syntax errors")
            suggestions.add("Verify Android SDK is installed")
        }

        return suggestions
    }

    private fun parseCompilationErrors(output: String): List<String> {
        val errorPattern = Regex("""(.+\.java):(\d+): error: (.+)""")
        return output.lines()
            .mapNotNull { line ->
                errorPattern.find(line)?.let { match ->
                    val file = match.groupValues[1].substringAfterLast("/")
                    val lineNum = match.groupValues[2]
                    val error = match.groupValues[3]
                    "$file:$lineNum - $error"
                }
            }
            .distinct()
    }
}
