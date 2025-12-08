package com.whatap.apk2project.decompiler

import com.whatap.apk2project.models.ResourceFile
import com.whatap.apk2project.models.ResourceType
import com.whatap.apk2project.utils.FileUtils
import com.whatap.apk2project.utils.Logger
import com.whatap.apk2project.utils.ProcessUtils
import java.io.File

class ResourceExtractor {

    data class ResourceExtractionResult(
        val resourceDir: File,
        val assetsDir: File,
        val resources: List<ResourceFile>,
        val success: Boolean,
        val errors: List<String>
    )

    suspend fun extract(apkFile: File, outputDir: File): ResourceExtractionResult {
        Logger.debug("Extracting resources from ${apkFile.name}")

        val resourceDir = File(outputDir, "res")
        val assetsDir = File(outputDir, "assets")
        val errors = mutableListOf<String>()

        // Try apktool first (best for resources)
        val apktoolResult = tryApktool(apkFile, outputDir)
        if (apktoolResult != null) {
            return apktoolResult
        }

        // Fallback: use aapt2 to dump resources
        val aapt2Result = tryAapt2(apkFile, outputDir)
        if (aapt2Result != null) {
            return aapt2Result
        }

        // Last resort: simple ZIP extraction
        return extractFromZip(apkFile, outputDir)
    }

    private suspend fun tryApktool(apkFile: File, outputDir: File): ResourceExtractionResult? {
        val apktool = findApktool() ?: return null

        Logger.debug("Using APKTool for resource extraction")

        val apktoolDir = File(outputDir, "apktool_output")
        apktoolDir.mkdirs()

        val command = listOf(
            "java", "-jar", apktool,
            "d", apkFile.absolutePath,
            "-o", apktoolDir.absolutePath,
            "-f",  // Force overwrite
            "-s"   // Don't decode sources (we use JADX)
        )

        val result = ProcessUtils.execute(command, outputDir, timeoutSeconds = 300)

        if (!result.isSuccess && !apktoolDir.exists()) {
            Logger.warn("APKTool failed: ${result.stderr}")
            return null
        }

        val resourceDir = File(apktoolDir, "res")
        val assetsDir = File(apktoolDir, "assets")

        // Collect resource files
        val resources = if (resourceDir.exists()) {
            collectResourceFiles(resourceDir)
        } else {
            emptyList()
        }

        return ResourceExtractionResult(
            resourceDir = resourceDir,
            assetsDir = assetsDir,
            resources = resources,
            success = true,
            errors = emptyList()
        )
    }

    private suspend fun tryAapt2(apkFile: File, outputDir: File): ResourceExtractionResult? {
        val aapt2 = ProcessUtils.findExecutable("aapt2") ?: return null

        Logger.debug("Using aapt2 for resource extraction")

        // aapt2 can dump resources but not extract them directly
        // We'll use it to get resource info and extract from ZIP

        return null // For now, fall back to ZIP extraction
    }

    private fun extractFromZip(apkFile: File, outputDir: File): ResourceExtractionResult {
        Logger.debug("Extracting resources from ZIP (basic extraction)")

        val errors = mutableListOf<String>()
        val resourceDir = File(outputDir, "res")
        val assetsDir = File(outputDir, "assets")

        resourceDir.mkdirs()
        assetsDir.mkdirs()

        try {
            val entries = FileUtils.listZipEntries(apkFile)

            // Extract res/ files
            entries.filter { it.startsWith("res/") && !it.endsWith("/") }.forEach { entry ->
                val destFile = File(outputDir, entry)
                FileUtils.extractFileFromZip(apkFile, entry, destFile)
            }

            // Extract assets/ files
            entries.filter { it.startsWith("assets/") && !it.endsWith("/") }.forEach { entry ->
                val destFile = File(outputDir, entry)
                FileUtils.extractFileFromZip(apkFile, entry, destFile)
            }

        } catch (e: Exception) {
            errors.add("Error extracting resources: ${e.message}")
        }

        val resources = if (resourceDir.exists()) {
            collectResourceFiles(resourceDir)
        } else {
            emptyList()
        }

        return ResourceExtractionResult(
            resourceDir = resourceDir,
            assetsDir = assetsDir,
            resources = resources,
            success = errors.isEmpty(),
            errors = errors
        )
    }

    private fun collectResourceFiles(resourceDir: File): List<ResourceFile> {
        return resourceDir.walkTopDown()
            .filter { it.isFile }
            .map { file ->
                val relativePath = file.relativeTo(resourceDir).path
                val parts = relativePath.split(File.separator)
                val typePart = parts.firstOrNull() ?: ""

                ResourceFile(
                    file = file,
                    type = parseResourceType(typePart),
                    qualifiers = parseQualifiers(typePart)
                )
            }
            .toList()
    }

    private fun parseResourceType(dirName: String): ResourceType {
        val baseName = dirName.split("-").firstOrNull() ?: dirName
        return when (baseName.lowercase()) {
            "layout" -> ResourceType.LAYOUT
            "drawable" -> ResourceType.DRAWABLE
            "mipmap" -> ResourceType.MIPMAP
            "values" -> ResourceType.VALUES
            "menu" -> ResourceType.MENU
            "xml" -> ResourceType.XML
            "raw" -> ResourceType.RAW
            "anim" -> ResourceType.ANIM
            "animator" -> ResourceType.ANIMATOR
            "color" -> ResourceType.COLOR
            "font" -> ResourceType.FONT
            "navigation" -> ResourceType.NAVIGATION
            else -> ResourceType.UNKNOWN
        }
    }

    private fun parseQualifiers(dirName: String): List<String> {
        val parts = dirName.split("-")
        return if (parts.size > 1) {
            parts.drop(1)
        } else {
            emptyList()
        }
    }

    private fun findApktool(): String? {
        // Check bundled apktool
        val bundledPath = System.getProperty("user.dir") + "/tools/apktool.jar"
        if (File(bundledPath).exists()) {
            return bundledPath
        }

        // Check in home directory
        val homePath = System.getProperty("user.home") + "/apktool/apktool.jar"
        if (File(homePath).exists()) {
            return homePath
        }

        // Check if apktool is in PATH (might be a wrapper script)
        val apktoolInPath = ProcessUtils.findExecutable("apktool")
        if (apktoolInPath != null) {
            // Check if it's a JAR or a script
            val file = File(apktoolInPath)
            return if (file.extension == "jar") {
                apktoolInPath
            } else {
                // It's likely a wrapper script, use it differently
                null // Will be handled by direct command execution
            }
        }

        return null
    }

    fun summarizeResources(resources: List<ResourceFile>): Map<ResourceType, Int> {
        return resources.groupBy { it.type }.mapValues { it.value.size }
    }
}
