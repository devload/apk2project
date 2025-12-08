package com.whatap.apk2project.generator

import com.whatap.apk2project.utils.Logger
import freemarker.template.Configuration
import freemarker.template.TemplateExceptionHandler
import java.io.StringWriter

class TemplateEngine {

    private val freemarker: Configuration = Configuration(Configuration.VERSION_2_3_32).apply {
        setClassLoaderForTemplateLoading(javaClass.classLoader, "/templates")
        defaultEncoding = "UTF-8"
        templateExceptionHandler = TemplateExceptionHandler.RETHROW_HANDLER
        logTemplateExceptions = false
        wrapUncheckedExceptions = true
        fallbackOnNullLoopVariable = false
    }

    fun render(templateName: String, dataModel: Map<String, Any?>): String {
        return try {
            val template = freemarker.getTemplate(templateName)
            val writer = StringWriter()
            template.process(dataModel, writer)
            writer.toString()
        } catch (e: Exception) {
            Logger.error("Failed to render template $templateName: ${e.message}")
            throw e
        }
    }

    fun renderBuildGradle(data: BuildGradleData): String {
        val dataModel = mapOf(
            "agpVersion" to data.agpVersion,
            "kotlinVersion" to data.kotlinVersion,
            "hasKotlin" to data.hasKotlin,
            "hasHilt" to data.hasHilt,
            "hasFirebase" to data.hasFirebase,
            "hasCrashlytics" to data.hasCrashlytics,
            "namespace" to data.namespace,
            "applicationId" to data.applicationId,
            "compileSdk" to data.compileSdk,
            "minSdk" to data.minSdk,
            "targetSdk" to data.targetSdk,
            "versionCode" to data.versionCode,
            "versionName" to data.versionName,
            "javaVersion" to data.javaVersion,
            "minifyEnabled" to data.minifyEnabled,
            "multiDexEnabled" to data.multiDexEnabled,
            "hasVectorDrawables" to data.hasVectorDrawables,
            "hasCompose" to data.hasCompose,
            "buildFeatures" to data.buildFeatures,
            "coreDependencies" to data.coreDependencies,
            "detectedDependencies" to data.detectedDependencies.map {
                mapOf("notation" to it.toGradleNotation(), "confidence" to it.confidence)
            }
        )

        return render("build.gradle.ftl", dataModel)
    }

    fun renderSettingsGradle(projectName: String): String {
        return render("settings.gradle.ftl", mapOf("projectName" to projectName))
    }

    fun renderGradleProperties(): String {
        return render("gradle.properties.ftl", emptyMap())
    }

    fun renderProguardRules(data: ProguardRulesData): String {
        return render("proguard-rules.pro.ftl", mapOf(
            "applicationClass" to data.applicationClass,
            "hasRetrofit" to data.hasRetrofit,
            "hasGson" to data.hasGson,
            "hasOkHttp" to data.hasOkHttp,
            "hasGlide" to data.hasGlide
        ))
    }

    fun renderReadme(data: ReadmeData): String {
        return render("README.md.ftl", mapOf(
            "projectName" to data.projectName,
            "packageName" to data.packageName,
            "versionName" to data.versionName,
            "versionCode" to data.versionCode,
            "minSdk" to data.minSdk,
            "minSdkName" to getAndroidVersionName(data.minSdk),
            "targetSdk" to data.targetSdk,
            "targetSdkName" to getAndroidVersionName(data.targetSdk),
            "generatedDate" to data.generatedDate,
            "sourceApkName" to data.sourceApkName,
            "apkSizeMb" to data.apkSizeMb,
            "totalClasses" to data.totalClasses,
            "decompileSuccessRate" to data.decompileSuccessRate,
            "dependencyCount" to data.dependencyCount,
            "coreDependencies" to data.coreDependencies,
            "detectedDependencies" to data.detectedDependencies.map {
                mapOf("notation" to it.toGradleNotation(), "confidence" to it.confidence)
            },
            "obfuscated" to data.obfuscated,
            "hasNativeLibraries" to data.hasNativeLibraries,
            "nativeLibraries" to data.nativeLibraries
        ))
    }

    private fun getAndroidVersionName(sdk: Int): String {
        return when (sdk) {
            21 -> "5.0 Lollipop"
            22 -> "5.1 Lollipop"
            23 -> "6.0 Marshmallow"
            24 -> "7.0 Nougat"
            25 -> "7.1 Nougat"
            26 -> "8.0 Oreo"
            27 -> "8.1 Oreo"
            28 -> "9 Pie"
            29 -> "10"
            30 -> "11"
            31 -> "12"
            32 -> "12L"
            33 -> "13"
            34 -> "14"
            35 -> "15"
            else -> "API $sdk"
        }
    }
}

data class BuildGradleData(
    val agpVersion: String = "8.2.0",
    val kotlinVersion: String = "1.9.21",
    val hasKotlin: Boolean = false,
    val hasHilt: Boolean = false,
    val hasFirebase: Boolean = false,
    val hasCrashlytics: Boolean = false,
    val namespace: String,
    val applicationId: String,
    val compileSdk: Int,
    val minSdk: Int,
    val targetSdk: Int,
    val versionCode: Int,
    val versionName: String,
    val javaVersion: Int = 17,
    val minifyEnabled: Boolean = false,
    val multiDexEnabled: Boolean = false,
    val hasVectorDrawables: Boolean = true,
    val hasCompose: Boolean = false,
    val buildFeatures: Map<String, Boolean> = emptyMap(),
    val coreDependencies: List<String>,
    val detectedDependencies: List<com.whatap.apk2project.models.Dependency>
)

data class ProguardRulesData(
    val applicationClass: String,
    val hasRetrofit: Boolean = false,
    val hasGson: Boolean = false,
    val hasOkHttp: Boolean = false,
    val hasGlide: Boolean = false
)

data class ReadmeData(
    val projectName: String,
    val packageName: String,
    val versionName: String,
    val versionCode: Int,
    val minSdk: Int,
    val targetSdk: Int,
    val generatedDate: String,
    val sourceApkName: String,
    val apkSizeMb: String,
    val totalClasses: Int,
    val decompileSuccessRate: Int,
    val dependencyCount: Int,
    val coreDependencies: List<String>,
    val detectedDependencies: List<com.whatap.apk2project.models.Dependency>,
    val obfuscated: Boolean,
    val hasNativeLibraries: Boolean,
    val nativeLibraries: Map<String, List<String>>
)
