# APK2Project

Android APK to Gradle Project Converter with AI-Powered Deobfuscation

## 🎯 Key Features

### PARSE 0: APK → Gradle Project
- **APK Decompilation**: Convert DEX files to Java source code
- **Resource Extraction**: Restore all resources (layouts, images, strings, etc.)
- **Dependency Analysis**: Auto-detect used libraries (54 library signatures built-in)
- **Project Generation**: Create buildable Gradle project structure

### AI Deobfuscation (PARSE 1-5)
- **🤖 AI-Powered Deobfuscation**: Ollama, Claude API support
- **📊 Call Graph Analysis**: Bottom-up method analysis
- **🎯 Priority Processing**: Analyze important methods first
- **⚡ Parallel Processing**: Multi-core utilization for speed
- **🌐 Real-time Monitoring**: Track progress with Next.js Dashboard

## 📋 System Requirements

### Required

1. **JDK 17+**
   ```bash
   # macOS
   brew install openjdk@17

   # Ubuntu/Debian
   sudo apt install openjdk-17-jdk
   ```

2. **JADX** (DEX decompiler)
   ```bash
   # macOS
   brew install jadx

   # Linux - manual install
   wget https://github.com/skylot/jadx/releases/download/v1.5.0/jadx-1.5.0.zip
   unzip jadx-1.5.0.zip -d /opt/jadx
   export PATH=$PATH:/opt/jadx/bin
   ```

3. **APKTool** (resource extractor)
   ```bash
   # macOS
   brew install apktool

   # Linux
   wget https://raw.githubusercontent.com/iBotPeaches/Apktool/master/scripts/linux/apktool
   wget https://bitbucket.org/iBotPeaches/apktool/downloads/apktool_2.9.3.jar
   chmod +x apktool
   mv apktool apktool_2.9.3.jar /usr/local/bin/
   ```

### Optional (for AI Deobfuscation)

4. **Ollama** (local AI server)
   ```bash
   # macOS/Linux
   curl https://ollama.ai/install.sh | sh

   # Download models
   ollama pull deepseek-coder:6.7b
   ollama pull qwen2.5  # For Korean translation
   ```

5. **Node.js 20+** (Dashboard)
   ```bash
   # macOS
   brew install node

   # Ubuntu/Debian
   sudo apt install nodejs npm
   ```

## 🔧 Installation

```bash
# 1. Clone project
git clone https://github.com/devload/apk2project.git
cd apk2project

# 2. Build CLI
./gradlew build

# 3. Install Dashboard
cd dashboard
npm install
cd ..
```

## ⚙️ Configuration (output.properties)

Configure settings via `output.properties` in project root.

```properties
# APK2Project Configuration

# Output directory (relative or absolute path)
# Example: output.dir=generate_project
output.dir=generate_project

# Dashboard port
# Example: dashboard.port=3000
dashboard.port=3000

# Ollama server
# Example: ollama.baseUrl=http://localhost:11434
ollama.baseUrl=http://localhost:11434
```

**Setup Instructions**:
1. Uncomment lines in `output.properties` by removing `#`
2. Enter your desired values
3. Save and restart pipeline

**Note**: `output.properties` is committed to Git as a template. Configure per project.

## 📚 Usage

### 1. Prepare APK File

#### Extract APK from Android Device

```bash
# 1. Find package name
adb shell pm list packages | grep <appname>

# 2. Find APK path
adb shell pm path <packagename>
# Example: adb shell pm path com.example.myapp

# 3. Pull APK
adb pull <apkpath> ./
# Example: adb pull /data/app/~~abc123==/com.example.myapp-xyz==/base.apk ./myapp.apk
```

### 2. Commands

#### `generate` - Project Generation (Recommended)

Analyze APK and generate complete Gradle project.

```bash
# Basic usage (PARSE 0 only)
./gradlew run --args="generate <APKFILE> -o <OUTPUTDIR>"

# Enable AI deobfuscation
./gradlew run --args="generate <APKFILE> -o <OUTPUTDIR> --ai"

# Example
./gradlew run --args="generate ./sample.apk -o ./output/sample_project"

# AI + Korean translation
./gradlew run --args="generate ./sample.apk -o ./output --ai --korean"
```

**Options**:
- `-o, --output <DIR>`: Output directory (default: `./<appname>_project`)
- `--ai`: Enable AI deobfuscation
- `--ai-client <ollama|claude|codex>`: AI client selection (default: ollama)
- `--model <MODEL>`: AI model name (default: deepseek-coder:6.7b)
- `--korean`: Enable Korean translation
- `--translation-model <MODEL>`: Translation model (default: qwen2.5)
- `--batch-size <N>`: Batch size (default: 10)
- `-v, --verbose`: Verbose logging

**Output Structure**:
```
output/sample_project/
├── app/
│   ├── build.gradle          # Auto-generated build config
│   ├── proguard-rules.pro
│   └── src/
│       └── main/
│           ├── AndroidManifest.xml
│           ├── java/         # Decompiled Java sources
│           ├── res/          # Resource files
│           └── assets/       # Asset files
├── .apk2project/
│   ├── status.json           # Progress (for Dashboard)
│   ├── dashboard.html        # Legacy dashboard
│   └── cache/                # AI cache
├── build.gradle              # Root build file
├── settings.gradle
├── gradle.properties
├── gradle/wrapper/           # Gradle Wrapper
└── README.md                 # Generated project info
```

#### `analyze` - Dependency Analysis

Analyze libraries used in APK.

```bash
# Basic usage
./gradlew run --args="analyze <APKFILE>"

# Show all dependencies (including low confidence)
./gradlew run --args="analyze ./sample.apk --all"

# Verbose logging
./gradlew run --args="analyze ./sample.apk -v"
```

**Output Example**:
```
═══════════════════════════════════════
  APK Information
═══════════════════════════════════════
  Package: com.example.myapp
  Version: 1.0.0 (1)
  Min SDK: 21
  Target SDK: 34
  DEX files: 5

═══════════════════════════════════════
  Detected Dependencies (32 total)
═══════════════════════════════════════
  High Confidence (12):
  ✓ com.squareup.okhttp3:okhttp:4.12.0
  ✓ com.squareup.retrofit2:retrofit:2.9.0
  ✓ com.google.code.gson:gson:2.10.1
  ...
```

#### `decompile` - Decompile Only

Decompile APK and extract source code only.

```bash
./gradlew run --args="decompile <APKFILE> -o <OUTPUTDIR>"

# Example
./gradlew run --args="decompile ./sample.apk -o ./decompiled"
```

#### `verify` - Project Verification

Verify buildability of generated project.

```bash
./gradlew run --args="verify <PROJECTDIR>"

# Example
./gradlew run --args="verify ./output/sample_project"
```

**Verification Items**:
- Project structure check
- build.gradle file existence
- Source file existence
- Resource file existence

## 🎛️ Dashboard Usage

Monitor pipeline progress in real-time.

### How to Start

```bash
# Terminal 1: Run pipeline
./gradlew run --args="generate ./sample.apk -o ./output --ai"

# Terminal 2: Start Dashboard
cd dashboard
./start-dashboard.sh
# Or
PORT=3000 npm run dev
```

### Access Dashboard

Open browser at **http://localhost:3000**

### Dashboard Features

1. **Pipeline Visualization**
   - PARSE 0: APK → Gradle conversion stages
   - Phase 1-5: AI Deobfuscation stages
   - Real-time progress display

2. **System Monitoring**
   - CPU usage
   - Memory usage
   - GPU usage (Mac Apple Silicon)

3. **Progress Status**
   - Files/methods processed
   - Success/failure statistics
   - Estimated completion time

4. **Recent Changes**
   - Recent rename list
   - AI request logs

### Dashboard Auto Port Selection

`start-dashboard.sh` reads port from `output.properties` and tries next ports if busy:

```bash
# dashboard.port=3000 in output.properties
cd dashboard
./start-dashboard.sh
# Tries 3001, 3002... automatically if port 3000 is busy
```

## 🤖 AI Deobfuscation Details

### Pipeline Stages

**PARSE 0: APK → Gradle Project**
1. AndroidManifest parsing
2. APK decompilation (JADX)
3. Resource extraction (APKTool)
4. Dependency analysis
5. Gradle project generation

**Phase 1: File Parsing**
- Parallel file parsing (20 workers)
- Extract methods/classes from Java files

**Phase 2: Call Graph Building**
- Analyze method call relationships
- Create reverse call graph

**Phase 3: Method Deobfuscation**
- Process leaf methods first
- AI-based name inference
- Batch processing (parallel)

**Phase 4: Class Deobfuscation**
- Deobfuscate class names
- Reconstruct package structure

**Phase 5: Apply Renames**
- Apply renames to source code
- Create backups

### AI Model Recommendations

| Model | Size | Speed | Quality | Use Case |
|------|------|------|---------|----------|
| **deepseek-coder:6.7b** | 4.5GB | ⚡⚡⚡ | ⭐⭐⭐ | **Recommended** - Balanced |
| deepseek-coder:33b | 20GB | ⚡ | ⭐⭐⭐⭐⭐ | Highest quality |
| qwen2.5:7b | 4.5GB | ⚡⚡⚡ | ⭐⭐⭐ | General code |
| qwen2.5 | 4.7GB | ⚡⚡ | ⭐⭐ | Korean translation |

### Performance Optimization

```bash
# Fast processing (smaller model + large batch)
./gradlew run --args="generate ./sample.apk -o ./output --ai --model deepseek-coder:6.7b --batch-size 50"

# Highest quality
./gradlew run --args="generate ./sample.apk -o ./output --ai --model deepseek-coder:33b --batch-size 10"

# Korean translation
./gradlew run --args="generate ./sample.apk -o ./output --ai --korean --translation-model qwen2.5"
```

## 📊 Test Results

### PARSE 0 (APK → Gradle)

| Item | Result |
|------|--------|
| APK Size | ~150 MB |
| DEX Files | ~10 (Multi-DEX) |
| Decompiled Classes | ~35,000 |
| Success Rate | 95-99% |
| Detected Dependencies | ~50 |
| Processing Time | ~3 minutes |

### AI Deobfuscation

| Item | deepseek-coder:6.7b | deepseek-coder:33b |
|------|---------------------|-------------------|
| Processing Speed | ~500 methods/min | ~150 methods/min |
| Quality | Good | Best |
| Memory | 6GB | 20GB |
| GPU | Recommended | Required |

## 📦 Detectable Libraries (54)

### Android/Google
- AndroidX (appcompat, core, fragment, recyclerview, constraintlayout, etc.)
- Google Play Services (auth, location, maps)
- Firebase (analytics, messaging, crashlytics, auth)
- Material Components

### Networking
- OkHttp, Retrofit
- Volley

### Image
- Glide, Picasso
- Coil

### Data/Serialization
- Gson, Moshi
- Room, Realm
- DataStore

### Async/Reactive
- RxJava, RxAndroid
- Kotlin Coroutines

### DI
- Dagger, Hilt

### UI
- Lottie
- ViewPager2

### Other
- ZXing (QR/barcode)
- Apache Commons
- Timber (logging)

## 🔧 Troubleshooting

### JADX Not Found

```
Error: JADX not found. Install with: brew install jadx
```

**Fix**: Check if JADX is in PATH
```bash
which jadx
# If no output, install required
```

### APKTool Not Found

```
Warning: Could not decode AndroidManifest.xml
```

**Fix**: Verify APKTool installation
```bash
which apktool
apktool --version
```

### Dashboard Shows IDLE

**Issue**: Dashboard displays "Waiting for pipeline to start..."

**Fix**:
1. Check if `output.properties` exists in project root
2. Verify `output.dir` matches actual output path
3. Restart Dashboard: `cd dashboard && ./start-dashboard.sh`

### Ollama Connection Failed

```
Error: Failed to connect to Ollama at http://localhost:11434
```

**Fix**:
```bash
# Check Ollama server
curl http://localhost:11434/api/tags

# Start Ollama
ollama serve

# Check models
ollama list
```

### Out of Memory

Large APK processing may cause memory errors.

```bash
# Increase Gradle JVM memory
./gradlew run -Dorg.gradle.jvmargs="-Xmx8g" --args="generate large.apk -o output"

# Use smaller model
./gradlew run --args="generate large.apk -o output --ai --model deepseek-coder:6.7b"
```

### Dashboard Port Conflict

```
Error: Port 3000 already in use
```

**Fix**:
1. Use different port: `cd dashboard && PORT=3001 npm run dev`
2. Or change `dashboard.port` in `output.properties`
3. Auto selection script already tries next ports

### Build Errors

Common errors when building generated project:

1. **Duplicate class error**
   - Remove duplicate dependencies in build.gradle

2. **Missing resource error**
   - Some resource references may be broken due to obfuscation
   - Manual R class reference fixes may be needed

3. **API compatibility error**
   - Adjust minSdk/targetSdk versions

## 🚫 Limitations

- **Obfuscated Code**: ProGuard/R8 obfuscated code is restored but readability is reduced
- **Native Libraries**: .so files are copied only, not decompiled
- **Dynamic Loading**: Reflection or dynamic class loading may not be perfectly restored
- **Accurate Versions**: Library versions are estimates and may not be accurate
- **AI Accuracy**: AI-inferred names may not always be accurate

## 📄 License

MIT License

## ⚖️ Legal Disclaimer

**IMPORTANT**: This tool is intended for educational and security research purposes only.

### Users must agree to:

1. **Legal Use Only**
   - Use only when you have legal rights for APK reverse engineering
   - Apply only to apps you developed or have explicit permission
   - Prohibited from using for intellectual property infringement

2. **Disclaimer of Liability**
   - Developer is not responsible for any legal issues arising from use of this tool
   - APK reverse engineering may violate laws in your country of residence
   - No warranty for damages from commercial use

3. **Security Research Purpose**
   - Use only for ethical purposes like vulnerability analysis, security research
   - Publish research results responsibly

4. **User Responsibility**
   - User is solely responsible for all consequences of using this tool
   - User is liable for all legal problems from illegal use

**Summary**: Developer assumes no liability for any problems caused by using this tool. All responsibility lies with the user.

---

*Generated by [APK2Project](https://github.com/devload/apk2project)*
