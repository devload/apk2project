package com.whatap.apk2project.generator

import com.whatap.apk2project.models.ResourceFile
import com.whatap.apk2project.models.ResourceType
import com.whatap.apk2project.utils.FileUtils
import com.whatap.apk2project.utils.Logger
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class ResourceOrganizer {

    data class OrganizeResult(
        val resources: List<ResourceFile>,
        val layouts: Int,
        val drawables: Int,
        val values: Int,
        val others: Int,
        val assets: Int
    )

    fun organize(
        sourceResDir: File,
        sourceAssetsDir: File?,
        targetResDir: File,
        targetAssetsDir: File
    ): OrganizeResult {
        Logger.info("Organizing resources...")

        val resources = mutableListOf<ResourceFile>()
        var layouts = 0
        var drawables = 0
        var values = 0
        var others = 0
        var assets = 0

        // Copy res/ directory
        if (sourceResDir.exists()) {
            sourceResDir.walkTopDown()
                .filter { it.isFile }
                .forEach { file ->
                    try {
                        val relativePath = file.relativeTo(sourceResDir).path
                        val targetFile = File(targetResDir, relativePath)
                        targetFile.parentFile?.mkdirs()

                        Files.copy(file.toPath(), targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING)

                        val resourceType = getResourceType(file.parentFile?.name ?: "")
                        resources.add(ResourceFile(
                            file = targetFile,
                            type = resourceType,
                            qualifiers = getQualifiers(file.parentFile?.name ?: "")
                        ))

                        when (resourceType) {
                            ResourceType.LAYOUT -> layouts++
                            ResourceType.DRAWABLE, ResourceType.MIPMAP -> drawables++
                            ResourceType.VALUES -> values++
                            else -> others++
                        }
                    } catch (e: Exception) {
                        Logger.debug("Error copying resource ${file.name}: ${e.message}")
                    }
                }
        }

        // Copy assets/ directory
        if (sourceAssetsDir != null && sourceAssetsDir.exists()) {
            sourceAssetsDir.walkTopDown()
                .filter { it.isFile }
                .forEach { file ->
                    try {
                        val relativePath = file.relativeTo(sourceAssetsDir).path
                        val targetFile = File(targetAssetsDir, relativePath)
                        targetFile.parentFile?.mkdirs()

                        Files.copy(file.toPath(), targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
                        assets++
                    } catch (e: Exception) {
                        Logger.debug("Error copying asset ${file.name}: ${e.message}")
                    }
                }
        }

        Logger.success("Organized $layouts layouts, $drawables drawables, $values value files, $assets assets")

        return OrganizeResult(
            resources = resources,
            layouts = layouts,
            drawables = drawables,
            values = values,
            others = others,
            assets = assets
        )
    }

    fun organizeNativeLibraries(
        sourceNativeDir: Map<String, List<File>>,
        targetJniLibsDir: File
    ): Int {
        var count = 0

        for ((abi, libraries) in sourceNativeDir) {
            val abiDir = File(targetJniLibsDir, abi)
            abiDir.mkdirs()

            for (lib in libraries) {
                try {
                    val targetFile = File(abiDir, lib.name)
                    Files.copy(lib.toPath(), targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    count++
                } catch (e: Exception) {
                    Logger.debug("Error copying native lib ${lib.name}: ${e.message}")
                }
            }
        }

        if (count > 0) {
            Logger.success("Copied $count native libraries")
        }

        return count
    }

    private fun getResourceType(dirName: String): ResourceType {
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

    private fun getQualifiers(dirName: String): List<String> {
        val parts = dirName.split("-")
        return if (parts.size > 1) parts.drop(1) else emptyList()
    }

    fun cleanupResources(resDir: File) {
        // Remove public.xml which can cause issues
        val publicXml = File(resDir, "values/public.xml")
        if (publicXml.exists()) {
            publicXml.delete()
            Logger.debug("Removed public.xml")
        }

        // Rename files with invalid characters
        var renamedCount = 0
        resDir.walkTopDown()
            .filter { it.isFile }
            .toList() // Collect first to avoid concurrent modification
            .forEach { file ->
                val newName = sanitizeResourceFileName(file.name)
                if (newName != file.name) {
                    val newFile = File(file.parentFile, newName)
                    if (!newFile.exists()) {
                        file.renameTo(newFile)
                        renamedCount++
                    } else {
                        // Delete duplicate file with invalid name
                        file.delete()
                    }
                }
            }

        if (renamedCount > 0) {
            Logger.info("Renamed $renamedCount files with invalid characters")
        }

        // Fix common resource issues
        var xmlFixCount = 0
        var deletedCount = 0
        resDir.walkTopDown()
            .filter { it.isFile && it.extension == "xml" }
            .toList()
            .forEach { file ->
                try {
                    // Read bytes to detect BOM
                    val bytes = file.readBytes()
                    var content: String

                    // Remove BOM if present (UTF-8 BOM: EF BB BF)
                    content = if (bytes.size >= 3 &&
                        bytes[0] == 0xEF.toByte() &&
                        bytes[1] == 0xBB.toByte() &&
                        bytes[2] == 0xBF.toByte()) {
                        String(bytes.copyOfRange(3, bytes.size), Charsets.UTF_8)
                    } else {
                        String(bytes, Charsets.UTF_8)
                    }

                    // Remove any content before XML declaration or root element
                    val xmlDeclIndex = content.indexOf("<?xml")
                    val rootTagIndex = content.indexOf("<")
                    if (xmlDeclIndex > 0) {
                        content = content.substring(xmlDeclIndex)
                        xmlFixCount++
                    } else if (rootTagIndex > 0 && xmlDeclIndex == -1) {
                        content = content.substring(rootTagIndex)
                        xmlFixCount++
                    }

                    // Check if file is valid XML (has at least one tag)
                    // Also detect binary XML (Android AXML format) which wasn't properly decoded
                    val isBinaryXml = bytes.size >= 4 &&
                        (bytes[0] == 0x03.toByte() || bytes[0] == 0x00.toByte()) &&
                        (bytes[1] == 0x00.toByte())
                    val hasValidXmlContent = content.trimStart().startsWith("<") &&
                        content.contains(">") &&
                        !content.contains("\u0000") // No null bytes

                    if (isBinaryXml || !hasValidXmlContent || content.isBlank()) {
                        file.delete()
                        deletedCount++
                        return@forEach
                    }

                    // Remove invisible/control characters before XML
                    content = content.replace(Regex("^[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]+"), "")

                    // Fix duplicate XML declarations
                    val declarationCount = content.split("<?xml").size - 1
                    if (declarationCount > 1) {
                        content = content.replaceFirst(
                            Regex("""<\?xml[^?]*\?>[\s\n]*<\?xml"""),
                            "<?xml"
                        )
                        xmlFixCount++
                    }

                    // Write cleaned content
                    file.writeText(content, Charsets.UTF_8)
                } catch (e: Exception) {
                    Logger.debug("Error cleaning ${file.name}: ${e.message}")
                    // Delete corrupted XML files
                    file.delete()
                    deletedCount++
                }
            }

        if (xmlFixCount > 0 || deletedCount > 0) {
            Logger.info("Fixed $xmlFixCount XML files, deleted $deletedCount corrupted files")
        }
    }

    /**
     * Sanitize resource file names to only contain valid characters.
     * Valid characters: lowercase a-z, 0-9, underscore (_)
     */
    private fun sanitizeResourceFileName(fileName: String): String {
        val extension = fileName.substringAfterLast(".", "")
        val baseName = fileName.substringBeforeLast(".", fileName)

        // Replace invalid characters with underscore
        val sanitizedBase = baseName.lowercase()
            .replace(Regex("[^a-z0-9_]"), "_")
            // Remove leading underscores/numbers
            .replace(Regex("^[_0-9]+"), "")
            // Collapse multiple underscores
            .replace(Regex("_+"), "_")
            // Ensure doesn't start with a number
            .let { if (it.isEmpty() || it[0].isDigit()) "res_$it" else it }

        return if (extension.isNotEmpty()) {
            "$sanitizedBase.$extension"
        } else {
            sanitizedBase
        }
    }
}
