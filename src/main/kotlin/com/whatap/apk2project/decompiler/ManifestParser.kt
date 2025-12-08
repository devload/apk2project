package com.whatap.apk2project.decompiler

import com.whatap.apk2project.models.*
import com.whatap.apk2project.utils.FileUtils
import com.whatap.apk2project.utils.Logger
import com.whatap.apk2project.utils.ProcessUtils
import org.jdom2.Element
import org.jdom2.input.SAXBuilder
import java.io.File
import java.io.StringReader

class ManifestParser {
    private val androidNs = "http://schemas.android.com/apk/res/android"

    suspend fun parseFromApk(apkFile: File, workDir: File): ApkInfo? {
        Logger.debug("Parsing AndroidManifest.xml from ${apkFile.name}")

        // First try to extract binary manifest and decode it
        val manifestXml = extractAndDecodeManifest(apkFile, workDir)
            ?: return null

        return parseManifestXml(manifestXml, apkFile)
    }

    private suspend fun extractAndDecodeManifest(apkFile: File, workDir: File): String? {
        // Use aapt2 or apktool to decode binary XML
        val aapt2 = ProcessUtils.findExecutable("aapt2")
        val aapt = ProcessUtils.findExecutable("aapt")

        if (aapt2 != null || aapt != null) {
            val tool = aapt2 ?: aapt!!
            val result = ProcessUtils.execute(
                listOf(tool, "dump", "xmltree", apkFile.absolutePath, "--file", "AndroidManifest.xml"),
                workDir
            )

            if (result.isSuccess) {
                return convertXmlTreeToXml(result.stdout)
            }
        }

        // Fallback: use apktool if available
        val apktoolPath = ProcessUtils.findExecutable("apktool")
        if (apktoolPath != null) {
            Logger.debug("Using apktool at: $apktoolPath")
            val tempDir = File(workDir, "apktool_temp")
            tempDir.mkdirs()

            // apktool can be a shell script wrapper or JAR
            val result = ProcessUtils.execute(
                listOf(apktoolPath, "d", apkFile.absolutePath, "-o", tempDir.absolutePath, "-f", "-s"),
                workDir,
                timeoutSeconds = 180
            )

            Logger.debug("apktool exit code: ${result.exitCode}")
            if (result.exitCode != 0) {
                Logger.debug("apktool stderr: ${result.stderr}")
            }

            val manifestFile = File(tempDir, "AndroidManifest.xml")
            if (manifestFile.exists()) {
                Logger.debug("Found AndroidManifest.xml at: ${manifestFile.absolutePath}")
                return manifestFile.readText()
            }
        }

        Logger.warn("Could not decode AndroidManifest.xml")
        return null
    }

    private fun convertXmlTreeToXml(xmlTree: String): String {
        // Parse aapt xmltree output and reconstruct XML
        val builder = StringBuilder()
        builder.appendLine("""<?xml version="1.0" encoding="utf-8"?>""")

        val lines = xmlTree.lines()
        val elementStack = mutableListOf<String>()

        for (line in lines) {
            when {
                line.contains("E:") -> {
                    val elementName = line.substringAfter("E:").substringBefore(" ").trim()
                    if (elementStack.isNotEmpty()) {
                        builder.appendLine(">")
                    }
                    val indent = "  ".repeat(elementStack.size)
                    builder.append("$indent<$elementName")
                    elementStack.add(elementName)
                }
                line.contains("A:") -> {
                    val attrPart = line.substringAfter("A:")
                    val attrName = attrPart.substringBefore("(").substringBefore("=").trim()
                    val attrValue = attrPart.substringAfter("=\"").substringBefore("\"")
                    builder.append(" $attrName=\"$attrValue\"")
                }
            }
        }

        // Close all open elements
        while (elementStack.isNotEmpty()) {
            val element = elementStack.removeLast()
            val indent = "  ".repeat(elementStack.size)
            builder.appendLine("/>")
        }

        return builder.toString()
    }

    fun parseManifestXml(xmlContent: String, apkFile: File): ApkInfo? {
        return try {
            val saxBuilder = SAXBuilder()
            val document = saxBuilder.build(StringReader(xmlContent))
            val root = document.rootElement

            val packageName = root.getAttributeValue("package") ?: "unknown"
            val versionCode = root.getAttributeValue("versionCode", androidNs)?.toIntOrNull() ?: 1
            val versionName = root.getAttributeValue("versionName", androidNs) ?: "1.0"

            val usesSdkElement = root.getChild("uses-sdk")
            val minSdk = usesSdkElement?.getAttributeValue("minSdkVersion", androidNs)?.toIntOrNull() ?: 21
            val targetSdk = usesSdkElement?.getAttributeValue("targetSdkVersion", androidNs)?.toIntOrNull() ?: 34

            val applicationElement = root.getChild("application")
            val applicationName = applicationElement?.getAttributeValue("name", androidNs)

            val permissions = root.getChildren("uses-permission").mapNotNull {
                it.getAttributeValue("name", androidNs)
            }

            val activities = applicationElement?.getChildren("activity")?.map { parseActivity(it) } ?: emptyList()
            val services = applicationElement?.getChildren("service")?.map { parseService(it) } ?: emptyList()
            val receivers = applicationElement?.getChildren("receiver")?.map { parseReceiver(it) } ?: emptyList()
            val providers = applicationElement?.getChildren("provider")?.map { parseProvider(it) } ?: emptyList()

            val usesFeatures = root.getChildren("uses-feature").mapNotNull {
                it.getAttributeValue("name", androidNs)
            }

            // Count DEX files in APK
            val dexFiles = FileUtils.listZipEntries(apkFile).filter { it.endsWith(".dex") }
            val nativeLibs = FileUtils.listZipEntries(apkFile).filter { it.endsWith(".so") }

            ApkInfo(
                file = apkFile,
                packageName = packageName,
                versionName = versionName,
                versionCode = versionCode,
                minSdk = minSdk,
                targetSdk = targetSdk,
                compileSdk = targetSdk,
                applicationName = applicationName,
                permissions = permissions,
                activities = activities,
                services = services,
                receivers = receivers,
                providers = providers,
                usesFeatures = usesFeatures,
                nativeLibraries = nativeLibs,
                dexCount = dexFiles.size,
                isMultiDex = dexFiles.size > 1,
                fileSizeMb = FileUtils.getFileSizeMb(apkFile)
            )
        } catch (e: Exception) {
            Logger.error("Failed to parse manifest: ${e.message}")
            null
        }
    }

    private fun parseActivity(element: Element): ActivityInfo {
        return ActivityInfo(
            name = element.getAttributeValue("name", androidNs) ?: "",
            exported = element.getAttributeValue("exported", androidNs)?.toBoolean() ?: false,
            launchMode = element.getAttributeValue("launchMode", androidNs),
            intentFilters = element.getChildren("intent-filter").map { parseIntentFilter(it) }
        )
    }

    private fun parseService(element: Element): ServiceInfo {
        return ServiceInfo(
            name = element.getAttributeValue("name", androidNs) ?: "",
            exported = element.getAttributeValue("exported", androidNs)?.toBoolean() ?: false,
            intentFilters = element.getChildren("intent-filter").map { parseIntentFilter(it) }
        )
    }

    private fun parseReceiver(element: Element): ReceiverInfo {
        return ReceiverInfo(
            name = element.getAttributeValue("name", androidNs) ?: "",
            exported = element.getAttributeValue("exported", androidNs)?.toBoolean() ?: false,
            intentFilters = element.getChildren("intent-filter").map { parseIntentFilter(it) }
        )
    }

    private fun parseProvider(element: Element): ProviderInfo {
        return ProviderInfo(
            name = element.getAttributeValue("name", androidNs) ?: "",
            authorities = element.getAttributeValue("authorities", androidNs),
            exported = element.getAttributeValue("exported", androidNs)?.toBoolean() ?: false
        )
    }

    private fun parseIntentFilter(element: Element): IntentFilter {
        val actions = element.getChildren("action").mapNotNull {
            it.getAttributeValue("name", androidNs)
        }
        val categories = element.getChildren("category").mapNotNull {
            it.getAttributeValue("name", androidNs)
        }
        val data = element.getChildren("data").map { dataElement ->
            IntentData(
                scheme = dataElement.getAttributeValue("scheme", androidNs),
                host = dataElement.getAttributeValue("host", androidNs),
                path = dataElement.getAttributeValue("path", androidNs),
                mimeType = dataElement.getAttributeValue("mimeType", androidNs)
            )
        }
        return IntentFilter(actions, categories, data)
    }

    private fun findApktool(): String? {
        // Check in resources
        val resourceApktool = javaClass.getResource("/tools/apktool.jar")
        if (resourceApktool != null) {
            return resourceApktool.path
        }

        // Check in PATH
        return ProcessUtils.findExecutable("apktool")
    }

    companion object {
        fun Element.getAttributeValue(name: String, namespace: String): String? {
            return this.getAttribute(name, org.jdom2.Namespace.getNamespace("android", namespace))?.value
        }
    }
}
