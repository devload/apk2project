package com.whatap.apk2project.generator

import com.whatap.apk2project.analyzer.DependencyAnalyzer
import com.whatap.apk2project.decompiler.DexExtractor
import com.whatap.apk2project.decompiler.JadxDecompiler
import com.whatap.apk2project.decompiler.ManifestParser
import com.whatap.apk2project.decompiler.ResourceExtractor
import com.whatap.apk2project.fixer.CodeFixer
import com.whatap.apk2project.fixer.GracefulDegradationFixer
import com.whatap.apk2project.models.*
import com.whatap.apk2project.utils.FileUtils
import com.whatap.apk2project.utils.Logger
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import kotlin.system.measureTimeMillis

class ProjectGenerator(
    private val jadxDecompiler: JadxDecompiler = JadxDecompiler(),
    private val dexExtractor: DexExtractor = DexExtractor(),
    private val resourceExtractor: ResourceExtractor = ResourceExtractor(),
    private val manifestParser: ManifestParser = ManifestParser(),
    private val dependencyAnalyzer: DependencyAnalyzer = DependencyAnalyzer(),
    private val templateEngine: TemplateEngine = TemplateEngine(),
    private val sourceOrganizer: SourceOrganizer = SourceOrganizer(),
    private val resourceOrganizer: ResourceOrganizer = ResourceOrganizer()
) {

    suspend fun generate(
        apkFile: File,
        outputDir: File,
        options: GenerateOptions = GenerateOptions()
    ): GenerateResult {
        Logger.header("APK to Project Converter")
        Logger.info("Input: ${apkFile.name}")
        Logger.info("Output: ${outputDir.absolutePath}")

        val workDir = FileUtils.createTempDirectory("apk2project")
        var totalDuration: Long = 0

        // ProgressMonitor for PARSE 0 (sourceDir will be set after decompilation)
        val monitor = com.whatap.apk2project.deobfuscator.monitor.ProgressMonitor(
            File(outputDir, ".apk2project")
        )
        monitor.apkFilePath = apkFile.absolutePath
        monitor.outputProjectPath = outputDir.absolutePath
        monitor.start()

        try {
            totalDuration = measureTimeMillis {
                // Step 1: Parse AndroidManifest
                Logger.header("Step 1: Analyzing APK")
                monitor.currentPhase = com.whatap.apk2project.deobfuscator.monitor.PipelinePhase.PARSE0_PARSING_MANIFEST
                monitor.parse0Step = 1
                monitor.parse0Progress = 0.0
                monitor.phase = "PARSE 0: Parsing Manifest"
                monitor.status = "Analyzing APK structure..."
                monitor.forceUpdate()  // 즉시 반영

                val apkInfo = manifestParser.parseFromApk(apkFile, workDir)
                    ?: return GenerateResult.Failure("Failed to parse AndroidManifest.xml")

                printApkInfo(apkInfo)
                monitor.parse0Progress = 20.0
                monitor.forceUpdate()  // 즉시 반영

                // Step 2: Decompile
                Logger.header("Step 2: Decompiling")
                monitor.currentPhase = com.whatap.apk2project.deobfuscator.monitor.PipelinePhase.PARSE0_DECOMPILING
                monitor.parse0Step = 2
                monitor.phase = "PARSE 0: Decompiling"
                monitor.status = "Decompiling DEX to Java..."
                monitor.forceUpdate()  // 즉시 반영

                val decompileResult = jadxDecompiler.decompile(apkFile, workDir)

                val (sourceDir, resourceDir, decompileStats) = when (decompileResult) {
                    is DecompileResult.Success -> Triple(
                        decompileResult.sourceDir,
                        decompileResult.resourceDir,
                        decompileResult.stats
                    )
                    is DecompileResult.PartialSuccess -> {
                        Logger.warn("Decompilation completed with ${decompileResult.errors.size} errors")
                        Triple(
                            decompileResult.sourceDir,
                            decompileResult.resourceDir,
                            decompileResult.stats
                        )
                    }
                    is DecompileResult.Failure -> {
                        monitor.currentPhase = com.whatap.apk2project.deobfuscator.monitor.PipelinePhase.FAILED
                        monitor.status = "Decompilation failed: ${decompileResult.error}"
                        monitor.forceUpdate()  // 즉시 반영
                        return GenerateResult.Failure(decompileResult.error, decompileResult.cause)
                    }
                }

                monitor.decompileSuccessRate = decompileStats.successRate.toDouble() * 100.0
                Logger.success("Decompiled ${decompileStats.successfulClasses} classes (${(decompileStats.successRate * 100).toInt()}% success)")
                monitor.parse0Progress = 40.0
                monitor.forceUpdate()  // 즉시 반영

                // Step 3: Extract resources (if JADX didn't do it properly)
                Logger.header("Step 3: Extracting Resources")
                monitor.currentPhase = com.whatap.apk2project.deobfuscator.monitor.PipelinePhase.PARSE0_EXTRACTING_RESOURCES
                monitor.parse0Step = 3
                monitor.phase = "PARSE 0: Extracting Resources"
                monitor.status = "Extracting resources..."
                monitor.forceUpdate()  // 즉시 반영

                val resResult = resourceExtractor.extract(apkFile, workDir)
                monitor.totalResourcesExtracted = resResult.resources.size
                Logger.success("Extracted ${resResult.resources.size} resource files")
                monitor.parse0Progress = 60.0
                monitor.forceUpdate()  // 즉시 반영

                // Step 4: Analyze dependencies
                Logger.header("Step 4: Analyzing Dependencies")
                monitor.currentPhase = com.whatap.apk2project.deobfuscator.monitor.PipelinePhase.PARSE0_ANALYZING_DEPENDENCIES
                monitor.parse0Step = 4
                monitor.phase = "PARSE 0: Analyzing Dependencies"
                monitor.status = "Detecting libraries and dependencies..."
                monitor.forceUpdate()  // 즉시 반영

                val analysisResult = dependencyAnalyzer.analyze(sourceDir)

                val dependencies = when (analysisResult) {
                    is AnalysisResult.Success -> {
                        Logger.success("Detected ${analysisResult.dependencies.size} dependencies")
                        monitor.dependenciesDetected = analysisResult.dependencies.size
                        analysisResult.dependencies
                    }
                    is AnalysisResult.Failure -> {
                        Logger.warn("Dependency analysis failed: ${analysisResult.error}")
                        emptyList()
                    }
                }
                monitor.parse0Progress = 80.0
                monitor.forceUpdate()  // 즉시 반영

                // Step 5: Generate project structure
                Logger.header("Step 5: Generating Project")
                monitor.currentPhase = com.whatap.apk2project.deobfuscator.monitor.PipelinePhase.PARSE0_GENERATING_PROJECT
                monitor.parse0Step = 5
                monitor.phase = "PARSE 0: Generating Project"
                monitor.status = "Creating Gradle project structure..."
                monitor.forceUpdate()  // 즉시 반영

                val projectDir = generateProjectStructure(
                    outputDir = outputDir,
                    apkInfo = apkInfo,
                    sourceDir = sourceDir,
                    resourceDir = if (resResult.resourceDir.exists()) resResult.resourceDir else resourceDir,
                    assetsDir = resResult.assetsDir,
                    nativeLibs = extractNativeLibraries(apkFile, workDir),
                    dependencies = dependencies,
                    decompileStats = decompileStats,
                    options = options
                )

                Logger.success("Project generated at: ${projectDir.absolutePath}")
                monitor.parse0Progress = 100.0
                monitor.forceUpdate()  // 즉시 반영

                // Step 6: AI Deobfuscation (optional)
                if (options.enableAi) {
                    Logger.header("Step 6: AI Deobfuscation")
                    runAiDeobfuscation(sourceDir, outputDir, options, monitor)
                }
            }

            // Cleanup
            if (!options.keepTempFiles) {
                FileUtils.deleteDirectory(workDir)
            }

            // Mark PARSE 0 as complete (unless AI deobfuscation is running)
            if (!options.enableAi) {
                monitor.currentPhase = com.whatap.apk2project.deobfuscator.monitor.PipelinePhase.COMPLETE
                monitor.phase = "Complete"
                monitor.status = "Project generation completed successfully"
                monitor.isRunning = false
            }

            Logger.header("Complete!")
            Logger.info("Total time: ${totalDuration / 1000}s")
            Logger.info("Next steps:")
            Logger.step("cd ${outputDir.absolutePath}")
            Logger.step("Open in Android Studio")
            Logger.lastStep("./gradlew assembleDebug")

            return GenerateResult.Success(
                project = AndroidProject(
                    projectDir = outputDir,
                    packageName = "",
                    applicationId = "",
                    versionName = "",
                    versionCode = 0,
                    minSdk = 21,
                    targetSdk = 34,
                    compileSdk = 34,
                    hasKotlinCode = false,
                    hasJavaCode = true,
                    hasDataBinding = false,
                    hasViewBinding = false,
                    hasCompose = false,
                    isMinified = false,
                    dependencies = emptyList(),
                    sourceFiles = emptyList(),
                    resourceFiles = emptyList(),
                    assetFiles = emptyList(),
                    nativeLibraries = emptyMap(),
                    manifestInfo = ApkInfo(
                        file = apkFile,
                        packageName = "",
                        versionName = "",
                        versionCode = 0,
                        minSdk = 21,
                        targetSdk = 34,
                        compileSdk = 34,
                        applicationName = null,
                        permissions = emptyList(),
                        activities = emptyList(),
                        services = emptyList(),
                        receivers = emptyList(),
                        providers = emptyList(),
                        usesFeatures = emptyList(),
                        nativeLibraries = emptyList(),
                        dexCount = 1,
                        isMultiDex = false,
                        fileSizeMb = 0.0
                    )
                ),
                stats = GenerationStats(
                    totalClasses = 0,
                    decompiledClasses = 0,
                    failedClasses = 0,
                    detectedDependencies = 0,
                    resourceFiles = 0,
                    durationMs = totalDuration
                )
            )

        } catch (e: Exception) {
            Logger.error("Generation failed: ${e.message}")
            e.printStackTrace()
            return GenerateResult.Failure(e.message ?: "Unknown error", e)
        }
    }

    private fun printApkInfo(info: ApkInfo) {
        Logger.step("Package: ${info.packageName}")
        Logger.step("Version: ${info.versionName} (${info.versionCode})")
        Logger.step("Min SDK: ${info.minSdk}")
        Logger.step("Target SDK: ${info.targetSdk}")
        Logger.step("APK Size: ${"%.1f".format(info.fileSizeMb)} MB")
        Logger.lastStep("DEX files: ${info.dexCount}")
    }

    private suspend fun generateProjectStructure(
        outputDir: File,
        apkInfo: ApkInfo,
        sourceDir: File,
        resourceDir: File,
        assetsDir: File?,
        nativeLibs: Map<String, List<File>>,
        dependencies: List<Dependency>,
        decompileStats: DecompileStats,
        options: GenerateOptions
    ): File {
        // Create directory structure
        val appDir = File(outputDir, "app")
        val srcMainDir = File(appDir, "src/main")
        val javaDir = File(srcMainDir, "java")
        val kotlinDir = if (hasKotlinFiles(sourceDir)) File(srcMainDir, "kotlin") else null
        val resDir = File(srcMainDir, "res")
        val assetsTargetDir = File(srcMainDir, "assets")
        val jniLibsDir = File(srcMainDir, "jniLibs")

        // Create directories
        listOfNotNull(javaDir, kotlinDir, resDir, assetsTargetDir).forEach { it.mkdirs() }

        // Organize sources (exclude library packages, we'll generate stubs for them)
        Logger.step("Organizing source files...")
        val sourceResult = sourceOrganizer.organize(sourceDir, javaDir, kotlinDir, excludeLibraries = true)
        sourceOrganizer.cleanupSource(javaDir)
        kotlinDir?.let { sourceOrganizer.cleanupSource(it) }

        // Generate SDK stubs for filtered library packages
        Logger.step("Generating SDK stubs...")
        val sdkStubGenerator = SdkStubGenerator()
        val stubResult = sdkStubGenerator.generateStubs(javaDir, javaDir)
        if (stubResult.packagesGenerated > 0) {
            Logger.success("Generated ${stubResult.packagesGenerated} SDK stub packages")
        }

        // Fix common decompilation errors
        Logger.step("Fixing decompilation errors...")
        val codeFixer = CodeFixer()
        val fixResult = codeFixer.fixSourceDirectory(javaDir)
        kotlinDir?.let { codeFixer.fixSourceDirectory(it) }
        Logger.success("Fixed ${fixResult.totalFixes} compilation issues")

        // Apply graceful degradation for heavily optimized APKs
        Logger.step("Applying graceful degradation for R8-optimized code...")
        val gracefulFixer = GracefulDegradationFixer()

        // First, delete files with catastrophic decompilation errors (unfixable)
        val deletedFiles = gracefulFixer.deleteProblematicFiles(javaDir, errorThreshold = 50)
        kotlinDir?.let { gracefulFixer.deleteProblematicFiles(it, errorThreshold = 50) }

        // Then apply method-level fixes
        val gracefulResult = gracefulFixer.fixSourceDirectory(javaDir)
        kotlinDir?.let { gracefulFixer.fixSourceDirectory(it) }

        if (deletedFiles > 0 || gracefulResult.methodsFixed > 0) {
            Logger.warn("Graceful degradation: deleted $deletedFiles unfixable files, stubbed ${gracefulResult.methodsFixed} methods")
        }

        // Organize resources
        Logger.step("Organizing resources...")
        val resourceResult = resourceOrganizer.organize(resourceDir, assetsDir, resDir, assetsTargetDir)
        resourceOrganizer.cleanupResources(resDir)

        // Organize native libraries
        if (nativeLibs.isNotEmpty()) {
            Logger.step("Copying native libraries...")
            resourceOrganizer.organizeNativeLibraries(nativeLibs, jniLibsDir)
        }

        // Detect features
        val hasKotlin = sourceResult.kotlinCount > 0
        val hasCompose = dependencies.any { it.groupId.contains("compose") }
        val hasHilt = dependencies.any { it.artifactId.contains("hilt") }
        val hasFirebase = dependencies.any { it.groupId.contains("firebase") }
        val hasCrashlytics = dependencies.any { it.artifactId.contains("crashlytics") }
        val hasViewBinding = detectViewBinding(sourceDir)
        val hasDataBinding = detectDataBinding(sourceDir)

        // Generate build.gradle
        Logger.step("Generating build.gradle...")
        val buildGradleData = BuildGradleData(
            hasKotlin = hasKotlin,
            hasHilt = hasHilt,
            hasFirebase = hasFirebase,
            hasCrashlytics = hasCrashlytics,
            namespace = apkInfo.packageName,
            applicationId = apkInfo.packageName,
            compileSdk = apkInfo.targetSdk.coerceAtLeast(34),
            minSdk = apkInfo.minSdk.coerceAtLeast(21),
            targetSdk = apkInfo.targetSdk.coerceAtLeast(34),
            versionCode = apkInfo.versionCode,
            versionName = apkInfo.versionName,
            javaVersion = if (apkInfo.targetSdk >= 34) 17 else 11,
            multiDexEnabled = apkInfo.isMultiDex,
            hasCompose = hasCompose,
            buildFeatures = buildMap {
                if (hasViewBinding) put("viewBinding", true)
                if (hasDataBinding) put("dataBinding", true)
                if (hasCompose) put("compose", true)
            },
            coreDependencies = getCoreDependencies(hasKotlin),
            detectedDependencies = dependencies.filter { it.confidence >= 0.85f }
        )
        File(appDir, "build.gradle").writeText(templateEngine.renderBuildGradle(buildGradleData))

        // Generate settings.gradle
        Logger.step("Generating settings.gradle...")
        val projectName = apkInfo.packageName.substringAfterLast(".")
        File(outputDir, "settings.gradle").writeText(templateEngine.renderSettingsGradle(projectName))

        // Generate gradle.properties
        Logger.step("Generating gradle.properties...")
        File(outputDir, "gradle.properties").writeText(templateEngine.renderGradleProperties())

        // Generate proguard-rules.pro
        Logger.step("Generating proguard-rules.pro...")
        val proguardData = ProguardRulesData(
            applicationClass = apkInfo.applicationName ?: "${apkInfo.packageName}.Application",
            hasRetrofit = dependencies.any { it.artifactId == "retrofit" },
            hasGson = dependencies.any { it.artifactId == "gson" },
            hasOkHttp = dependencies.any { it.artifactId == "okhttp" },
            hasGlide = dependencies.any { it.artifactId == "glide" }
        )
        File(appDir, "proguard-rules.pro").writeText(templateEngine.renderProguardRules(proguardData))

        // Generate google-services.json placeholder if Firebase is used
        if (hasFirebase) {
            Logger.step("Generating google-services.json placeholder...")
            generateGoogleServicesPlaceholder(appDir, apkInfo.packageName)
        }

        // Ensure essential resources exist
        Logger.step("Ensuring essential resources...")
        ensureEssentialResources(resDir, apkInfo)

        // Copy AndroidManifest.xml
        Logger.step("Generating AndroidManifest.xml...")
        generateManifest(apkInfo, srcMainDir)

        // Generate README.md
        Logger.step("Generating README.md...")
        val readmeData = ReadmeData(
            projectName = projectName,
            packageName = apkInfo.packageName,
            versionName = apkInfo.versionName,
            versionCode = apkInfo.versionCode,
            minSdk = apkInfo.minSdk,
            targetSdk = apkInfo.targetSdk,
            generatedDate = SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(Date()),
            sourceApkName = apkInfo.file.name,
            apkSizeMb = "%.1f".format(apkInfo.fileSizeMb),
            totalClasses = decompileStats.totalClasses,
            decompileSuccessRate = (decompileStats.successRate * 100).toInt(),
            dependencyCount = dependencies.size,
            coreDependencies = getCoreDependencies(hasKotlin),
            detectedDependencies = dependencies,
            obfuscated = decompileStats.obfuscatedClasses > decompileStats.totalClasses * 0.1,
            hasNativeLibraries = nativeLibs.isNotEmpty(),
            nativeLibraries = nativeLibs.mapValues { it.value.map { f -> f.name } }
        )
        File(outputDir, "README.md").writeText(templateEngine.renderReadme(readmeData))

        // Setup Gradle wrapper
        Logger.step("Setting up Gradle wrapper...")
        setupGradleWrapper(outputDir)

        return outputDir
    }

    private fun getCoreDependencies(hasKotlin: Boolean): List<String> {
        val deps = mutableListOf(
            "androidx.appcompat:appcompat:1.6.1",
            "com.google.android.material:material:1.11.0",
            "androidx.constraintlayout:constraintlayout:2.1.4"
        )

        if (hasKotlin) {
            deps.add(0, "androidx.core:core-ktx:1.12.0")
        } else {
            deps.add(0, "androidx.core:core:1.12.0")
        }

        return deps
    }

    private fun extractNativeLibraries(apkFile: File, workDir: File): Map<String, List<File>> {
        val extractResult = dexExtractor.extract(apkFile, workDir)
        return extractResult.nativeLibraries
    }

    private fun hasKotlinFiles(sourceDir: File): Boolean {
        return sourceDir.walkTopDown().any { it.extension == "kt" }
    }

    private fun detectViewBinding(sourceDir: File): Boolean {
        return sourceDir.walkTopDown()
            .filter { it.extension == "java" || it.extension == "kt" }
            .any { file ->
                file.readText().contains("Binding.inflate") ||
                file.readText().contains("databinding.")
            }
    }

    private fun detectDataBinding(sourceDir: File): Boolean {
        return sourceDir.walkTopDown()
            .filter { it.extension == "java" || it.extension == "kt" }
            .any { file ->
                file.readText().contains("DataBindingUtil") ||
                file.readText().contains("@BindingAdapter")
            }
    }

    private fun ensureEssentialResources(resDir: File, apkInfo: ApkInfo) {
        // Ensure values/strings.xml exists with app_name
        val valuesDir = File(resDir, "values")
        valuesDir.mkdirs()

        val stringsFile = File(valuesDir, "strings.xml")
        if (!stringsFile.exists()) {
            val appName = apkInfo.packageName.substringAfterLast(".")
                .replaceFirstChar { it.uppercase() }
            stringsFile.writeText("""
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">$appName</string>
</resources>
            """.trimIndent())
            Logger.debug("Created strings.xml with app_name")
        } else {
            // Ensure app_name exists in strings.xml
            var content = stringsFile.readText()
            if (!content.contains("\"app_name\"")) {
                val appName = apkInfo.packageName.substringAfterLast(".")
                    .replaceFirstChar { it.uppercase() }
                content = content.replace(
                    "</resources>",
                    "    <string name=\"app_name\">$appName</string>\n</resources>"
                )
                stringsFile.writeText(content)
                Logger.debug("Added app_name to strings.xml")
            }
        }

        // Ensure mipmap ic_launcher and ic_launcher_round exist
        // Look for existing mipmap directories (including versioned ones like mipmap-hdpi-v4)
        val allMipmapDirs = resDir.listFiles()?.filter { it.isDirectory && it.name.startsWith("mipmap") } ?: emptyList()

        // Check if any ic_launcher exists
        val hasLauncher = allMipmapDirs.any { dir ->
            dir.listFiles()?.any { it.nameWithoutExtension == "ic_launcher" } == true
        }

        // Check if any ic_launcher_round exists
        val hasRoundLauncher = allMipmapDirs.any { dir ->
            dir.listFiles()?.any { it.nameWithoutExtension == "ic_launcher_round" } == true
        }

        if (hasLauncher && !hasRoundLauncher) {
            // Copy ic_launcher to ic_launcher_round in each mipmap directory
            for (mipmapDir in allMipmapDirs) {
                val launcherFile = mipmapDir.listFiles()?.firstOrNull {
                    it.nameWithoutExtension == "ic_launcher"
                }
                if (launcherFile != null) {
                    val roundFile = File(mipmapDir, "ic_launcher_round.${launcherFile.extension}")
                    if (!roundFile.exists()) {
                        launcherFile.copyTo(roundFile)
                    }
                }
            }
            Logger.debug("Created ic_launcher_round from ic_launcher")
        } else if (!hasLauncher) {
            // No launcher icons at all, create minimal ones
            val defaultMipmap = File(resDir, "mipmap-hdpi")
            defaultMipmap.mkdirs()
            // Create a simple placeholder icon XML (adaptive icon)
            File(defaultMipmap, "ic_launcher.xml").writeText("""
<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/ic_launcher_background"/>
    <foreground android:drawable="@color/ic_launcher_foreground"/>
</adaptive-icon>
            """.trimIndent())
            File(defaultMipmap, "ic_launcher_round.xml").writeText("""
<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/ic_launcher_background"/>
    <foreground android:drawable="@color/ic_launcher_foreground"/>
</adaptive-icon>
            """.trimIndent())

            // Create colors for icon
            val colorsFile = File(valuesDir, "colors.xml")
            if (!colorsFile.exists()) {
                colorsFile.writeText("""
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <color name="ic_launcher_background">#FFFFFF</color>
    <color name="ic_launcher_foreground">#000000</color>
</resources>
                """.trimIndent())
            }
            Logger.debug("Created placeholder launcher icons")
        }
    }

    private fun generateGoogleServicesPlaceholder(appDir: File, packageName: String) {
        // Generate a placeholder google-services.json
        // This will need to be replaced with actual Firebase project values
        val placeholder = """
{
  "project_info": {
    "project_number": "000000000000",
    "project_id": "placeholder-project-id",
    "storage_bucket": "placeholder-project-id.appspot.com"
  },
  "client": [
    {
      "client_info": {
        "mobilesdk_app_id": "1:000000000000:android:0000000000000000",
        "android_client_info": {
          "package_name": "$packageName"
        }
      },
      "oauth_client": [],
      "api_key": [
        {
          "current_key": "PLACEHOLDER_API_KEY"
        }
      ],
      "services": {
        "appinvite_service": {
          "other_platform_oauth_client": []
        }
      }
    }
  ],
  "configuration_version": "1"
}
        """.trimIndent()

        File(appDir, "google-services.json").writeText(placeholder)
        Logger.warn("Generated placeholder google-services.json - replace with your Firebase config")
    }

    private fun generateManifest(apkInfo: ApkInfo, srcMainDir: File) {
        val manifestBuilder = StringBuilder()
        manifestBuilder.appendLine("""<?xml version="1.0" encoding="utf-8"?>""")
        manifestBuilder.appendLine("""<manifest xmlns:android="http://schemas.android.com/apk/res/android">""")
        manifestBuilder.appendLine()

        // Filter valid component names (must be a class name, not namespace URI or empty)
        fun isValidComponentName(name: String): Boolean {
            return name.isNotEmpty() &&
                !name.startsWith("http://") &&
                !name.startsWith("https://") &&
                (name.contains(".") || name.startsWith("."))
        }

        // Permissions
        for (permission in apkInfo.permissions.distinct()) {
            manifestBuilder.appendLine("""    <uses-permission android:name="$permission" />""")
        }

        if (apkInfo.permissions.isNotEmpty()) {
            manifestBuilder.appendLine()
        }

        // Application
        manifestBuilder.appendLine("""    <application""")
        manifestBuilder.appendLine("""        android:allowBackup="true"""")
        manifestBuilder.appendLine("""        android:icon="@mipmap/ic_launcher"""")
        manifestBuilder.appendLine("""        android:label="@string/app_name"""")
        manifestBuilder.appendLine("""        android:roundIcon="@mipmap/ic_launcher_round"""")
        manifestBuilder.appendLine("""        android:supportsRtl="true"""")
        manifestBuilder.appendLine("""        android:theme="@style/Theme.AppCompat.Light.DarkActionBar">""")

        // Activities - filter valid and unique names
        val validActivities = apkInfo.activities
            .filter { isValidComponentName(it.name) }
            .distinctBy { it.name }

        for (activity in validActivities) {
            manifestBuilder.appendLine()
            manifestBuilder.appendLine("""        <activity""")
            manifestBuilder.appendLine("""            android:name="${activity.name}"""")
            manifestBuilder.appendLine("""            android:exported="${activity.exported}">""")

            for (filter in activity.intentFilters) {
                manifestBuilder.appendLine("""            <intent-filter>""")
                for (action in filter.actions) {
                    manifestBuilder.appendLine("""                <action android:name="$action" />""")
                }
                for (category in filter.categories) {
                    manifestBuilder.appendLine("""                <category android:name="$category" />""")
                }
                manifestBuilder.appendLine("""            </intent-filter>""")
            }

            manifestBuilder.appendLine("""        </activity>""")
        }

        // Services - filter valid and unique names
        val validServices = apkInfo.services
            .filter { isValidComponentName(it.name) }
            .distinctBy { it.name }

        for (service in validServices) {
            manifestBuilder.appendLine()
            manifestBuilder.appendLine("""        <service""")
            manifestBuilder.appendLine("""            android:name="${service.name}"""")
            manifestBuilder.appendLine("""            android:exported="${service.exported}" />""")
        }

        // Receivers - filter valid and unique names
        val validReceivers = apkInfo.receivers
            .filter { isValidComponentName(it.name) }
            .distinctBy { it.name }

        for (receiver in validReceivers) {
            manifestBuilder.appendLine()
            manifestBuilder.appendLine("""        <receiver""")
            manifestBuilder.appendLine("""            android:name="${receiver.name}"""")
            manifestBuilder.appendLine("""            android:exported="${receiver.exported}" />""")
        }

        manifestBuilder.appendLine("""    </application>""")
        manifestBuilder.appendLine("""</manifest>""")

        File(srcMainDir, "AndroidManifest.xml").writeText(manifestBuilder.toString())
    }

    private fun setupGradleWrapper(projectDir: File) {
        val wrapperDir = File(projectDir, "gradle/wrapper")
        wrapperDir.mkdirs()

        // Create gradlew
        val gradlew = File(projectDir, "gradlew")
        gradlew.writeText(GRADLEW_SCRIPT)
        gradlew.setExecutable(true)

        // Create gradlew.bat
        File(projectDir, "gradlew.bat").writeText(GRADLEW_BAT_SCRIPT)

        // Create gradle-wrapper.properties
        File(wrapperDir, "gradle-wrapper.properties").writeText("""
distributionBase=GRADLE_USER_HOME
distributionPath=wrapper/dists
distributionUrl=https\://services.gradle.org/distributions/gradle-8.5-bin.zip
networkTimeout=10000
validateDistributionUrl=true
zipStoreBase=GRADLE_USER_HOME
zipStorePath=wrapper/dists
        """.trimIndent())

        // Copy gradle-wrapper.jar from resources or download
        val wrapperJar = File(wrapperDir, "gradle-wrapper.jar")
        val resourceJar = javaClass.getResourceAsStream("/gradle-wrapper.jar")
        if (resourceJar != null) {
            wrapperJar.outputStream().use { out ->
                resourceJar.copyTo(out)
            }
        } else {
            // Download gradle-wrapper.jar if not in resources
            try {
                val url = java.net.URL("https://github.com/gradle/gradle/raw/v8.5.0/gradle/wrapper/gradle-wrapper.jar")
                url.openStream().use { input ->
                    wrapperJar.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                Logger.debug("Downloaded gradle-wrapper.jar")
            } catch (e: Exception) {
                Logger.warn("Could not download gradle-wrapper.jar: ${e.message}")
                Logger.info("Please run 'gradle wrapper' in the generated project directory")
            }
        }
    }

    data class GenerateOptions(
        val keepTempFiles: Boolean = false,
        val skipVerification: Boolean = false,
        val verbose: Boolean = false,
        // AI Deobfuscation options
        val enableAi: Boolean = false,
        val aiClientType: com.whatap.apk2project.deobfuscator.client.AiClientType = com.whatap.apk2project.deobfuscator.client.AiClientType.OLLAMA,
        val modelName: String = "deepseek-coder:33b",
        val enableKorean: Boolean = false,
        val translationModelName: String = "qwen2.5:7b",
        val batchSize: Int = 10,
        val requestDelay: Long = 1000
    )

    companion object {
        private val GRADLEW_SCRIPT = """
#!/bin/sh

##############################################################################
# Gradle start up script for POSIX generated by Gradle.
##############################################################################

# Attempt to set APP_HOME
APP_HOME="${'$'}(cd "${'$'}(dirname "${'$'}0")" && pwd -P)" || exit

# Add default JVM options here.
DEFAULT_JVM_OPTS="-Xmx256m -Xms64m"

# Determine the Java command to use to start the JVM.
if [ -n "${'$'}JAVA_HOME" ] ; then
    JAVACMD="${'$'}JAVA_HOME/bin/java"
else
    JAVACMD="java"
fi

# Check that the wrapper jar exists
WRAPPER_JAR="${'$'}APP_HOME/gradle/wrapper/gradle-wrapper.jar"
if [ ! -f "${'$'}WRAPPER_JAR" ]; then
    echo "Error: Gradle wrapper JAR not found at ${'$'}WRAPPER_JAR"
    echo "Please run 'gradle wrapper' to generate it."
    exit 1
fi

exec "${'$'}JAVACMD" ${'$'}DEFAULT_JVM_OPTS ${'$'}GRADLE_OPTS -jar "${'$'}WRAPPER_JAR" "${'$'}@"
        """.trimIndent()

        private val GRADLEW_BAT_SCRIPT = """
@rem Gradle startup script for Windows
@if "%DEBUG%"=="" @echo off
setlocal

set DIRNAME=%~dp0
if "%DIRNAME%" == "" set DIRNAME=.
set APP_HOME=%DIRNAME%

set DEFAULT_JVM_OPTS=-Xmx256m -Xms64m

set WRAPPER_JAR=%APP_HOME%\gradle\wrapper\gradle-wrapper.jar

if exist "%JAVA_HOME%\bin\java.exe" (
    set JAVACMD=%JAVA_HOME%\bin\java.exe
) else (
    set JAVACMD=java.exe
)

"%JAVACMD%" %DEFAULT_JVM_OPTS% %GRADLE_OPTS% -jar "%WRAPPER_JAR%" %*

:end
endlocal
        """.trimIndent()
    }

    /**
     * Run AI deobfuscation pipeline
     */
    private suspend fun runAiDeobfuscation(
        sourceDir: File,
        projectOutputDir: File,
        options: GenerateOptions,
        monitor: com.whatap.apk2project.deobfuscator.monitor.ProgressMonitor
    ) {
        // Use same monitor as PARSE 0 (share ProgressMonitor)
        val config = com.whatap.apk2project.deobfuscator.pipeline.PipelineConfig(
            aiClientType = options.aiClientType,
            modelName = options.modelName,
            batchSize = options.batchSize,
            requestDelay = options.requestDelay,
            enableKorean = options.enableKorean,
            translationModelName = options.translationModelName,
            useCache = true
        )

        val pipeline = com.whatap.apk2project.deobfuscator.pipeline.DeobfuscationPipeline(
            sourceDir = sourceDir,
            outputDir = File(projectOutputDir, ".apk2project"),  // Use same outputDir as PARSE 0
            config = config,
            externalMonitor = monitor  // Share ProgressMonitor instance
        )

        // Run pipeline
        val result = pipeline.run()

        Logger.info("────────────────────────────────────────────────")
        if (result.success) {
            Logger.success("AI Deobfuscation complete!")
            Logger.info("Total Methods: ${result.stats.totalMethods}")
            Logger.info("Processed Methods: ${result.stats.processedMethods}")
            Logger.info("Renamed Methods: ${result.stats.renamedMethods}")
            Logger.info("Renamed Classes: ${result.stats.renamedClasses}")
            Logger.info("Duration: ${result.stats.durationMs / 1000}s")
            Logger.info("Results saved to: ${projectOutputDir.absolutePath}")
        } else {
            Logger.error("AI Deobfuscation failed: ${result.error}")
        }
    }
}
