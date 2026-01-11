# APK2Project - Claude Development Guide

## Project Overview
**APK to Gradle Project Converter with AI-Powered Deobfuscation**

Android APK 파일을 분석하여 빌드 가능한 Java/Kotlin Gradle 프로젝트로 복원하고, AI 기반 난독화 복호화 기능을 제공하는 CLI 도구입니다.

### Key Features
- 🔍 APK 디컴파일 및 소스 코드 복원
- 🤖 AI 기반 난독화된 메서드/클래스 이름 복원 (Ollama, Claude API 지원)
- 🌐 실시간 모니터링 대시보드 (Next.js)
- ⚡ 병렬 처리를 통한 성능 최적화
- 📊 Call Graph 분석 및 Bottom-up 처리
- 🇰🇷 한글 번역 지원 (Qwen 모델)

---

## Development Environment

### Language & Framework
- **Primary**: Kotlin (JVM)
- **Build Tool**: Gradle (Kotlin DSL)
- **CLI Framework**: Clikt (https://ajalt.github.io/clikt/)
- **Dashboard**: Next.js 16 + React 19 + TypeScript
- **Template Engine**: FreeMarker (build.gradle 생성)

### Key Dependencies
```kotlin
// build.gradle.kts
dependencies {
    // CLI & Core
    implementation("com.github.ajalt.clikt:clikt:4.2.1")
    implementation("org.freemarker:freemarker:2.3.32")
    implementation("com.google.code.gson:gson:2.10.1")

    // Bytecode Analysis
    implementation("org.ow2.asm:asm:9.6")
    implementation("org.ow2.asm:asm-commons:9.6")
    implementation("org.ow2.asm:asm-util:9.6")

    // HTTP & API
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Java Parser (AST)
    implementation("com.github.javaparser:javaparser-symbol-solver-core:3.25.8")

    // Graph Processing
    implementation("org.jgrapht:jgrapht-core:1.5.2")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")

    // Logging
    implementation("org.slf4j:slf4j-api:2.0.9")
    implementation("ch.qos.logback:logback-classic:1.4.11")

    // Testing
    testImplementation("io.kotest:kotest-runner-junit5:5.8.0")
    testImplementation("io.mockk:mockk:1.13.8")
}
```

### Dashboard Dependencies (Next.js)
```json
{
  "dependencies": {
    "next": "16.1.1",
    "react": "19.2.3",
    "react-dom": "19.2.3",
    "recharts": "^3.6.0",
    "@xyflow/react": "^12.10.0",
    "react-syntax-highlighter": "^16.1.0",
    "socket.io-client": "^4.8.3"
  }
}
```

---

## Project Structure

```
apk2project/
├── CLAUDE.md                          # This file - Development guide
├── README.md                          # User documentation
├── build.gradle.kts                   # Gradle build configuration
├── settings.gradle.kts
│
├── dashboard/                         # Next.js Dashboard
│   ├── app/
│   │   ├── api/status/route.ts       # API endpoint for status.json
│   │   ├── components/
│   │   │   ├── SystemMonitor.tsx     # CPU/Memory charts
│   │   │   └── WorkflowGraph.tsx     # Pipeline visualization
│   │   ├── page.tsx                  # Main dashboard page
│   │   └── layout.tsx
│   ├── package.json
│   ├── next.config.ts
│   └── tsconfig.json
│
└── src/
    ├── main/
    │   ├── kotlin/com/whatap/apk2project/
    │   │   ├── Main.kt                              # CLI entry point
    │   │   │
    │   │   ├── commands/                            # CLI Commands
    │   │   │   ├── DecompileCommand.kt              # APK → Java source
    │   │   │   ├── AnalyzeCommand.kt                # Dependency analysis
    │   │   │   ├── GenerateCommand.kt               # Generate Gradle project
    │   │   │   ├── VerifyCommand.kt                 # Verify buildability
    │   │   │   └── FixCommand.kt                    # Fix + AI deobfuscation
    │   │   │
    │   │   ├── decompiler/                          # Decompilation
    │   │   │   ├── JadxDecompiler.kt                # JADX wrapper
    │   │   │   ├── DexExtractor.kt                  # Extract DEX
    │   │   │   ├── ResourceExtractor.kt             # Extract resources
    │   │   │   └── ManifestParser.kt                # Parse AndroidManifest
    │   │   │
    │   │   ├── analyzer/                            # Dependency Analysis
    │   │   │   ├── DependencyAnalyzer.kt
    │   │   │   ├── LibraryDetector.kt
    │   │   │   ├── BytecodeAnalyzer.kt
    │   │   │   └── MavenResolver.kt
    │   │   │
    │   │   ├── fixer/                               # Code Fixing
    │   │   │   └── CodeFixer.kt                     # Fix compilation errors
    │   │   │
    │   │   ├── deobfuscator/                        # AI Deobfuscation (NEW)
    │   │   │   ├── DeobfuscationOrchestrator.kt     # Main orchestrator
    │   │   │   │
    │   │   │   ├── parser/                          # Parsing & Analysis
    │   │   │   │   └── MethodCallGraphBuilder.kt    # Build call graph (parallel)
    │   │   │   │
    │   │   │   ├── graph/                           # Graph Processing
    │   │   │   │   ├── CallGraph.kt                 # Graph data structures
    │   │   │   │   ├── CallGraphBuilder.kt          # Build graph
    │   │   │   │   └── LeafScorer.kt                # Leaf method scoring
    │   │   │   │
    │   │   │   ├── analysis/                        # Analysis Strategy
    │   │   │   │   ├── BottomUpScheduler.kt         # Bottom-up scheduling
    │   │   │   │   └── HeuristicAnalyzer.kt         # Rule-based analysis
    │   │   │   │
    │   │   │   ├── context/                         # Context Management
    │   │   │   │   ├── ContextStore.kt              # Store results
    │   │   │   │   └── ContextBuilder.kt            # Build context
    │   │   │   │
    │   │   │   ├── client/                          # AI Clients
    │   │   │   │   ├── AiClient.kt                  # Interface
    │   │   │   │   ├── OllamaClient.kt              # Ollama integration
    │   │   │   │   ├── ClaudeCodeClient.kt          # Claude API
    │   │   │   │   ├── CodexClient.kt               # Codex API
    │   │   │   │   └── TranslationClient.kt         # Korean translation
    │   │   │   │
    │   │   │   ├── ai/                              # AI Processing
    │   │   │   │   ├── ClaudeClient.kt              # Claude API client
    │   │   │   │   ├── PromptBuilder.kt             # Prompt generation
    │   │   │   │   └── ResponseParser.kt            # Response parsing
    │   │   │   │
    │   │   │   ├── pipeline/                        # Pipeline
    │   │   │   │   └── DeobfuscationPipeline.kt     # Main pipeline
    │   │   │   │
    │   │   │   ├── renamer/                         # Renaming
    │   │   │   │   └── SourceRenamer.kt             # Apply renames
    │   │   │   │
    │   │   │   ├── monitor/                         # Monitoring
    │   │   │   │   ├── ProgressMonitor.kt           # Progress tracking
    │   │   │   │   └── DashboardServer.kt           # Legacy HTTP server
    │   │   │   │
    │   │   │   ├── session/                         # Session Management
    │   │   │   │   └── SessionManager.kt
    │   │   │   │
    │   │   │   └── models/                          # Data Models
    │   │   │       ├── DeobfuscationModels.kt
    │   │   │       ├── CallGraphModels.kt
    │   │   │       └── AnalysisModels.kt
    │   │   │
    │   │   ├── generator/                           # Project Generation
    │   │   │   ├── ProjectGenerator.kt
    │   │   │   ├── BuildGradleGenerator.kt
    │   │   │   └── SourceOrganizer.kt
    │   │   │
    │   │   └── utils/                               # Utilities
    │   │       ├── Logger.kt
    │   │       └── FileUtils.kt
    │   │
    │   └── resources/
    │       ├── templates/                           # FreeMarker templates
    │       │   ├── build.gradle.ftl
    │       │   └── settings.gradle.ftl
    │       └── dashboard.html                       # Legacy dashboard
    │
    └── test/
        └── kotlin/com/whatap/apk2project/
            ├── deobfuscator/
            └── fixer/
```

---

## Module Details

### 1. CLI Commands (`commands/`)

#### FixCommand.kt
**가장 중요한 명령어** - CodeFixer + AI 난독화 복호화 통합

**기능:**
- 디컴파일된 Java 코드의 컴파일 에러 자동 수정
- AI 기반 난독화 복호화 (`--ai` 플래그)
- ProgressMonitor를 통한 실시간 진행 상황 추적
- Next.js 대시보드 연동

**사용법:**
```bash
# 기본 Fix (컴파일 에러만 수정)
apk2project fix ./decompiled-source

# AI 난독화 복호화 활성화
apk2project fix ./decompiled-source --ai

# Ollama 모델 지정
apk2project fix ./decompiled-source --ai --model deepseek-coder:6.7b

# 한글 번역 활성화
apk2project fix ./decompiled-source --ai --korean

# Claude API 사용
apk2project fix ./decompiled-source --ai --ai-client claude
```

**주요 코드:**
```kotlin
class FixCommand : CliktCommand() {
    private val ai by option("--ai").flag(default = false)
    private val aiClientType by option("--ai-client").choice("ollama", "claude", "codex")
    private val modelName by option("--model").default("deepseek-coder:6.7b")
    private val enableKorean by option("--korean").flag(default = false)

    override fun run() {
        // 1. ProgressMonitor 초기화 (status.json 생성)
        val monitor = ProgressMonitor(outputDir)
        monitor.start()

        // 2. CodeFixer 실행 (parallel processing)
        val fixer = CodeFixer()
        val result = fixer.fixSourceDirectory(sourceDir, monitor)

        // 3. AI 난독화 복호화 (optional)
        if (ai) {
            val pipeline = DeobfuscationPipeline(sourceDir, outputDir, config)
            pipeline.run()
        }
    }
}
```

---

### 2. Code Fixer (`fixer/`)

#### CodeFixer.kt
**역할:** JADX 디컴파일 결과의 컴파일 에러 자동 수정

**주요 기능:**
- **병렬 파일 스캔** (8 workers, 100 files/chunk)
- Missing method stub 생성
- Type mismatch 수정
- Syntax error 수정
- ProgressMonitor 연동

**처리 과정:**
1. Pass 1: 모든 Java 파일에서 missing methods 찾기 (병렬)
2. Pass 2: 코드 수정 적용 (병렬)
3. Stub 클래스 생성

**코드 예시:**
```kotlin
fun fixSourceDirectory(
    sourceDir: File,
    progressMonitor: ProgressMonitor? = null
): FixResult {
    val javaFiles = sourceDir.walkTopDown()
        .filter { it.extension == "java" }
        .toList()

    progressMonitor?.totalFilesToParse = javaFiles.size

    runBlocking {
        // Pass 1: Parallel scan
        javaFiles.chunked(500).forEach { chunk ->
            val jobs = chunk.map { file ->
                async(Dispatchers.IO) {
                    missingMethods.addAll(findMissingMethods(file))
                    progressMonitor?.parsedFiles = processed.incrementAndGet()
                }
            }
            jobs.awaitAll()
        }

        // Pass 2: Parallel fix
        javaFiles.chunked(500).forEach { chunk ->
            val jobs = chunk.map { file ->
                async(Dispatchers.IO) {
                    fixJavaFile(file)
                }
            }
            jobs.awaitAll()
        }
    }
}
```

---

### 3. AI Deobfuscation Pipeline (`deobfuscator/`)

#### DeobfuscationPipeline.kt
**역할:** AI 기반 난독화 복호화 파이프라인 오케스트레이터

**5단계 파이프라인:**

**Phase 1: File Parsing**
```kotlin
private suspend fun phase1ParseFiles(): PhaseResult {
    val builder = MethodCallGraphBuilder()
    val result = builder.parseFilesOnly(sourceDir) { current, total ->
        monitor.parsedFiles = current
        monitor.totalFilesToParse = total
    }

    monitor.totalClasses.set(result.classCount)
    monitor.totalMethods.set(result.methodCount)
}
```

**Phase 2: Call Graph Building**
```kotlin
private suspend fun phase2BuildCallGraph(): PhaseResult {
    val graphResult = builder.buildCallGraph { current, total ->
        monitor.callGraphClasses = current
        monitor.totalCallGraphClasses = total
    }

    monitor.callGraphEdges = graphResult.edgeCount
}
```

**Phase 3: Method Deobfuscation**
```kotlin
private suspend fun phase3DeobfuscateMethods() {
    // 1. Leaf methods 추출 및 스코어링
    val leafMethods = findLeafMethods()
    val scored = LeafScorer().score(leafMethods)

    // 2. Bottom-up 순서로 처리
    val scheduler = BottomUpScheduler(callGraph)

    // 3. AI 분석 (배치 처리)
    scored.chunked(batchSize).forEach { batch ->
        val jobs = batch.map { method ->
            async {
                val context = contextBuilder.build(method)
                val result = aiClient.analyze(method, context)
                contextStore.save(method, result)
            }
        }
        jobs.awaitAll()
    }
}
```

**Phase 4: Class Deobfuscation**
```kotlin
private suspend fun phase4DeobfuscateClasses() {
    // Similar to Phase 3, but for classes
}
```

**Phase 5: Apply Renames**
```kotlin
private suspend fun phase5ApplyRenames() {
    val renamer = SourceRenamer(sourceDir)
    val renames = contextStore.getAllRenames()
    renamer.applyRenames(renames)
}
```

---

#### MethodCallGraphBuilder.kt
**역할:** Call Graph 구축 및 관계 분석

**핵심 기능:**
1. **병렬 파일 파싱** (JavaParser 사용)
2. **병렬 Call Graph 구축**
3. **Reverse Call Graph** (caller 추적용)

**병렬 처리 전략:**
```kotlin
fun parseFilesOnly(sourceDir: File): ParseResult {
    val javaFiles = sourceDir.walkTopDown()
        .filter { it.extension == "java" }
        .toList()

    val numWorkers = Runtime.getRuntime().availableProcessors().coerceAtLeast(4)
    val chunkSize = 100

    runBlocking {
        javaFiles.chunked(chunkSize).forEach { chunk ->
            val jobs = chunk.map { file ->
                async(Dispatchers.IO) {
                    val fileParser = JavaParser()  // Thread-safe: new instance
                    parseFile(file, fileParser)
                }
            }
            jobs.awaitAll()
        }
    }
}

fun buildCallGraph(): BuildGraphResult {
    val edgeList = ConcurrentHashMap.newKeySet<EdgeInfo>()

    runBlocking {
        classes.chunked(500).forEach { chunk ->
            val jobs = chunk.map { classNode ->
                async(Dispatchers.IO) {
                    // Extract method calls
                    val edges = extractMethodCalls(classNode)
                    edgeList.addAll(edges)
                }
            }
            jobs.awaitAll()
        }
    }

    // Add edges to graph (single-threaded for safety)
    edgeList.forEach { edge ->
        val fromExists = callGraph.containsVertex(edge.from)
        val toExists = callGraph.containsVertex(edge.to)

        if (fromExists && toExists) {
            try {
                callGraph.addEdge(edge.from, edge.to)
                reverseCallGraph.addEdge(edge.to, edge.from)
            } catch (e: Exception) {
                logger.debug("Skipped edge: ${e.message}")
            }
        }
    }
}
```

---

#### LeafScorer.kt
**역할:** Leaf method 우선순위 스코어링

**스코어링 기준:**
```kotlin
fun score(method: MethodNode): Int {
    if (shouldSkip(method)) return -1000

    var score = 0
    score += method.externalApiCalls.size * 10      // 외부 API 호출
    score += method.stringLiterals.size * 5         // 문자열 리터럴
    score += method.androidFrameworkHints * 8       // Android 타입
    score += if (method.hasAnnotations) 5 else 0    // 어노테이션
    score += if (method.isLifecycleMethod) 20 else 0 // onCreate 등

    return score
}

fun shouldSkip(method: MethodNode): Boolean {
    // Synthetic methods (access$, lambda$, etc.)
    if (method.isSynthetic) return true

    // Generic names
    if (method.name in listOf("toString", "hashCode", "equals")) return true

    // Too simple
    if (method.bodyTokens < 10 && method.signals.isEmpty()) return true

    return false
}
```

---

#### AI Clients (`client/`)

**OllamaClient.kt** - Ollama 로컬 모델
```kotlin
class OllamaClient(
    private val baseUrl: String = "http://localhost:11434",
    private val model: String = "deepseek-coder:6.7b"
) : AiClient {
    override suspend fun analyze(
        method: MethodNode,
        context: AnalysisContext
    ): DeobfuscationResult {
        val prompt = PromptBuilder.build(method, context)
        val response = client.post("$baseUrl/api/generate") {
            setBody(mapOf(
                "model" to model,
                "prompt" to prompt,
                "stream" to false
            ))
        }

        return ResponseParser.parse(response.bodyAsText())
    }
}
```

**ClaudeCodeClient.kt** - Claude API
```kotlin
class ClaudeCodeClient(
    private val apiKey: String,
    private val model: String = "claude-sonnet-4.5"
) : AiClient {
    override suspend fun analyze(
        method: MethodNode,
        context: AnalysisContext
    ): DeobfuscationResult {
        val prompt = PromptBuilder.build(method, context)
        val response = client.post("https://api.anthropic.com/v1/messages") {
            header("x-api-key", apiKey)
            header("anthropic-version", "2023-06-01")
            setBody(mapOf(
                "model" to model,
                "max_tokens" to 1024,
                "messages" to listOf(
                    mapOf("role" to "user", "content" to prompt)
                )
            ))
        }

        return ResponseParser.parse(response.bodyAsText())
    }
}
```

---

### 4. Monitoring & Dashboard

#### ProgressMonitor.kt
**역할:** 실시간 진행 상황 추적 및 status.json 생성

**데이터 수집:**
```kotlin
class ProgressMonitor(private val outputDir: File) {
    // Statistics
    val totalClasses = AtomicInteger(0)
    val totalMethods = AtomicInteger(0)
    val processedMethods = AtomicInteger(0)
    val renamedMethods = AtomicInteger(0)

    // Phase 1: File Parsing
    @Volatile var parsedFiles: Int = 0
    @Volatile var totalFilesToParse: Int = 0

    // Phase 2: Call Graph
    @Volatile var callGraphClasses: Int = 0
    @Volatile var callGraphEdges: Int = 0

    // Resource monitoring
    private val resourceHistory = ConcurrentLinkedQueue<ResourceSnapshot>()

    fun start() {
        startTime.set(System.currentTimeMillis())

        // CPU/Memory sampling (every 1s)
        cpuSamplingTimer = fixedRateTimer("cpu-sampler", daemon = true, period = 1000) {
            val cpuUsage = osBean.processCpuLoad * 100
            val memoryUsed = (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024

            resourceHistory.add(ResourceSnapshot(
                timestamp = System.currentTimeMillis(),
                cpuUsagePercent = cpuUsage,
                memoryUsedMb = memoryUsed.toInt(),
                memoryUsagePercent = (memoryUsed / memoryTotal) * 100
            ))

            updateStatus()
        }
    }

    private fun updateStatus() {
        val status = mapOf(
            "phase" to phase,
            "currentPhase" to currentPhase.name,
            "status" to status,
            "isRunning" to isRunning,
            "totalClasses" to totalClasses.get(),
            "totalMethods" to totalMethods.get(),
            "processedMethods" to processedMethods.get(),
            "renamedMethods" to renamedMethods.get(),
            "parsedFiles" to parsedFiles,
            "totalFilesToParse" to totalFilesToParse,
            "callGraphEdges" to callGraphEdges,
            "resourceHistory" to resourceHistory.takeLast(100),
            "cpuUsagePercent" to latestCpuUsage,
            "memoryUsedMb" to latestMemoryUsed,
            "lastUpdated" to System.currentTimeMillis()
        )

        statusFile.writeText(gson.toJson(status))
    }
}
```

#### Next.js Dashboard (`dashboard/`)

**SystemMonitor.tsx** - CPU/Memory Charts
```typescript
export default function SystemMonitor({ resourceHistory }: Props) {
  return (
    <div className="grid grid-cols-2 gap-4">
      {/* CPU Chart */}
      <div className="bg-slate-800 rounded-lg p-4">
        <h3>CPU Usage</h3>
        <LineChart data={resourceHistory}>
          <Line dataKey="cpuUsagePercent" stroke="#00d9ff" />
          <YAxis domain={[0, 100]} />
        </LineChart>
      </div>

      {/* Memory Chart */}
      <div className="bg-slate-800 rounded-lg p-4">
        <h3>Memory Usage</h3>
        <LineChart data={resourceHistory}>
          <Line dataKey="memoryUsedMb" stroke="#00ff88" />
        </LineChart>
      </div>
    </div>
  );
}
```

**WorkflowGraph.tsx** - Pipeline Visualization
```typescript
export default function WorkflowGraph({ currentPhase, ...props }: Props) {
  const nodes = [
    { id: 'parse', label: 'Parse Files', progress: props.phase1Progress },
    { id: 'graph', label: 'Build Graph', progress: props.phase2Progress },
    { id: 'analyze', label: 'AI Analysis', progress: props.phase3Progress },
    { id: 'rename', label: 'Apply Renames', progress: props.phase4Progress },
  ];

  return (
    <ReactFlow nodes={nodes} edges={edges}>
      <Controls />
      <Background />
    </ReactFlow>
  );
}
```

**API Route (`app/api/status/route.ts`)**
```typescript
export async function GET() {
  const statusPath = path.join(
    process.cwd(),
    '..',
    'hana-decompiled',
    'sources',
    '.apk2project',
    'status.json'
  );

  const data = fs.readFileSync(statusPath, 'utf-8');
  const status = JSON.parse(data);

  return NextResponse.json(status, {
    headers: {
      'Cache-Control': 'no-cache, no-store, must-revalidate',
    },
  });
}
```

---

## Quick Start Guide

### 1. Build the Project

```bash
# Clone repository
git clone https://github.com/devload/apk2project.git
cd apk2project

# Build Kotlin CLI
./gradlew build

# Build Next.js dashboard
cd dashboard
npm install
cd ..
```

### 2. Run Basic Commands

```bash
# Decompile APK
./gradlew run --args="decompile input.apk --output ./output"

# Fix compilation errors
./gradlew run --args="fix ./output/sources"

# Generate Gradle project
./gradlew run --args="generate input.apk --output ./project"
```

### 3. Run AI Deobfuscation

**Prerequisites:**
- Ollama installed: `curl https://ollama.ai/install.sh | sh`
- Model downloaded: `ollama pull deepseek-coder:6.7b`

```bash
# Start Ollama service
ollama serve

# Run deobfuscation
./gradlew run --args="fix ./decompiled-sources --ai"

# With Korean translation
./gradlew run --args="fix ./decompiled-sources --ai --korean"

# Use Claude API (requires ANTHROPIC_API_KEY)
export ANTHROPIC_API_KEY=sk-ant-xxxxx
./gradlew run --args="fix ./decompiled-sources --ai --ai-client claude"
```

### 4. Start Dashboard

**Terminal 1: Run deobfuscation**
```bash
./gradlew run --args="fix ./decompiled-sources --ai --korean"
```

**Terminal 2: Start Next.js dashboard**
```bash
cd dashboard
npm run dev
```

**Access Dashboard:**
- Open browser: http://localhost:3000
- Real-time updates every 3 seconds
- CPU/Memory graphs
- Pipeline visualization
- Code diff viewer

---

## Development Workflow

### Adding a New AI Client

1. **Implement AiClient interface:**
```kotlin
class MyCustomClient : AiClient {
    override suspend fun analyze(
        method: MethodNode,
        context: AnalysisContext
    ): DeobfuscationResult {
        // Your implementation
    }
}
```

2. **Register in AiClientType enum:**
```kotlin
enum class AiClientType {
    OLLAMA,
    CLAUDE,
    CODEX,
    MYCUSTOM  // Add here
}
```

3. **Add CLI option in FixCommand.kt:**
```kotlin
private val aiClientType by option("--ai-client")
    .choice("ollama", "claude", "codex", "mycustom")
```

### Modifying Dashboard Components

```bash
cd dashboard

# Edit components
vim app/components/SystemMonitor.tsx

# Test locally
npm run dev

# Build for production
npm run build
npm start
```

### Running Tests

```bash
# Run all tests
./gradlew test

# Run specific test
./gradlew test --tests "CodeFixerTest"

# With coverage
./gradlew test jacocoTestReport
```

---

## Performance Optimization

### Current Optimizations

1. **Parallel File Parsing**
   - Workers: CPU cores (min 4, default 8)
   - Chunk size: 100 files
   - Thread-safe: New JavaParser per file

2. **Parallel Call Graph Building**
   - Chunk size: 500 classes
   - Thread-safe: ConcurrentHashMap for edges
   - Single-threaded edge insertion (JGraphT requirement)

3. **Batch AI Processing**
   - Default batch size: 10 methods
   - Configurable via `--batch-size`
   - Async/await pattern

### Tuning Parameters

```bash
# Increase batch size for faster processing
./gradlew run --args="fix ./sources --ai --batch-size 20"

# Adjust worker count (internal config)
# Edit MethodCallGraphBuilder.kt:
val numWorkers = Runtime.getRuntime().availableProcessors()
```

---

## Troubleshooting

### Common Issues

**1. Port 3000 already in use**
```bash
# Kill process on port 3000
lsof -ti:3000 | xargs kill -9

# Or use different port
cd dashboard
PORT=3001 npm run dev
```

**2. Ollama connection refused**
```bash
# Check Ollama service
curl http://localhost:11434/api/tags

# Start Ollama
ollama serve
```

**3. Out of memory during parsing**
```bash
# Increase JVM heap
export GRADLE_OPTS="-Xmx8g"
./gradlew run --args="fix ./sources --ai"
```

**4. Call Graph vertex errors**
```bash
# This is normal for external library methods
# The system automatically skips missing vertices
# Check logs for: "Skipped N edges due to missing vertices"
```

---

## Architecture Decisions

### Why Next.js Dashboard?

1. **Modern stack**: React 19, TypeScript, Tailwind CSS
2. **Real-time updates**: Easy polling/WebSocket integration
3. **Rich components**: Recharts, React Flow, Syntax Highlighter
4. **SSR/API routes**: No CORS issues
5. **Developer experience**: Hot reload, TypeScript support

### Why Parallel Processing?

**Before optimization:**
- 35,374 files: ~15 minutes sequential
- Call graph: ~5 minutes sequential

**After optimization:**
- 35,374 files: ~3 minutes parallel (8 workers)
- Call graph: ~1 minute parallel
- **Total speedup: ~5x faster**

### Why Bottom-Up Analysis?

**Problem with Top-Down:**
```
A.a() → B.b() → C.c()
```
- Analyzing A first: No context about B.b() or C.c()
- Generic names: `doSomething()`, `process()`, `handle()`

**Bottom-Up Solution:**
```
1. Analyze C.c() first → "sendHttpRequest()"
2. Analyze B.b() with context → "fetchUserData()" (calls sendHttpRequest)
3. Analyze A.a() with full context → "handleLoginFlow()" (calls fetchUserData)
```

Result: **Contextually meaningful names**

---

## Future Improvements

### Planned Features

1. **WebSocket Support**
   - Replace polling with real-time updates
   - Implement in DashboardServer + Next.js

2. **Caching Layer**
   - Cache AI responses (method signature → result)
   - Persist to `deobfuscation-cache.jsonl`

3. **Incremental Analysis**
   - Only re-analyze changed methods
   - Use git diff for change detection

4. **Multi-APK Support**
   - Batch processing for multiple APKs
   - Parallel APK analysis

5. **Custom Prompts**
   - User-configurable prompt templates
   - Domain-specific knowledge injection

---

## Contributing

### Code Style

- Follow Kotlin coding conventions
- Use `val` over `var` when possible
- Prefer immutable data structures
- Document public APIs with KDoc

### Commit Messages

```
feat: Add parallel processing for file parsing
fix: Handle missing vertices in call graph
docs: Update CLAUDE.md with module details
refactor: Simplify PromptBuilder logic
test: Add unit tests for LeafScorer
```

### Pull Request Process

1. Create feature branch: `feature/your-feature-name`
2. Make changes and commit
3. Push to GitHub: `git push -u origin feature/your-feature-name`
4. Create Pull Request
5. Wait for review and CI checks

---

## License

This project is developed for internal use.

---

## Contact & Support

For questions or issues:
- Create GitHub issue
- Contact: devload@example.com

---

**Last Updated:** 2026-01-12
**Version:** 1.0.0 (Next.js Dashboard + Parallel Processing)
