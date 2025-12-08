# APK2Project - Claude Development Guide

## Project Overview
**APK to Gradle Project Converter** - Android APK 파일을 분석하여 빌드 가능한 Java/Kotlin Gradle 프로젝트로 복원하는 CLI 도구

## Development Environment

### Language & Framework
- **Primary**: Kotlin (JVM)
- **Build Tool**: Gradle (Kotlin DSL)
- **CLI Framework**: Clikt (https://ajalt.github.io/clikt/)
- **Template Engine**: FreeMarker (build.gradle 생성)

### Key Dependencies
```kotlin
// build.gradle.kts
dependencies {
    implementation("com.github.ajalt.clikt:clikt:4.2.1")
    implementation("org.freemarker:freemarker:2.3.32")
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("org.ow2.asm:asm:9.6")                   // Bytecode analysis
    implementation("org.ow2.asm:asm-commons:9.6")
    implementation("org.ow2.asm:asm-util:9.6")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")   // Maven Central API

    // Testing
    testImplementation("io.kotest:kotest-runner-junit5:5.8.0")
    testImplementation("io.mockk:mockk:1.13.8")
}
```

### External Tools Integration
- **JADX**: DEX → Java decompiler
- **APKTool**: APK resource extraction
- **Maven Central API**: Dependency resolution
- **ASM**: Bytecode analysis for library detection

## Project Structure

```
apk2project/
├── CLAUDE.md                      # This file
├── mission.md                     # Project goals and implementation plan
├── README.md                      # User documentation
├── build.gradle.kts               # Gradle build configuration
├── settings.gradle.kts
└── src/
    ├── main/
    │   ├── kotlin/
    │   │   └── com/whatap/apk2project/
    │   │       ├── Main.kt                        # Entry point
    │   │       ├── commands/
    │   │       │   ├── DecompileCommand.kt        # APK → Java source
    │   │       │   ├── AnalyzeCommand.kt          # Dependency analysis
    │   │       │   ├── GenerateCommand.kt         # Generate Gradle project
    │   │       │   └── VerifyCommand.kt           # Verify buildability
    │   │       ├── decompiler/
    │   │       │   ├── JadxDecompiler.kt          # JADX wrapper
    │   │       │   ├── DexExtractor.kt            # Extract DEX from APK
    │   │       │   ├── ResourceExtractor.kt       # Extract resources
    │   │       │   └── ManifestParser.kt          # Parse AndroidManifest
    │   │       ├── analyzer/
    │   │       │   ├── DependencyAnalyzer.kt      # Detect dependencies
    │   │       │   ├── LibraryDetector.kt         # Match libraries
    │   │       │   ├── BytecodeAnalyzer.kt        # ASM-based analysis
    │   │       │   ├── MavenResolver.kt           # Maven Central API
    │   │       │   └── ObfuscationDetector.kt     # Detect ProGuard/R8
    │   │       ├── generator/
    │   │       │   ├── ProjectGenerator.kt        # Orchestrator
    │   │       │   ├── BuildGradleGenerator.kt    # build.gradle gen
    │   │       │   ├── SettingsGradleGen.kt       # settings.gradle gen
    │   │       │   ├── SourceOrganizer.kt         # Organize Java files
    │   │       │   ├── ResourceOrganizer.kt       # Organize resources
    │   │       │   └── ManifestGenerator.kt       # Generate manifest
    │   │       ├── verifier/
    │   │       │   ├── ProjectVerifier.kt         # Test build
    │   │       │   ├── DependencyResolver.kt      # Resolve missing deps
    │   │       │   └── CompileChecker.kt          # Check compilability
    │   │       ├── models/
    │   │       │   ├── ApkInfo.kt                 # APK metadata
    │   │       │   ├── Dependency.kt              # Dependency model
    │   │       │   ├── AndroidProject.kt          # Project structure
    │   │       │   └── BuildConfig.kt             # Build configuration
    │   │       └── utils/
    │   │           ├── ProgressBar.kt             # Progress display
    │   │           ├── Logger.kt                  # Logging
    │   │           └── FileUtils.kt               # File operations
    │   └── resources/
    │       ├── templates/
    │       │   ├── build.gradle.ftl               # FreeMarker template
    │       │   ├── settings.gradle.ftl
    │       │   ├── gradle.properties.ftl
    │       │   ├── AndroidManifest.xml.ftl
    │       │   └── README.md.ftl                  # Generated project README
    │       ├── library-signatures.json            # Known library patterns
    │       └── tools/
    │           ├── jadx/                          # JADX binaries
    │           └── apktool/                       # APKTool JAR
    └── test/
        └── kotlin/
            └── com/whatap/apk2project/
                ├── decompiler/
                │   └── JadxDecompilerTest.kt
                ├── analyzer/
                │   ├── DependencyAnalyzerTest.kt
                │   └── LibraryDetectorTest.kt
                └── generator/
                    └── BuildGradleGeneratorTest.kt
```

## Development Guidelines

### 1. Code Style
- **Kotlin Coding Conventions**: Follow official style guide
- **Immutability**: Prefer `val` and immutable data structures
- **Null safety**: Use nullable types explicitly, avoid `!!`
- **Sealed classes**: For result types and state machines

### 2. Error Handling Strategy
```kotlin
// Use sealed class for complex results
sealed class DecompileResult {
    data class Success(val sourceDir: File, val stats: DecompileStats) : DecompileResult()
    data class PartialSuccess(val sourceDir: File, val errors: List<String>) : DecompileResult()
    data class Failure(val error: String, val cause: Throwable? = null) : DecompileResult()
}

// Use Result<T> for simple operations
suspend fun extractDex(apk: File): Result<List<File>> {
    return runCatching {
        // extraction logic
    }
}
```

### 3. Performance Considerations
- **Parallel processing**: Decompile multiple DEX files concurrently
- **Memory management**: Stream large files, don't load entirely into memory
- **Caching**: Cache Maven Central API responses
- **Progress feedback**: Show progress for long operations

### 4. Dependency Detection Strategy

#### Level 1: Package Name Analysis
```kotlin
// Common package prefixes → Known libraries
"androidx.appcompat" → "androidx.appcompat:appcompat:1.6.1"
"com.squareup.okhttp3" → "com.squareup.okhttp3:okhttp:4.11.0"
```

#### Level 2: Bytecode Signature Analysis
```kotlin
// Analyze class structure, method signatures
class BytecodeAnalyzer {
    fun analyzeClass(classFile: File): ClassSignature {
        // Use ASM to parse bytecode
        // Extract: methods, fields, annotations
        // Match against library-signatures.json
    }
}
```

#### Level 3: Maven Central API
```kotlin
// Query Maven Central for exact versions
suspend fun searchMaven(className: String): List<MavenArtifact> {
    val url = "https://search.maven.org/solrsearch/select?q=fc:$className"
    // Parse response, return matching artifacts
}
```

### 5. Build.gradle Generation
Use FreeMarker templates for clean separation:

**Template** (`build.gradle.ftl`):
```groovy
plugins {
    id 'com.android.application' version '${androidGradlePluginVersion}'
<#if hasKotlin>
    id 'org.jetbrains.kotlin.android' version '${kotlinVersion}'
</#if>
}

android {
    namespace '${packageName}'
    compileSdk ${compileSdk}

    defaultConfig {
        applicationId "${applicationId}"
        minSdk ${minSdk}
        targetSdk ${targetSdk}
        versionCode ${versionCode}
        versionName "${versionName}"
    }

    buildTypes {
        release {
            minifyEnabled ${minifyEnabled?c}
<#if proguardFiles?has_content>
            proguardFiles <#list proguardFiles as file>'${file}'<#sep>, </#list>
</#if>
        }
    }

    compileOptions {
        sourceCompatibility JavaVersion.VERSION_${javaVersion}
        targetCompatibility JavaVersion.VERSION_${javaVersion}
    }
}

dependencies {
<#list dependencies as dep>
    implementation '${dep.groupId}:${dep.artifactId}:${dep.version}'
</#list>
}
```

**Usage**:
```kotlin
class BuildGradleGenerator(private val config: Configuration) {
    fun generate(projectInfo: AndroidProject): String {
        val template = config.getTemplate("build.gradle.ftl")
        val dataModel = mapOf(
            "androidGradlePluginVersion" to "8.2.0",
            "kotlinVersion" to "1.9.20",
            "hasKotlin" to projectInfo.hasKotlinCode,
            "packageName" to projectInfo.packageName,
            "compileSdk" to projectInfo.compileSdk,
            // ... more fields
            "dependencies" to projectInfo.dependencies
        )
        return template.process(dataModel)
    }
}
```

## Implementation Phases

### Phase 1: APK Decompilation (Week 1-2)

**Goal**: Extract and decompile APK contents

**Tasks**:
- [ ] Implement `DexExtractor.kt`
  - [ ] Extract APK as ZIP
  - [ ] Find all DEX files (classes.dex, classes2.dex, ...)
  - [ ] Extract to temp directory
- [ ] Implement `JadxDecompiler.kt`
  - [ ] Download/bundle JADX CLI
  - [ ] Run JADX on DEX files
  - [ ] Parse JADX output
  - [ ] Handle decompilation errors gracefully
- [ ] Implement `ResourceExtractor.kt`
  - [ ] Use APKTool to decode resources
  - [ ] Extract res/ directory
  - [ ] Extract assets/
- [ ] Implement `ManifestParser.kt`
  - [ ] Parse AndroidManifest.xml
  - [ ] Extract: package name, version, min/target SDK
  - [ ] Extract: permissions, activities, services
- [ ] Command: `apk2project decompile input.apk`

**JADX Integration**:
```kotlin
class JadxDecompiler {
    suspend fun decompile(dexFiles: List<File>, outputDir: File): DecompileResult {
        val jadxPath = findJadx() // Look in resources or PATH

        val command = listOf(
            jadxPath,
            "--output-dir", outputDir.absolutePath,
            "--no-res",                    // Skip resources (we use APKTool)
            "--show-bad-code",             // Show problematic code
            "--threads-count", "4",        // Parallel decompilation
            *dexFiles.map { it.absolutePath }.toTypedArray()
        )

        val process = ProcessBuilder(command)
            .redirectErrorStream(true)
            .start()

        // Parse output for progress
        var classesProcessed = 0
        var totalClasses = 0

        process.inputStream.bufferedReader().useLines { lines ->
            lines.forEach { line ->
                when {
                    line.contains("INFO  - loading") -> {
                        // Extract total classes count
                    }
                    line.contains("INFO  - processing") -> {
                        classesProcessed++
                        showProgress(classesProcessed, totalClasses)
                    }
                    line.contains("ERROR") -> {
                        logger.error(line)
                    }
                }
            }
        }

        val exitCode = process.waitFor()

        return when {
            exitCode == 0 -> DecompileResult.Success(outputDir, stats)
            exitCode == 1 && outputDir.exists() ->
                DecompileResult.PartialSuccess(outputDir, errors)
            else ->
                DecompileResult.Failure("JADX failed with code $exitCode")
        }
    }
}
```

### Phase 2: Dependency Analysis (Week 3-4)

**Goal**: Detect all external libraries and dependencies

**Tasks**:
- [ ] Implement `BytecodeAnalyzer.kt`
  - [ ] Use ASM to parse .class files
  - [ ] Extract import statements
  - [ ] Collect unique package names
- [ ] Implement `LibraryDetector.kt`
  - [ ] Load `library-signatures.json` (curated database)
  - [ ] Match package prefixes to known libraries
  - [ ] Detect library versions from class signatures
- [ ] Implement `MavenResolver.kt`
  - [ ] Query Maven Central API
  - [ ] Cache API responses
  - [ ] Handle rate limiting
- [ ] Implement `ObfuscationDetector.kt`
  - [ ] Detect ProGuard/R8 obfuscation patterns
  - [ ] Analyze mapping.txt if present
  - [ ] Warn about deobfuscation challenges
- [ ] Command: `apk2project analyze input.apk`

**Library Signatures Database** (`library-signatures.json`):
```json
{
  "libraries": [
    {
      "name": "OkHttp",
      "groupId": "com.squareup.okhttp3",
      "artifactId": "okhttp",
      "packagePrefix": "okhttp3",
      "classSignatures": [
        "okhttp3.OkHttpClient",
        "okhttp3.Request",
        "okhttp3.Response"
      ],
      "versionDetection": {
        "class": "okhttp3.OkHttp",
        "field": "VERSION",
        "type": "static String"
      }
    },
    {
      "name": "Retrofit",
      "groupId": "com.squareup.retrofit2",
      "artifactId": "retrofit",
      "packagePrefix": "retrofit2",
      "classSignatures": [
        "retrofit2.Retrofit",
        "retrofit2.Call",
        "retrofit2.Callback"
      ]
    }
  ]
}
```

**Detection Algorithm**:
```kotlin
class LibraryDetector(private val signaturesFile: File) {
    private val libraryDb: List<LibrarySignature> = loadSignatures()

    fun detectLibraries(sourceDir: File): List<DetectedLibrary> {
        val importedPackages = scanImports(sourceDir)
        val detectedLibs = mutableListOf<DetectedLibrary>()

        for (lib in libraryDb) {
            val confidence = calculateConfidence(lib, importedPackages)
            if (confidence > 0.7) {
                val version = detectVersion(lib, sourceDir) ?: lib.latestVersion
                detectedLibs.add(DetectedLibrary(lib, version, confidence))
            }
        }

        return detectedLibs.sortedByDescending { it.confidence }
    }

    private fun scanImports(sourceDir: File): Set<String> {
        val imports = mutableSetOf<String>()
        sourceDir.walkTopDown()
            .filter { it.extension == "java" || it.extension == "kt" }
            .forEach { file ->
                file.useLines { lines ->
                    lines.filter { it.trim().startsWith("import ") }
                        .forEach { line ->
                            val pkg = line.substringAfter("import ")
                                .substringBefore(";")
                                .trim()
                            imports.add(pkg)
                        }
                }
            }
        return imports
    }
}
```

### Phase 3: Gradle Project Generation (Week 5-6)

**Goal**: Create buildable Android Gradle project

**Tasks**:
- [ ] Implement `ProjectGenerator.kt` (orchestrator)
- [ ] Implement `BuildGradleGenerator.kt`
  - [ ] Detect Android Gradle Plugin version
  - [ ] Generate dependencies block
  - [ ] Handle Kotlin vs Java
  - [ ] Add necessary repositories
- [ ] Implement `SettingsGradleGenerator.kt`
- [ ] Implement `SourceOrganizer.kt`
  - [ ] Move Java files to `src/main/java/`
  - [ ] Move Kotlin files to `src/main/kotlin/`
  - [ ] Preserve package structure
- [ ] Implement `ResourceOrganizer.kt`
  - [ ] Move resources to `res/`
  - [ ] Move assets to `assets/`
  - [ ] Handle resource conflicts
- [ ] Implement `ManifestGenerator.kt`
  - [ ] Clean up manifest
  - [ ] Remove unnecessary permissions
  - [ ] Ensure proper namespacing
- [ ] Command: `apk2project generate input.apk --output ./project`

**Project Structure Output**:
```
generated-project/
├── build.gradle                 # Generated
├── settings.gradle              # Generated
├── gradle.properties            # Generated
├── gradle/
│   └── wrapper/                 # Copy from template
├── src/
│   ├── main/
│   │   ├── java/               # Decompiled Java sources
│   │   │   └── com/example/app/
│   │   ├── kotlin/             # Decompiled Kotlin sources (if any)
│   │   ├── res/                # Extracted resources
│   │   ├── assets/             # Extracted assets
│   │   └── AndroidManifest.xml # Cleaned manifest
│   └── test/
│       └── java/               # Empty (placeholder)
├── proguard-rules.pro          # If detected
├── README.md                   # Generated guide
└── analysis-report.html        # Dependency analysis report
```

### Phase 4: Build Verification (Week 7)

**Goal**: Ensure generated project builds successfully

**Tasks**:
- [ ] Implement `ProjectVerifier.kt`
  - [ ] Run `./gradlew assembleDebug --dry-run`
  - [ ] Detect Gradle sync errors
  - [ ] Identify missing dependencies
- [ ] Implement `DependencyResolver.kt`
  - [ ] Suggest missing dependencies
  - [ ] Auto-add common dependencies
  - [ ] Handle version conflicts
- [ ] Implement `CompileChecker.kt`
  - [ ] Parse compilation errors
  - [ ] Categorize errors (missing deps, syntax, etc.)
  - [ ] Provide fixing suggestions
- [ ] Command: `apk2project verify ./project`

**Verification Flow**:
```kotlin
class ProjectVerifier {
    suspend fun verify(projectDir: File): VerificationResult {
        val results = mutableListOf<VerificationStep>()

        // Step 1: Check project structure
        results.add(verifyStructure(projectDir))

        // Step 2: Gradle sync
        results.add(runGradleSync(projectDir))

        // Step 3: Resolve dependencies
        val missingDeps = findMissingDependencies(projectDir)
        if (missingDeps.isNotEmpty()) {
            results.add(VerificationStep.MissingDependencies(missingDeps))
        }

        // Step 4: Compile check
        results.add(runCompileCheck(projectDir))

        // Calculate success rate
        val successRate = results.count { it.isSuccess } / results.size.toFloat()

        return VerificationResult(
            steps = results,
            successRate = successRate,
            buildable = successRate >= 0.8
        )
    }

    private suspend fun runGradleSync(projectDir: File): VerificationStep {
        val gradlew = File(projectDir, if (isWindows()) "gradlew.bat" else "gradlew")
        if (!gradlew.exists()) {
            copyGradleWrapper(projectDir)
        }

        val command = listOf(
            gradlew.absolutePath,
            "tasks",
            "--quiet"
        )

        return runCatching {
            val process = ProcessBuilder(command)
                .directory(projectDir)
                .redirectErrorStream(true)
                .start()

            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()

            if (exitCode == 0) {
                VerificationStep.GradleSync(success = true)
            } else {
                VerificationStep.GradleSync(
                    success = false,
                    error = output
                )
            }
        }.getOrElse { e ->
            VerificationStep.GradleSync(success = false, error = e.message ?: "Unknown error")
        }
    }
}
```

### Phase 5: Advanced Features (Week 8-9)

**Goal**: Production-ready capabilities

**Tasks**:
- [ ] Multi-APK support (base + splits)
- [ ] Kotlin code quality improvements
- [ ] ProGuard rule generation
- [ ] Native library handling (.so files)
- [ ] Build variant detection (debug/release)
- [ ] Automated unit test generation
- [ ] HTML report generation

**Report Generation**:
```kotlin
class ReportGenerator {
    fun generateHtml(analysis: AnalysisResult): File {
        val html = """
        <!DOCTYPE html>
        <html>
        <head>
            <title>APK Analysis Report</title>
            <style>
                /* CSS styles */
            </style>
        </head>
        <body>
            <h1>APK Analysis Report</h1>
            <h2>Package Info</h2>
            <table>
                <tr><td>Package Name</td><td>${analysis.packageName}</td></tr>
                <tr><td>Version</td><td>${analysis.versionName}</td></tr>
                <tr><td>Target SDK</td><td>${analysis.targetSdk}</td></tr>
            </table>

            <h2>Detected Dependencies (${analysis.dependencies.size})</h2>
            <table>
                <tr>
                    <th>Library</th>
                    <th>Group ID</th>
                    <th>Artifact ID</th>
                    <th>Version</th>
                    <th>Confidence</th>
                </tr>
                ${analysis.dependencies.joinToString("\n") { dep ->
                    "<tr><td>${dep.name}</td><td>${dep.groupId}</td><td>${dep.artifactId}</td><td>${dep.version}</td><td>${dep.confidence}%</td></tr>"
                }}
            </table>

            <h2>Project Statistics</h2>
            <ul>
                <li>Classes: ${analysis.classCount}</li>
                <li>Methods: ${analysis.methodCount}</li>
                <li>Obfuscated: ${if (analysis.isObfuscated) "Yes" else "No"}</li>
                <li>Build Success Rate: ${analysis.buildSuccessRate}%</li>
            </ul>
        </body>
        </html>
        """

        val reportFile = File("analysis-report.html")
        reportFile.writeText(html)
        return reportFile
    }
}
```

## Testing Strategy

### Unit Tests
```kotlin
class LibraryDetectorTest {
    @Test
    fun `should detect OkHttp from package imports`() {
        val detector = LibraryDetector(signaturesFile)
        val imports = setOf(
            "okhttp3.OkHttpClient",
            "okhttp3.Request",
            "okhttp3.Response"
        )

        val detected = detector.matchLibrary(imports)

        assertEquals("OkHttp", detected?.name)
        assertTrue(detected?.confidence!! > 0.9)
    }
}
```

### Integration Tests
```kotlin
@Test
fun `end-to-end APK to project generation`() = runBlocking {
    val testApk = File("test-resources/sample.apk")
    val outputDir = Files.createTempDirectory("test-project").toFile()

    val generator = ProjectGenerator()
    val result = generator.generate(testApk, outputDir)

    assertTrue(result is GenerateResult.Success)
    assertTrue(File(outputDir, "build.gradle").exists())
    assertTrue(File(outputDir, "src/main/java").exists())

    // Verify it builds
    val verifier = ProjectVerifier()
    val verifyResult = verifier.verify(outputDir)
    assertTrue(verifyResult.buildable)
}
```

## Success Criteria

### Functional Goals
- ✅ 85%+ decompilation success rate
- ✅ 90%+ dependency detection accuracy
- ✅ 80%+ generated projects build successfully
- ✅ Support Android 7.0+ APKs (API 24+)
- ✅ Handle obfuscated code gracefully

### Performance Goals
- ✅ Decompile 100MB APK in < 3 minutes
- ✅ Generate project in < 1 minute
- ✅ Parallel processing for multiple DEX files

### UX Goals
- ✅ Clear progress indicators
- ✅ Detailed analysis reports
- ✅ Actionable error messages
- ✅ Professional CLI output

## Known Challenges

### Challenge 1: Obfuscated Code
**Problem**: ProGuard/R8 makes code unreadable
**Solution**:
- Detect obfuscation patterns
- Provide warning to user
- Attempt pattern-based deobfuscation
- Generate "best effort" project

### Challenge 2: Native Libraries
**Problem**: .so files can't be decompiled
**Solution**:
- Extract .so files to `jniLibs/`
- Add CMake/NDK configuration
- Document native dependencies
- Mark project as "partial"

### Challenge 3: Dynamic Code Loading
**Problem**: Apps using reflection, class loaders
**Solution**:
- Detect reflection patterns
- Document dynamic code usage
- Generate commented placeholders

### Challenge 4: Exact Version Detection
**Problem**: Hard to determine exact library versions
**Solution**:
- Check for version constants in code
- Query Maven for latest stable version
- Provide version range in comments

## Quick Start

```bash
# Initialize project
./gradlew init --type kotlin-application

# Download JADX
mkdir -p src/main/resources/tools/jadx
cd src/main/resources/tools/jadx
wget https://github.com/skylot/jadx/releases/download/v1.4.7/jadx-1.4.7.zip
unzip jadx-1.4.7.zip

# Build
./gradlew build

# Test
./gradlew test

# Run
./gradlew run --args="decompile sample.apk"
```

---

**Important**: This is an advanced project requiring deep understanding of Android internals, bytecode analysis, and build systems. Take time to understand each component before implementing!
