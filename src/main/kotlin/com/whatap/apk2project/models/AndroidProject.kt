package com.whatap.apk2project.models

import java.io.File

data class AndroidProject(
    val projectDir: File,
    val packageName: String,
    val applicationId: String,
    val versionName: String,
    val versionCode: Int,
    val minSdk: Int,
    val targetSdk: Int,
    val compileSdk: Int,
    val hasKotlinCode: Boolean,
    val hasJavaCode: Boolean,
    val hasDataBinding: Boolean,
    val hasViewBinding: Boolean,
    val hasCompose: Boolean,
    val isMinified: Boolean,
    val dependencies: List<Dependency>,
    val sourceFiles: List<SourceFile>,
    val resourceFiles: List<ResourceFile>,
    val assetFiles: List<File>,
    val nativeLibraries: Map<String, List<File>>, // ABI -> .so files
    val manifestInfo: ApkInfo
)

data class SourceFile(
    val file: File,
    val packageName: String,
    val className: String,
    val language: SourceLanguage,
    val isDecompiled: Boolean,
    val hasErrors: Boolean
)

enum class SourceLanguage {
    JAVA,
    KOTLIN
}

data class ResourceFile(
    val file: File,
    val type: ResourceType,
    val qualifiers: List<String>
)

enum class ResourceType {
    LAYOUT,
    DRAWABLE,
    MIPMAP,
    VALUES,
    MENU,
    XML,
    RAW,
    ANIM,
    ANIMATOR,
    COLOR,
    FONT,
    NAVIGATION,
    UNKNOWN
}

data class BuildConfig(
    val agpVersion: String = "8.2.0",
    val kotlinVersion: String = "1.9.21",
    val gradleVersion: String = "8.5",
    val javaVersion: Int = 17,
    val buildToolsVersion: String = "34.0.0"
)

sealed class GenerateResult {
    data class Success(
        val project: AndroidProject,
        val stats: GenerationStats
    ) : GenerateResult()

    data class PartialSuccess(
        val project: AndroidProject,
        val stats: GenerationStats,
        val warnings: List<String>
    ) : GenerateResult()

    data class Failure(
        val error: String,
        val cause: Throwable? = null
    ) : GenerateResult()
}

data class GenerationStats(
    val totalClasses: Int,
    val decompiledClasses: Int,
    val failedClasses: Int,
    val detectedDependencies: Int,
    val resourceFiles: Int,
    val durationMs: Long
)
