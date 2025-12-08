package com.whatap.apk2project.utils

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

object FileUtils {

    fun extractZip(zipFile: File, destDir: File): List<File> {
        val extractedFiles = mutableListOf<File>()
        destDir.mkdirs()

        ZipFile(zipFile).use { zip ->
            zip.entries().asSequence().forEach { entry ->
                val destFile = File(destDir, entry.name)

                // Security check for zip slip vulnerability
                if (!destFile.canonicalPath.startsWith(destDir.canonicalPath)) {
                    throw SecurityException("Zip entry outside target dir: ${entry.name}")
                }

                if (entry.isDirectory) {
                    destFile.mkdirs()
                } else {
                    destFile.parentFile?.mkdirs()
                    zip.getInputStream(entry).use { input ->
                        destFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    extractedFiles.add(destFile)
                }
            }
        }

        return extractedFiles
    }

    fun findFiles(dir: File, extension: String): List<File> {
        return dir.walkTopDown()
            .filter { it.isFile && it.extension.equals(extension, ignoreCase = true) }
            .toList()
    }

    fun findFilesByPattern(dir: File, pattern: Regex): List<File> {
        return dir.walkTopDown()
            .filter { it.isFile && pattern.matches(it.name) }
            .toList()
    }

    fun copyDirectory(source: File, dest: File) {
        if (!source.exists()) return

        source.walkTopDown().forEach { file ->
            val relativePath = file.relativeTo(source)
            val destFile = File(dest, relativePath.path)

            if (file.isDirectory) {
                destFile.mkdirs()
            } else {
                destFile.parentFile?.mkdirs()
                Files.copy(file.toPath(), destFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }

    fun deleteDirectory(dir: File): Boolean {
        return dir.deleteRecursively()
    }

    fun createTempDirectory(prefix: String): File {
        return Files.createTempDirectory(prefix).toFile()
    }

    fun getFileSizeMb(file: File): Double {
        return file.length() / (1024.0 * 1024.0)
    }

    fun ensureDirectory(dir: File): File {
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun readTextSafe(file: File): String? {
        return try {
            file.readText()
        } catch (e: Exception) {
            null
        }
    }

    fun writeTextSafe(file: File, content: String): Boolean {
        return try {
            file.parentFile?.mkdirs()
            file.writeText(content)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun getRelativePath(file: File, baseDir: File): String {
        return file.relativeTo(baseDir).path
    }

    fun extractFileFromZip(zipFile: File, entryName: String, destFile: File): Boolean {
        return try {
            ZipFile(zipFile).use { zip ->
                val entry = zip.getEntry(entryName) ?: return false
                destFile.parentFile?.mkdirs()
                zip.getInputStream(entry).use { input ->
                    destFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    fun listZipEntries(zipFile: File): List<String> {
        return ZipFile(zipFile).use { zip ->
            zip.entries().asSequence().map { it.name }.toList()
        }
    }

    fun countFilesInDirectory(dir: File, extension: String? = null): Int {
        return dir.walkTopDown()
            .filter { it.isFile }
            .filter { extension == null || it.extension.equals(extension, ignoreCase = true) }
            .count()
    }
}
