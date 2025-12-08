package com.whatap.apk2project.models

import java.io.File

data class ApkInfo(
    val file: File,
    val packageName: String,
    val versionName: String,
    val versionCode: Int,
    val minSdk: Int,
    val targetSdk: Int,
    val compileSdk: Int,
    val applicationName: String?,
    val permissions: List<String>,
    val activities: List<ActivityInfo>,
    val services: List<ServiceInfo>,
    val receivers: List<ReceiverInfo>,
    val providers: List<ProviderInfo>,
    val usesFeatures: List<String>,
    val nativeLibraries: List<String>,
    val dexCount: Int,
    val isMultiDex: Boolean,
    val fileSizeMb: Double
)

data class ActivityInfo(
    val name: String,
    val exported: Boolean,
    val launchMode: String?,
    val intentFilters: List<IntentFilter>
)

data class ServiceInfo(
    val name: String,
    val exported: Boolean,
    val intentFilters: List<IntentFilter>
)

data class ReceiverInfo(
    val name: String,
    val exported: Boolean,
    val intentFilters: List<IntentFilter>
)

data class ProviderInfo(
    val name: String,
    val authorities: String?,
    val exported: Boolean
)

data class IntentFilter(
    val actions: List<String>,
    val categories: List<String>,
    val data: List<IntentData>
)

data class IntentData(
    val scheme: String?,
    val host: String?,
    val path: String?,
    val mimeType: String?
)
