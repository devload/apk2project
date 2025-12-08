# Mission: APK to Gradle Project Converter

## 🎯 Project Goal

Android APK 파일을 분석하여 **빌드 가능한 Java/Kotlin Gradle 프로젝트로 복원**하는 CLI 도구 개발

### Why This Matters
- **역공학 분석**: 앱의 구조와 의존성 이해
- **레거시 복원**: 소스코드를 잃어버린 앱 복구
- **보안 감사**: 앱의 내부 구현 검토
- **개발 참고**: 다른 앱의 구현 방식 학습

### End Result
```bash
$ apk2project generate app.apk --output ./recovered-project
# 3 minutes later...
✅ Project generated successfully!
   → cd recovered-project && ./gradlew assembleDebug
```

---

## 📦 What You're Building

### Core Product
**CLI tool** that reverse-engineers APK files into buildable Android projects

### Key Commands
```bash
apk2project decompile <input.apk>              # DEX → Java source
apk2project analyze <input.apk>                # Dependency analysis
apk2project generate <input.apk> --output DIR # Full project generation
apk2project verify <project-dir>               # Test buildability
```

### User Journey
```
Step 1: User has APK file (no source code)
Step 2: Run: apk2project generate app.apk --output ./project
Step 3: Wait 3-5 minutes (decompilation + analysis + generation)
Step 4: Open ./project in Android Studio
Step 5: Build and run successfully (80%+ success rate)
```

---

## 🏗️ Technical Architecture

### System Flow
```
┌──────────────┐
│  Input APK   │
└──────┬───────┘
       │
       ↓
┌─────────────────────────────────────────────┐
│           APK ANALYSIS PIPELINE             │
├─────────────────────────────────────────────┤
│  1️⃣  DECOMPILATION                          │
│     ├─ Extract DEX files from APK           │
│     ├─ JADX: DEX → Java source code         │
│     ├─ APKTool: Resources extraction        │
│     └─ Parse AndroidManifest.xml            │
├─────────────────────────────────────────────┤
│  2️⃣  DEPENDENCY ANALYSIS                    │
│     ├─ Scan import statements               │
│     ├─ Analyze bytecode (ASM)               │
│     ├─ Match against library database       │
│     ├─ Query Maven Central API              │
│     └─ Detect obfuscation (ProGuard/R8)     │
├─────────────────────────────────────────────┤
│  3️⃣  PROJECT GENERATION                     │
│     ├─ Generate build.gradle (FreeMarker)   │
│     ├─ Generate settings.gradle             │
│     ├─ Organize source files (src/main/)    │
│     ├─ Organize resources (res/, assets/)   │
│     └─ Clean AndroidManifest.xml            │
├─────────────────────────────────────────────┤
│  4️⃣  VERIFICATION (Optional)                │
│     ├─ Gradle sync test                     │
│     ├─ Dependency resolution check          │
│     ├─ Compilation test                     │
│     └─ Generate analysis report             │
└─────────────────────────────────────────────┘
       │
       ↓
┌───────────────────────────┐
│  Output: Gradle Project   │
│  ├─ build.gradle          │
│  ├─ src/main/java/        │
│  ├─ src/main/res/         │
│  └─ README.md             │
└───────────────────────────┘
```

---

## 💡 Core Components Deep Dive

### Component 1: JADX Decompiler Integration

**Purpose**: Convert DEX bytecode to readable Java source code

**Implementation Strategy**:
```kotlin
class JadxDecompiler {
    private val jadxPath: String = findJadxExecutable()

    suspend fun decompile(apkFile: File, outputDir: File): DecompileResult {
        // Step 1: Extract DEX files
        val dexFiles = extractDexFiles(apkFile)

        // Step 2: Run JADX
        val command = buildJadxCommand(dexFiles, outputDir)
        val process = startProcess(command)

        // Step 3: Monitor progress
        val stats = monitorDecompilation(process)

        // Step 4: Return result
        return when {
            stats.errorCount == 0 ->
                DecompileResult.Success(outputDir, stats)
            stats.errorCount < stats.totalClasses * 0.2 ->
                DecompileResult.PartialSuccess(outputDir, stats)
            else ->
                DecompileResult.Failure("Too many decompilation errors")
        }
    }

    private fun buildJadxCommand(dexFiles: List<File>, output: File): List<String> {
        return listOf(
            jadxPath,
            "--output-dir", output.absolutePath,
            "--no-res",                  // Skip resources (use APKTool)
            "--show-bad-code",           // Show problematic code
            "--escape-unicode",          // Readable Unicode
            "--threads-count", "4",      // Parallel processing
            "--deobf",                   // Try deobfuscation
            *dexFiles.map { it.absolutePath }.toTypedArray()
        )
    }
}
```

**Key Challenges**:
1. **Large APKs**: 100+ MB APKs take 5-10 minutes
   - Solution: Show detailed progress, parallel processing
2. **Obfuscated code**: ProGuard makes code unreadable
   - Solution: Enable `--deobf`, accept limitations
3. **Incomplete decompilation**: Some classes fail
   - Solution: Continue anyway, mark failed classes

### Component 2: Dependency Analyzer

**Purpose**: Detect all external libraries used in the APK

**Three-Level Detection System**:

#### Level 1: Package Prefix Matching (Fast, 70% accuracy)
```kotlin
class PackagePrefixDetector {
    private val knownLibraries = mapOf(
        "androidx.appcompat" to "androidx.appcompat:appcompat",
        "com.squareup.okhttp3" to "com.squareup.okhttp3:okhttp",
        "com.google.gson" to "com.google.code.gson:gson",
        "retrofit2" to "com.squareup.retrofit2:retrofit",
        // ... 100+ more entries
    )

    fun detectByPrefix(importedPackages: Set<String>): List<Dependency> {
        return importedPackages.mapNotNull { pkg ->
            knownLibraries.entries.find { (prefix, _) ->
                pkg.startsWith(prefix)
            }?.let { (_, artifact) ->
                parseDependency(artifact)
            }
        }
    }
}
```

#### Level 2: Bytecode Signature Matching (Slower, 85% accuracy)
```kotlin
class BytecodeSignatureDetector(private val signaturesDb: LibraryDatabase) {
    fun detectBySignature(classFiles: List<File>): List<DetectedLibrary> {
        val classSignatures = analyzeClasses(classFiles)

        return signaturesDb.libraries.mapNotNull { lib ->
            val matchedSignatures = lib.signatures.count { signature ->
                classSignatures.contains(signature)
            }

            val confidence = matchedSignatures.toFloat() / lib.signatures.size

            if (confidence > 0.7) {
                DetectedLibrary(lib, confidence)
            } else null
        }
    }

    private fun analyzeClasses(files: List<File>): Set<ClassSignature> {
        return files.flatMap { analyzeClass(it) }.toSet()
    }

    private fun analyzeClass(file: File): List<ClassSignature> {
        val classReader = ClassReader(file.readBytes())
        val visitor = SignatureExtractor()
        classReader.accept(visitor, 0)
        return visitor.signatures
    }
}
```

#### Level 3: Maven Central API Query (Slowest, 95% accuracy)
```kotlin
class MavenCentralResolver {
    private val httpClient = OkHttpClient()
    private val cache = mutableMapOf<String, MavenArtifact>()

    suspend fun resolveClass(fullyQualifiedName: String): MavenArtifact? {
        if (cache.containsKey(fullyQualifiedName)) {
            return cache[fullyQualifiedName]
        }

        val query = "fc:\"$fullyQualifiedName\""
        val url = "https://search.maven.org/solrsearch/select?q=$query&rows=5"

        val response = httpClient.newCall(Request.Builder().url(url).build()).execute()
        val json = Gson().fromJson(response.body?.string(), MavenSearchResult::class.java)

        val artifact = json.response.docs.firstOrNull()?.let {
            MavenArtifact(it.g, it.a, it.latestVersion)
        }

        cache[fullyQualifiedName] = artifact
        return artifact
    }
}
```

**Combined Strategy**:
```kotlin
class DependencyAnalyzer(
    private val prefixDetector: PackagePrefixDetector,
    private val signatureDetector: BytecodeSignatureDetector,
    private val mavenResolver: MavenCentralResolver
) {
    suspend fun analyze(projectSources: File): List<Dependency> {
        val allDependencies = mutableMapOf<String, Dependency>()

        // Level 1: Fast prefix matching
        val imports = scanImports(projectSources)
        prefixDetector.detectByPrefix(imports).forEach { dep ->
            allDependencies[dep.artifactId] = dep.copy(confidence = 0.7f)
        }

        // Level 2: Bytecode signature (for missing deps)
        val classFiles = findCompiledClasses(projectSources)
        signatureDetector.detectBySignature(classFiles).forEach { detected ->
            if (!allDependencies.containsKey(detected.artifactId)) {
                allDependencies[detected.artifactId] = detected.toDependency()
            }
        }

        // Level 3: Maven Central (for unknown classes)
        val unknownClasses = findUnknownClasses(imports, allDependencies.values)
        unknownClasses.take(10).forEach { className ->
            mavenResolver.resolveClass(className)?.let { artifact ->
                allDependencies[artifact.artifactId] = artifact.toDependency()
            }
        }

        return allDependencies.values.toList()
    }
}
```

### Component 3: Build.gradle Generator

**Purpose**: Create a valid build.gradle file with all dependencies

**FreeMarker Template Approach**:

**Template** (`build.gradle.ftl`):
```groovy
plugins {
    id 'com.android.application' version '${agpVersion}'
<#if kotlinVersion??>
    id 'org.jetbrains.kotlin.android' version '${kotlinVersion}'
</#if>
}

android {
    namespace '${namespace}'
    compileSdk ${compileSdk}

    defaultConfig {
        applicationId "${applicationId}"
        minSdk ${minSdk}
        targetSdk ${targetSdk}
        versionCode ${versionCode}
        versionName "${versionName}"

        testInstrumentationRunner "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            minifyEnabled ${minifyEnabled?c}
<#if proguardFiles?has_content>
            proguardFiles getDefaultProguardFile('proguard-android-optimize.txt'), 'proguard-rules.pro'
</#if>
        }
    }

    compileOptions {
        sourceCompatibility JavaVersion.VERSION_${javaVersion}
        targetCompatibility JavaVersion.VERSION_${javaVersion}
    }

<#if kotlinVersion??>
    kotlinOptions {
        jvmTarget = '${javaVersion}'
    }
</#if>

<#if buildFeatures?has_content>
    buildFeatures {
<#list buildFeatures as feature, enabled>
        ${feature} ${enabled?c}
</#list>
    }
</#if>
}

dependencies {
    // Core Android libraries
<#list coreDependencies as dep>
    implementation '${dep.toGradleNotation()}'
</#list>

    // Detected third-party libraries
<#list detectedDependencies as dep>
    implementation '${dep.toGradleNotation()}' // Confidence: ${dep.confidence}%
</#list>

    // Testing dependencies
    testImplementation 'junit:junit:4.13.2'
    androidTestImplementation 'androidx.test.ext:junit:1.1.5'
    androidTestImplementation 'androidx.test.espresso:espresso-core:3.5.1'
}
```

**Generator Code**:
```kotlin
class BuildGradleGenerator(private val freeMarker: Configuration) {
    fun generate(projectInfo: AndroidProject, dependencies: List<Dependency>): String {
        val template = freeMarker.getTemplate("build.gradle.ftl")

        val dataModel = mapOf(
            "agpVersion" to "8.2.0",
            "kotlinVersion" to if (projectInfo.hasKotlin) "1.9.20" else null,
            "namespace" to projectInfo.packageName,
            "applicationId" to projectInfo.packageName,
            "compileSdk" to projectInfo.targetSdk,
            "minSdk" to projectInfo.minSdk,
            "targetSdk" to projectInfo.targetSdk,
            "versionCode" to projectInfo.versionCode,
            "versionName" to projectInfo.versionName,
            "minifyEnabled" to projectInfo.isMinified,
            "javaVersion" to determineJavaVersion(projectInfo),
            "coreDependencies" to getCoreDependencies(projectInfo),
            "detectedDependencies" to dependencies.sortedByDescending { it.confidence },
            "buildFeatures" to detectBuildFeatures(projectInfo),
            "proguardFiles" to if (projectInfo.isMinified) listOf("proguard-rules.pro") else emptyList()
        )

        val writer = StringWriter()
        template.process(dataModel, writer)
        return writer.toString()
    }

    private fun getCoreDependencies(project: AndroidProject): List<Dependency> {
        return listOf(
            Dependency("androidx.core", "core-ktx", "1.12.0"),
            Dependency("androidx.appcompat", "appcompat", "1.6.1"),
            Dependency("com.google.android.material", "material", "1.11.0")
        )
    }

    private fun determineJavaVersion(project: AndroidProject): Int {
        return when {
            project.targetSdk >= 34 -> 17  // Android 14+
            project.targetSdk >= 31 -> 11  // Android 12+
            else -> 8                       // Older versions
        }
    }

    private fun detectBuildFeatures(project: AndroidProject): Map<String, Boolean> {
        return buildMap {
            if (project.hasDataBinding) put("dataBinding", true)
            if (project.hasViewBinding) put("viewBinding", true)
            if (project.hasCompose) put("compose", true)
        }
    }
}
```

### Component 4: Project Verifier

**Purpose**: Ensure generated project can build

**Verification Steps**:
```kotlin
class ProjectVerifier {
    suspend fun verify(projectDir: File): VerificationReport {
        val steps = mutableListOf<VerificationStep>()

        // Step 1: Project structure validation
        steps.add(verifyProjectStructure(projectDir))

        // Step 2: Gradle wrapper setup
        if (!hasGradleWrapper(projectDir)) {
            setupGradleWrapper(projectDir)
        }

        // Step 3: Gradle sync
        steps.add(runGradleSync(projectDir))

        // Step 4: Dependency resolution
        steps.add(resolveDependencies(projectDir))

        // Step 5: Compile check
        steps.add(checkCompilation(projectDir))

        // Calculate overall score
        val successRate = steps.count { it.passed } / steps.size.toFloat()

        return VerificationReport(
            steps = steps,
            successRate = successRate,
            buildable = successRate >= 0.8,
            suggestions = generateSuggestions(steps)
        )
    }

    private suspend fun runGradleSync(projectDir: File): VerificationStep {
        val command = listOf(
            "./gradlew",
            "tasks",
            "--console=plain"
        )

        return try {
            val output = executeCommand(projectDir, command)
            VerificationStep(
                name = "Gradle Sync",
                passed = !output.contains("FAILURE"),
                message = if (output.contains("FAILURE")) "Gradle sync failed" else "OK",
                details = output
            )
        } catch (e: Exception) {
            VerificationStep(
                name = "Gradle Sync",
                passed = false,
                message = "Failed to run Gradle: ${e.message}",
                details = e.stackTraceToString()
            )
        }
    }

    private suspend fun checkCompilation(projectDir: File): VerificationStep {
        val command = listOf(
            "./gradlew",
            "compileDebugJavaWithJavac",
            "--console=plain"
        )

        val output = executeCommand(projectDir, command)
        val compilationErrors = parseCompilationErrors(output)

        return VerificationStep(
            name = "Compilation",
            passed = compilationErrors.isEmpty(),
            message = if (compilationErrors.isEmpty()) {
                "Compilation successful"
            } else {
                "${compilationErrors.size} compilation errors found"
            },
            details = compilationErrors.joinToString("\n")
        )
    }

    private fun generateSuggestions(steps: List<VerificationStep>): List<String> {
        val suggestions = mutableListOf<String>()

        steps.filter { !it.passed }.forEach { step ->
            when (step.name) {
                "Gradle Sync" -> {
                    suggestions.add("Check build.gradle for syntax errors")
                    suggestions.add("Ensure all repositories are accessible")
                }
                "Dependency Resolution" -> {
                    suggestions.add("Some dependencies may not exist in Maven Central")
                    suggestions.add("Try updating dependency versions manually")
                }
                "Compilation" -> {
                    suggestions.add("Review decompiled code for errors")
                    suggestions.add("Some obfuscated code may not compile correctly")
                }
            }
        }

        return suggestions
    }
}
```

---

## 🎨 User Experience Design

### Command: `apk2project generate`

**Full Output Example**:
```bash
$ apk2project generate /path/to/app.apk --output ./recovered-project

🚀 APK to Project Converter v1.0

📦 Analyzing APK...
   ├─ Package: com.example.myapp
   ├─ Version: 2.5.3 (253)
   ├─ Min SDK: 24 (Android 7.0)
   ├─ Target SDK: 34 (Android 14)
   ├─ APK size: 45.2 MB
   └─ DEX files: 3 found

🔍 Decompiling DEX files...
   ├─ classes.dex (2,345 classes) ████████████████████████░░░░░░ 85%
   ├─ classes2.dex (1,234 classes) ████████████████████████████ 100%
   └─ classes3.dex (567 classes) ████████████████████████████░░ 92%
✅ Decompiled 4,146 classes (88% success rate)

⚠️  Obfuscation detected: ProGuard/R8
   → Some class/method names may be unclear (e.g., 'a', 'b', 'c')

📚 Analyzing dependencies...
   ├─ Scanning imports... (4,892 unique packages)
   ├─ Matching library signatures... (142 potential matches)
   ├─ Querying Maven Central... (15 API calls)
   └─ Resolving versions... ⏳

✅ Detected 27 dependencies:
   Core Libraries (100% confidence):
   ├─ androidx.appcompat:appcompat:1.6.1
   ├─ androidx.core:core-ktx:1.12.0
   └─ com.google.android.material:material:1.11.0

   Third-party Libraries (85%+ confidence):
   ├─ com.squareup.retrofit2:retrofit:2.9.0
   ├─ com.squareup.okhttp3:okhttp:4.11.0
   ├─ com.google.code.gson:gson:2.10.1
   └─ ... and 21 more

   Uncertain Dependencies (60-80% confidence):
   ├─ com.unknown.library:library:? (version unknown)
   └─ 2 more (manual review recommended)

🏗️  Generating Gradle project...
   ├─ Creating directory structure... ✓
   ├─ Organizing source files... ████████████████████████████████ 100%
   │  ├─ Moved 4,146 classes to src/main/java/
   │  └─ Preserved package structure
   ├─ Extracting resources... ████████████████████████████████ 100%
   │  ├─ Layouts: 42 XML files
   │  ├─ Drawables: 127 images
   │  ├─ Values: 8 XML files
   │  └─ Assets: 3.2 MB
   ├─ Generating build.gradle... ✓
   ├─ Generating settings.gradle... ✓
   ├─ Generating gradle.properties... ✓
   ├─ Setting up Gradle wrapper... ✓
   └─ Creating README.md... ✓

✅ Project generated successfully!
   → Location: ./recovered-project

📊 Project Statistics:
   ├─ Total classes: 4,146
   ├─ Total methods: 38,492
   ├─ Dependencies: 27 detected
   ├─ Resources: 180 files
   └─ Estimated build success: 85%

🔧 Verifying project... (optional, skip with --no-verify)
   ├─ Gradle sync... ✓ (12s)
   ├─ Dependency resolution... ✓ (8s)
   └─ Compilation check... ⚠️  (2 minor errors)

⚠️  Minor issues found:
   1. Missing dependency: com.unknown.library:library
      → Suggestion: Search Maven Central manually
   2. Compilation error in MainActivity.java:142
      → Suggestion: Review decompiled code

📝 Analysis report saved to: ./recovered-project/analysis-report.html

✅ SUCCESS! Your project is 85% ready to build.

💡 Next Steps:
   1. cd recovered-project
   2. Open in Android Studio
   3. Review and fix any compilation errors
   4. Update dependency versions if needed
   5. Run: ./gradlew assembleDebug

⏱️  Total time: 3m 42s
```

---

## 🚀 Implementation Roadmap

### Week 1-2: Decompilation Foundation
- [ ] Set up Kotlin project with Gradle
- [ ] Integrate JADX (download, bundle, or use as dependency)
- [ ] Implement DexExtractor
- [ ] Implement JadxDecompiler
- [ ] Implement ResourceExtractor (APKTool)
- [ ] Implement ManifestParser
- [ ] Create `apk2project decompile` command
- [ ] Handle errors gracefully
- [ ] Test with 5-10 sample APKs

### Week 3-4: Dependency Analysis
- [ ] Implement PackagePrefixDetector
- [ ] Create library-signatures.json database (100+ libraries)
- [ ] Implement BytecodeSignatureDetector (ASM integration)
- [ ] Implement MavenCentralResolver
- [ ] Implement ObfuscationDetector
- [ ] Create `apk2project analyze` command
- [ ] Test accuracy on known APKs
- [ ] Optimize performance (caching, parallel processing)

### Week 5-6: Project Generation
- [ ] Set up FreeMarker for templates
- [ ] Create build.gradle.ftl template
- [ ] Create settings.gradle.ftl template
- [ ] Implement BuildGradleGenerator
- [ ] Implement SourceOrganizer
- [ ] Implement ResourceOrganizer
- [ ] Implement ManifestGenerator
- [ ] Create `apk2project generate` command
- [ ] Test on real APKs
- [ ] Verify generated projects build

### Week 7: Verification & Polish
- [ ] Implement ProjectVerifier
- [ ] Implement DependencyResolver
- [ ] Implement CompileChecker
- [ ] Create `apk2project verify` command
- [ ] Generate HTML reports
- [ ] Add progress bars and colors
- [ ] Improve error messages
- [ ] Write comprehensive documentation

### Week 8-9: Advanced Features
- [ ] Multi-APK support (split APKs)
- [ ] Kotlin code quality improvements
- [ ] Native library handling
- [ ] ProGuard rule generation
- [ ] Automated test generation
- [ ] CI/CD configuration generation
- [ ] Performance optimizations

---

## 📊 Success Metrics

### Decompilation Success Rate
- ✅ Target: 85%+ classes decompiled successfully
- ✅ Measure: (Successfully decompiled classes) / (Total classes)

### Dependency Detection Accuracy
- ✅ Target: 90%+ correct dependencies
- ✅ Measure: Compare with known APKs' build.gradle

### Build Success Rate
- ✅ Target: 80%+ generated projects build
- ✅ Measure: `./gradlew assembleDebug` succeeds without manual fixes

### Performance
- ✅ 50 MB APK → Project in < 3 minutes
- ✅ 100 MB APK → Project in < 5 minutes

---

## 🐛 Known Limitations & Challenges

### 1. Obfuscated Code
**Challenge**: ProGuard/R8 makes code unreadable
**Impact**: 40% of commercial apps use obfuscation
**Mitigation**:
- Use JADX's built-in deobfuscation (`--deobf`)
- Generate code with comments: `// Obfuscated: original name unknown`
- Accept lower build success rate (60-70% for obfuscated apps)

### 2. Native Libraries (.so files)
**Challenge**: Can't decompile native code
**Impact**: 30% of apps use native libraries
**Mitigation**:
- Extract .so files to `jniLibs/`
- Add CMake configuration placeholder
- Document native dependencies in README

### 3. Exact Version Detection
**Challenge**: Hard to determine exact dependency versions
**Impact**: May cause build failures due to version incompatibilities
**Mitigation**:
- Use latest stable version from Maven
- Add comments with version uncertainty
- Provide version range suggestions

### 4. Dynamic Code Loading
**Challenge**: Reflection, class loaders, dynamic proxies
**Impact**: Missing dependencies and runtime errors
**Mitigation**:
- Analyze string constants for class names
- Add TODO comments for manual review
- Warn in verification report

### 5. Custom Build Logic
**Challenge**: Gradle plugins, custom tasks, build variants
**Impact**: Generated build.gradle may be incomplete
**Mitigation**:
- Use sensible defaults
- Add comments for manual configuration
- Provide template for common scenarios

---

## 🎓 Learning Resources

### JADX
- [JADX GitHub](https://github.com/skylot/jadx)
- CLI usage: `jadx --help`
- [Deobfuscation guide](https://github.com/skylot/jadx/wiki/Deobfuscation)

### ASM (Bytecode Analysis)
- [ASM Official Site](https://asm.ow2.io/)
- [ASM Guide](https://asm.ow2.io/asm4-guide.pdf)
- Example: Analyzing class structure

### Maven Central API
- [Search API](https://search.maven.org/classic/#api)
- Query format: `https://search.maven.org/solrsearch/select?q=fc:"com.example.ClassName"`

### FreeMarker
- [FreeMarker Manual](https://freemarker.apache.org/docs/)
- [Template syntax](https://freemarker.apache.org/docs/dgui_template_overallstructure.html)

### Android Gradle Plugin
- [AGP User Guide](https://developer.android.com/build)
- [DSL Reference](https://google.github.io/android-gradle-dsl/)

---

## 🔬 Testing Strategy

### Unit Tests
```kotlin
@Test
fun `should detect Retrofit from imports`() {
    val detector = LibraryDetector()
    val imports = setOf(
        "retrofit2.Retrofit",
        "retrofit2.Call",
        "retrofit2.http.GET"
    )

    val detected = detector.detect(imports)

    assertTrue(detected.any { it.artifactId == "retrofit" })
}
```

### Integration Tests
```kotlin
@Test
fun `end-to-end test with sample APK`() = runTest {
    val sampleApk = File("test-resources/sample-app.apk")
    val outputDir = Files.createTempDirectory("test").toFile()

    val generator = ProjectGenerator()
    val result = generator.generate(sampleApk, outputDir)

    assertTrue(result is GenerateResult.Success)
    assertTrue(File(outputDir, "build.gradle").exists())

    val verifier = ProjectVerifier()
    val verification = verifier.verify(outputDir)
    assertTrue(verification.successRate > 0.8)
}
```

### Real-World Testing
- Test with 50+ real APKs from Google Play
- Categorize by: size, obfuscation, complexity
- Track success rates per category

---

## 🎬 Getting Started

1. **Read CLAUDE.md** thoroughly
2. **Study JADX source code** (understand decompilation)
3. **Experiment with FreeMarker** templates
4. **Start with decompilation** (simplest component)
5. **Build incrementally** (test each component separately)

**First Milestone**: Successfully decompile an APK and print class names

Good luck! This is a challenging but rewarding project. 🚀
