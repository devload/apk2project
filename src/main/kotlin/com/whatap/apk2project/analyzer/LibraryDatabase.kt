package com.whatap.apk2project.analyzer

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.whatap.apk2project.models.Dependency
import com.whatap.apk2project.models.DetectionSource
import com.whatap.apk2project.utils.Logger
import java.io.InputStreamReader

class LibraryDatabase {

    private val libraries: List<LibraryEntry>
    private val packagePrefixMap: Map<String, LibraryEntry>

    init {
        libraries = loadLibraries()
        packagePrefixMap = libraries.associateBy { it.packagePrefix }
        Logger.debug("Loaded ${libraries.size} library signatures")
    }

    private fun loadLibraries(): List<LibraryEntry> {
        return try {
            val inputStream = javaClass.getResourceAsStream("/library-signatures.json")
            if (inputStream != null) {
                val reader = InputStreamReader(inputStream)
                val data = Gson().fromJson(reader, LibraryData::class.java)
                data.libraries
            } else {
                Logger.warn("library-signatures.json not found, using built-in list")
                getBuiltInLibraries()
            }
        } catch (e: Exception) {
            Logger.warn("Failed to load library signatures: ${e.message}")
            getBuiltInLibraries()
        }
    }

    fun findByPackagePrefix(packageName: String): LibraryEntry? {
        // Try exact match first
        packagePrefixMap[packageName]?.let { return it }

        // Try prefix matching
        for ((prefix, entry) in packagePrefixMap) {
            if (packageName.startsWith(prefix)) {
                return entry
            }
        }

        return null
    }

    fun findByClassName(className: String): LibraryEntry? {
        for (library in libraries) {
            if (library.classSignatures.any { className.startsWith(it) || className == it }) {
                return library
            }
        }
        return null
    }

    fun getAllLibraries(): List<LibraryEntry> = libraries

    fun toDependency(entry: LibraryEntry, confidence: Float = 0.85f): Dependency {
        return Dependency(
            groupId = entry.groupId,
            artifactId = entry.artifactId,
            version = entry.latestVersion,
            confidence = confidence,
            source = DetectionSource.PACKAGE_PREFIX
        )
    }

    private fun getBuiltInLibraries(): List<LibraryEntry> {
        return listOf(
            LibraryEntry("AndroidX Core", "androidx.core", "core-ktx", "androidx.core", listOf(), "1.12.0"),
            LibraryEntry("AndroidX AppCompat", "androidx.appcompat", "appcompat", "androidx.appcompat", listOf(), "1.6.1"),
            LibraryEntry("Material Components", "com.google.android.material", "material", "com.google.android.material", listOf(), "1.11.0"),
            LibraryEntry("Retrofit", "com.squareup.retrofit2", "retrofit", "retrofit2", listOf(), "2.9.0"),
            LibraryEntry("OkHttp", "com.squareup.okhttp3", "okhttp", "okhttp3", listOf(), "4.12.0"),
            LibraryEntry("Gson", "com.google.code.gson", "gson", "com.google.gson", listOf(), "2.10.1"),
            LibraryEntry("Glide", "com.github.bumptech.glide", "glide", "com.bumptech.glide", listOf(), "4.16.0"),
            LibraryEntry("RxJava", "io.reactivex.rxjava3", "rxjava", "io.reactivex.rxjava3", listOf(), "3.1.8"),
            LibraryEntry("Kotlin Coroutines", "org.jetbrains.kotlinx", "kotlinx-coroutines-core", "kotlinx.coroutines", listOf(), "1.7.3"),
            LibraryEntry("Dagger", "com.google.dagger", "dagger", "dagger", listOf(), "2.50"),
            LibraryEntry("Hilt", "com.google.dagger", "hilt-android", "dagger.hilt", listOf(), "2.50"),
            LibraryEntry("Firebase Core", "com.google.firebase", "firebase-common", "com.google.firebase", listOf(), "20.4.2"),
            LibraryEntry("Timber", "com.jakewharton.timber", "timber", "timber.log", listOf(), "5.0.1")
        )
    }
}

data class LibraryData(
    val libraries: List<LibraryEntry>
)

data class LibraryEntry(
    val name: String,
    val groupId: String,
    val artifactId: String,
    val packagePrefix: String,
    val classSignatures: List<String>,
    val latestVersion: String
)
