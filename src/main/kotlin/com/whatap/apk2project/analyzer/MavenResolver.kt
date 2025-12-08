package com.whatap.apk2project.analyzer

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.whatap.apk2project.models.Dependency
import com.whatap.apk2project.models.DetectionSource
import com.whatap.apk2project.models.MavenArtifact
import com.whatap.apk2project.utils.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class MavenResolver {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private val cache = ConcurrentHashMap<String, MavenArtifact?>()
    private var lastRequestTime = 0L
    private val minRequestInterval = 100L // ms between requests (rate limiting)

    // Blacklist of group IDs that are not compatible with Android
    private val groupIdBlacklist = setOf(
        // Eclipse platform
        "org.eclipse", "org.eclipse.core", "org.eclipse.jface", "org.eclipse.ui",
        "org.eclipse.ecf", "org.eclipse.swt", "org.eclipse.equinox",
        // Java EE / Jakarta
        "javax.servlet", "javax.ejb", "javax.jms", "javax.mail",
        "jakarta.servlet", "jakarta.ejb", "jakarta.jms",
        // Desktop GUI
        "org.openjfx", "javafx",
        // Server frameworks
        "org.springframework", "org.apache.tomcat", "org.apache.catalina",
        "org.jboss", "org.wildfly", "io.undertow",
        // Scala (not commonly used in Android)
        "org.scala-lang", "org.openmole", "com.typesafe.akka",
        // Non-Android specific
        "org.xbib", "net.nemerosa", "com.gitee", "org.qsardb",
        "org.apache.servicemix", "com.github.nbbrd.sdmx-dl",
        "com.github.catdou",
        // Desktop Excel/Document processing (usually not for Android)
        "org.apache.poi", "net.sf.jxls", "org.jxls"
    )

    // Whitelist of Android-compatible group ID prefixes
    private val androidCompatiblePrefixes = listOf(
        "androidx.", "com.google.android", "com.google.firebase",
        "com.squareup", "io.reactivex", "org.jetbrains.kotlin",
        "com.jakewharton", "com.github.bumptech", "com.airbnb",
        "io.coil", "com.facebook", "com.google.code.gson",
        "com.google.dagger", "org.apache.commons", "com.google.zxing",
        "io.ktor", "org.conscrypt", "com.auth0"
    )

    private fun isAndroidCompatible(groupId: String): Boolean {
        // Check blacklist
        if (groupIdBlacklist.any { groupId.startsWith(it) }) {
            return false
        }

        // Prefer whitelisted groups
        return androidCompatiblePrefixes.any { groupId.startsWith(it) } ||
            // Allow other groups but they'll be filtered by confidence later
            !groupId.contains("eclipse") &&
            !groupId.contains("swing") &&
            !groupId.contains("awt") &&
            !groupId.contains("servlet") &&
            !groupId.contains("javaee") &&
            !groupId.contains("javafx")
    }

    suspend fun searchByClassName(className: String): MavenArtifact? {
        cache[className]?.let { return it }

        return withContext(Dispatchers.IO) {
            try {
                // Rate limiting
                val now = System.currentTimeMillis()
                val elapsed = now - lastRequestTime
                if (elapsed < minRequestInterval) {
                    delay(minRequestInterval - elapsed)
                }
                lastRequestTime = System.currentTimeMillis()

                val query = "fc:\"$className\""
                val url = "https://search.maven.org/solrsearch/select?q=$query&rows=5&wt=json"

                Logger.debug("Querying Maven Central: $className")

                val request = Request.Builder()
                    .url(url)
                    .header("Accept", "application/json")
                    .build()

                val response = httpClient.newCall(request).execute()

                if (!response.isSuccessful) {
                    Logger.debug("Maven Central query failed: ${response.code}")
                    return@withContext null
                }

                val body = response.body?.string() ?: return@withContext null
                val result = gson.fromJson(body, MavenSearchResponse::class.java)

                // Filter for Android-compatible artifacts
                val artifact = result.response.docs
                    .filter { isAndroidCompatible(it.g) }
                    .firstOrNull()?.let { doc ->
                        MavenArtifact(
                            groupId = doc.g,
                            artifactId = doc.a,
                            latestVersion = doc.latestVersion ?: doc.v
                        )
                    }

                cache[className] = artifact
                artifact
            } catch (e: Exception) {
                Logger.debug("Maven search error: ${e.message}")
                null
            }
        }
    }

    suspend fun searchByGroupAndArtifact(groupId: String, artifactId: String): MavenArtifact? {
        val key = "$groupId:$artifactId"
        cache[key]?.let { return it }

        return withContext(Dispatchers.IO) {
            try {
                val now = System.currentTimeMillis()
                val elapsed = now - lastRequestTime
                if (elapsed < minRequestInterval) {
                    delay(minRequestInterval - elapsed)
                }
                lastRequestTime = System.currentTimeMillis()

                val query = "g:\"$groupId\" AND a:\"$artifactId\""
                val url = "https://search.maven.org/solrsearch/select?q=$query&rows=1&wt=json"

                val request = Request.Builder()
                    .url(url)
                    .header("Accept", "application/json")
                    .build()

                val response = httpClient.newCall(request).execute()

                if (!response.isSuccessful) {
                    return@withContext null
                }

                val body = response.body?.string() ?: return@withContext null
                val result = gson.fromJson(body, MavenSearchResponse::class.java)

                val artifact = result.response.docs.firstOrNull()?.let { doc ->
                    MavenArtifact(
                        groupId = doc.g,
                        artifactId = doc.a,
                        latestVersion = doc.latestVersion ?: doc.v
                    )
                }

                cache[key] = artifact
                artifact
            } catch (e: Exception) {
                Logger.debug("Maven search error: ${e.message}")
                null
            }
        }
    }

    suspend fun getLatestVersion(groupId: String, artifactId: String): String? {
        val artifact = searchByGroupAndArtifact(groupId, artifactId)
        return artifact?.latestVersion
    }

    fun getCacheStats(): String {
        return "Maven cache: ${cache.size} entries"
    }
}

data class MavenSearchResponse(
    val response: MavenResponse
)

data class MavenResponse(
    val numFound: Int,
    val docs: List<MavenDoc>
)

data class MavenDoc(
    val g: String,           // groupId
    val a: String,           // artifactId
    val v: String,           // version
    val latestVersion: String? = null,
    @SerializedName("p")
    val packaging: String? = null,
    val timestamp: Long? = null
)
