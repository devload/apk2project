package com.whatap.apk2project.decompiler

import com.whatap.apk2project.utils.FileUtils
import com.whatap.apk2project.utils.Logger
import java.io.File
import java.util.zip.ZipFile

class DexExtractor {

    data class ExtractionResult(
        val dexFiles: List<File>,
        val nativeLibraries: Map<String, List<File>>, // ABI -> .so files
        val assets: List<File>,
        val otherFiles: List<File>
    )

    fun extract(apkFile: File, outputDir: File): ExtractionResult {
        Logger.debug("Extracting DEX files from ${apkFile.name}")

        val dexDir = File(outputDir, "dex")
        val nativeDir = File(outputDir, "native")
        val assetsDir = File(outputDir, "assets")

        dexDir.mkdirs()
        nativeDir.mkdirs()
        assetsDir.mkdirs()

        val dexFiles = mutableListOf<File>()
        val nativeLibraries = mutableMapOf<String, MutableList<File>>()
        val assets = mutableListOf<File>()
        val otherFiles = mutableListOf<File>()

        ZipFile(apkFile).use { zip ->
            zip.entries().asSequence().forEach { entry ->
                when {
                    // DEX files
                    entry.name.endsWith(".dex") -> {
                        val dexFile = File(dexDir, entry.name)
                        extractEntry(zip, entry.name, dexFile)
                        dexFiles.add(dexFile)
                        Logger.debug("Extracted DEX: ${entry.name}")
                    }

                    // Native libraries
                    entry.name.startsWith("lib/") && entry.name.endsWith(".so") -> {
                        // Extract ABI from path: lib/arm64-v8a/libfoo.so
                        val parts = entry.name.split("/")
                        if (parts.size >= 3) {
                            val abi = parts[1]
                            val libName = parts.last()
                            val abiDir = File(nativeDir, abi)
                            abiDir.mkdirs()
                            val libFile = File(abiDir, libName)
                            extractEntry(zip, entry.name, libFile)

                            nativeLibraries.getOrPut(abi) { mutableListOf() }.add(libFile)
                            Logger.debug("Extracted native lib: $abi/$libName")
                        }
                    }

                    // Assets
                    entry.name.startsWith("assets/") && !entry.isDirectory -> {
                        val relativePath = entry.name.removePrefix("assets/")
                        val assetFile = File(assetsDir, relativePath)
                        extractEntry(zip, entry.name, assetFile)
                        assets.add(assetFile)
                    }

                    // Other relevant files
                    entry.name == "AndroidManifest.xml" ||
                    entry.name == "resources.arsc" ||
                    entry.name.startsWith("res/") -> {
                        val file = File(outputDir, entry.name)
                        extractEntry(zip, entry.name, file)
                        otherFiles.add(file)
                    }
                }
            }
        }

        // Sort DEX files by name (classes.dex, classes2.dex, etc.)
        val sortedDexFiles = dexFiles.sortedBy { file ->
            val name = file.nameWithoutExtension
            when {
                name == "classes" -> 0
                name.startsWith("classes") -> {
                    name.removePrefix("classes").toIntOrNull() ?: Int.MAX_VALUE
                }
                else -> Int.MAX_VALUE
            }
        }

        Logger.info("Extracted ${sortedDexFiles.size} DEX files, ${nativeLibraries.values.flatten().size} native libraries")

        return ExtractionResult(
            dexFiles = sortedDexFiles,
            nativeLibraries = nativeLibraries,
            assets = assets,
            otherFiles = otherFiles
        )
    }

    private fun extractEntry(zip: ZipFile, entryName: String, destFile: File) {
        val entry = zip.getEntry(entryName) ?: return

        // Security check for zip slip
        if (!destFile.canonicalPath.startsWith(destFile.parentFile.canonicalFile.canonicalPath)) {
            throw SecurityException("Zip entry outside target dir: $entryName")
        }

        destFile.parentFile?.mkdirs()
        zip.getInputStream(entry).use { input ->
            destFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
    }

    fun countClasses(dexFiles: List<File>): Int {
        // Rough estimate based on DEX file sizes
        // Average class is about 2KB in DEX format
        val totalSize = dexFiles.sumOf { it.length() }
        return (totalSize / 2048).toInt().coerceAtLeast(dexFiles.size * 100)
    }
}
