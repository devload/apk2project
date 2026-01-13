# APK2Project

Android APK 파일을 분석하여 빌드 가능한 Gradle 프로젝트로 복원하고, AI 기반 난독화 복호화를 제공하는 CLI 도구

## 🎯 주요 기능

### PARSE 0: APK → Gradle Project
- **APK 디컴파일**: DEX 파일을 Java 소스코드로 변환
- **리소스 추출**: 레이아웃, 이미지, 문자열 등 모든 리소스 복원
- **의존성 분석**: 사용된 라이브러리 자동 감지 (54개 라이브러리 시그니처 내장)
- **프로젝트 생성**: 바로 빌드 가능한 Gradle 프로젝트 구조 생성

### AI Deobfuscation (PARSE 1-5)
- **🤖 AI 기반 난독화 복호화**: Ollama, Claude API 지원
- **📊 Call Graph 분석**: Bottom-up 방식으로 메서드 분석
- **🎯 우선순위 처리**: 중요한 메서드 먼저 복호화
- **⚡ 병렬 처리**: 다중 코어 활용으로 빠른 처리
- **🌐 실시간 모니터링**: Next.js Dashboard로 진행 상황 추적

## 📋 시스템 요구사항

### 필수 설치

1. **JDK 17 이상**
   ```bash
   # macOS
   brew install openjdk@17

   # Ubuntu/Debian
   sudo apt install openjdk-17-jdk
   ```

2. **JADX** (DEX 디컴파일러)
   ```bash
   # macOS
   brew install jadx

   # Linux - 수동 설치
   wget https://github.com/skylot/jadx/releases/download/v1.5.0/jadx-1.5.0.zip
   unzip jadx-1.5.0.zip -d /opt/jadx
   export PATH=$PATH:/opt/jadx/bin
   ```

3. **APKTool** (리소스 추출)
   ```bash
   # macOS
   brew install apktool

   # Linux
   wget https://raw.githubusercontent.com/iBotPeaches/Apktool/master/scripts/linux/apktool
   wget https://bitbucket.org/iBotPeaches/apktool/downloads/apktool_2.9.3.jar
   chmod +x apktool
   mv apktool apktool_2.9.3.jar /usr/local/bin/
   ```

### 선택 사항 (AI Deobfuscation용)

4. **Ollama** (로컬 AI 서버)
   ```bash
   # macOS/Linux
   curl https://ollama.ai/install.sh | sh

   # 모델 다운로드
   ollama pull deepseek-coder:6.7b
   ollama pull qwen2.5  # 한글 번역용
   ```

5. **Node.js 20+** (Dashboard)
   ```bash
   # macOS
   brew install node

   # Ubuntu/Debian
   sudo apt install nodejs npm
   ```

## 🔧 설치 방법

```bash
# 1. 프로젝트 클론
git clone https://github.com/devload/apk2project.git
cd apk2project

# 2. CLI 빌드
./gradlew build

# 3. Dashboard 설치
cd dashboard
npm install
cd ..
```

## ⚙️ 설정 (output.properties)

프로젝트 루트의 `output.properties` 파일로 설정을 관리합니다.

```properties
# APK2Project Configuration

# 출력 디렉토리 (상대 경로 또는 절대 경로)
# Example: output.dir=generate_project
output.dir=generate_project

# Dashboard 포트
# Example: dashboard.port=3000
dashboard.port=3000

# Ollama 서버
# Example: ollama.baseUrl=http://localhost:11434
ollama.baseUrl=http://localhost:11434
```

**설정 방법**:
1. `output.properties` 파일의 주석(#)을 제거
2. 원하는 값 입력
3. 저장 후 파이프라인 재시작

**참고**: `output.properties`는 Git에 템플릿 형태로 커밋되므로, 프로젝트별로 설정 필요

## 📚 사용 방법

### 1. APK 파일 준비

#### Android 기기에서 APK 추출

```bash
# 1. 패키지 이름 확인
adb shell pm list packages | grep <앱이름>

# 2. APK 경로 확인
adb shell pm path <패키지이름>
# 예: adb shell pm path com.example.myapp

# 3. APK 추출
adb pull <APK경로> ./
# 예: adb pull /data/app/~~abc123==/com.example.myapp-xyz==/base.apk ./myapp.apk
```

### 2. 명령어

#### `generate` - 프로젝트 생성 (권장)

APK를 분석하고 완전한 Gradle 프로젝트를 생성합니다.

```bash
# 기본 사용법 (PARSE 0만)
./gradlew run --args="generate <APK파일> -o <출력디렉토리>"

# AI 난독화 복호화 활성화
./gradlew run --args="generate <APK파일> -o <출력디렉토리> --ai"

# 예시
./gradlew run --args="generate ./sample.apk -o ./output/sample_project"

# AI + 한글 번역
./gradlew run --args="generate ./sample.apk -o ./output --ai --korean"
```

**옵션**:
- `-o, --output <DIR>`: 출력 디렉토리 (기본: `./<앱이름>_project`)
- `--ai`: AI 난독화 복호화 활성화
- `--ai-client <ollama|claude|codex>`: AI 클라이언트 선택 (기본: ollama)
- `--model <MODEL>`: AI 모델명 (기본: deepseek-coder:6.7b)
- `--korean`: 한글 번역 활성화
- `--translation-model <MODEL>`: 번역 모델 (기본: qwen2.5)
- `--batch-size <N>`: 배치 크기 (기본: 10)
- `-v, --verbose`: 상세 로그 출력

**출력 구조**:
```
output/sample_project/
├── app/
│   ├── build.gradle          # 자동 생성된 빌드 설정
│   ├── proguard-rules.pro
│   └── src/
│       └── main/
│           ├── AndroidManifest.xml
│           ├── java/         # 디컴파일된 Java 소스
│           ├── res/          # 리소스 파일
│           └── assets/       # 에셋 파일
├── .apk2project/
│   ├── status.json           # 진행 상황 (Dashboard용)
│   ├── dashboard.html        # 레거시 대시보드
│   └── cache/                # AI 캐시
├── build.gradle              # 루트 빌드 파일
├── settings.gradle
├── gradle.properties
├── gradle/wrapper/           # Gradle Wrapper
└── README.md                 # 생성된 프로젝트 정보
```

#### `analyze` - 의존성 분석

APK의 사용된 라이브러리를 분석합니다.

```bash
# 기본 사용법
./gradlew run --args="analyze <APK파일>"

# 모든 의존성 표시 (낮은 신뢰도 포함)
./gradlew run --args="analyze ./sample.apk --all"

# 상세 로그
./gradlew run --args="analyze ./sample.apk -v"
```

**출력 예시**:
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

#### `decompile` - 디컴파일만 수행

APK를 디컴파일하여 소스 코드만 추출합니다.

```bash
./gradlew run --args="decompile <APK파일> -o <출력디렉토리>"

# 예시
./gradlew run --args="decompile ./sample.apk -o ./decompiled"
```

#### `verify` - 프로젝트 검증

생성된 프로젝트의 빌드 가능성을 검증합니다.

```bash
./gradlew run --args="verify <프로젝트디렉토리>"

# 예시
./gradlew run --args="verify ./output/sample_project"
```

**검증 항목**:
- 프로젝트 구조 확인
- build.gradle 파일 존재 여부
- 소스 파일 존재 여부
- 리소스 파일 존재 여부

## 🎛️ Dashboard 사용법

실시간 파이프라인 진행 상황을 모니터링할 수 있습니다.

### 시작 방법

```bash
# Terminal 1: 파이프라인 실행
./gradlew run --args="generate ./sample.apk -o ./output --ai"

# Terminal 2: Dashboard 시작
cd dashboard
./start-dashboard.sh
# 또는
PORT=3000 npm run dev
```

### Dashboard 접속

브라우저에서 **http://localhost:3000** 접속

### Dashboard 기능

1. **파이프라인 시각화**
   - PARSE 0: APK → Gradle 변환 단계
   - Phase 1-5: AI Deobfuscation 단계
   - 실시간 진행률 표시

2. **시스템 모니터링**
   - CPU 사용률
   - 메모리 사용량
   - GPU 사용률 (Mac Apple Silicon)

3. **진행 상황**
   - 처리된 파일/메서드 수
   - 성공/실패 통계
   - 예상 완료 시간

4. **최근 변경사항**
   - 최근 rename 목록
   - AI 요청 로그

### Dashboard 자동 포트 선택

`start-dashboard.sh`는 `output.properties`에서 포트를 읽고, 해당 포트가 사용 중이면 자동으로 다음 포트를 시도:

```bash
# output.properties에 dashboard.port=3000 설정
cd dashboard
./start-dashboard.sh
# Port 3000이 사용 중이면 3001, 3002... 자동 시도
```

## 🤖 AI Deobfuscation 상세

### 파이프라인 단계

**PARSE 0: APK → Gradle Project**
1. AndroidManifest 파싱
2. APK 디컴파일 (JADX)
3. 리소스 추출 (APKTool)
4. 의존성 분석
5. Gradle 프로젝트 생성

**Phase 1: File Parsing**
- 병렬 파일 파싱 (20 workers)
- Java 파일에서 메서드/클래스 추출

**Phase 2: Call Graph 구축**
- 메서드 호출 관계 분석
- Reverse Call Graph 생성

**Phase 3: Method Deobfuscation**
- Leaf method 우선 처리
- AI 기반 이름 추론
- 배치 처리 (병렬)

**Phase 4: Class Deobfuscation**
- 클래스 이름 복호화
- 패키지 구조 재구성

**Phase 5: Rename 적용**
- 소스 코드에 rename 적용
- 백업 생성

### AI 모델 추천

| 모델 | 크기 | 속도 | 품질 | 용도 |
|------|------|------|------|------|
| **deepseek-coder:6.7b** | 4.5GB | ⚡⚡⚡ | ⭐⭐⭐ | **추천** - 균형 |
| deepseek-coder:33b | 20GB | ⚡ | ⭐⭐⭐⭐⭐ | 최고 품질 |
| qwen2.5:7b | 4.5GB | ⚡⚡⚡ | ⭐⭐⭐ | 일반 코드 |
| qwen2.5 | 4.7GB | ⚡⚡ | ⭐⭐ | 한글 번역용 |

### 성능 최적화

```bash
# 빠른 처리 (거대 모델 + 큰 배치)
./gradlew run --args="generate ./sample.apk -o ./output --ai --model deepseek-coder:6.7b --batch-size 50"

# 최고 품질
./gradlew run --args="generate ./sample.apk -o ./output --ai --model deepseek-coder:33b --batch-size 10"

# 한글 번역
./gradlew run --args="generate ./sample.apk -o ./output --ai --korean --translation-model qwen2.5"
```

## 📊 테스트 결과 예시

### PARSE 0 (APK → Gradle)

| 항목 | 결과 |
|------|------|
| APK 크기 | ~150 MB |
| DEX 파일 수 | 10개 (Multi-DEX) |
| 디컴파일된 클래스 | ~35,000개 |
| 성공률 | 95-99% |
| 감지된 의존성 | ~50개 |
| 처리 시간 | ~3분 |

### AI Deobfuscation

| 항목 | deepseek-coder:6.7b | deepseek-coder:33b |
|------|---------------------|-------------------|
| 처리 속도 | ~500 methods/min | ~150 methods/min |
| 품질 | 좋음 | 최고 |
| 메모리 | 6GB | 20GB |
| GPU | 권장 | 필수 |

## 📦 감지 가능한 라이브러리 (54개)

### Android/Google
- AndroidX (appcompat, core, fragment, recyclerview, constraintlayout 등)
- Google Play Services (auth, location, maps)
- Firebase (analytics, messaging, crashlytics, auth)
- Material Components

### 네트워킹
- OkHttp, Retrofit
- Volley

### 이미지
- Glide, Picasso
- Coil

### 데이터/직렬화
- Gson, Moshi
- Room, Realm
- DataStore

### 비동기/반응형
- RxJava, RxAndroid
- Kotlin Coroutines

### DI
- Dagger, Hilt

### UI
- Lottie
- ViewPager2

### 기타
- ZXing (QR/바코드)
- Apache Commons
- Timber (로깅)

## 🔧 트러블슈팅

### JADX를 찾을 수 없음

```
Error: JADX not found. Install with: brew install jadx
```

**해결**: JADX가 PATH에 있는지 확인
```bash
which jadx
# 결과가 없으면 설치 필요
```

### APKTool을 찾을 수 없음

```
Warning: Could not decode AndroidManifest.xml
```

**해결**: APKTool 설치 확인
```bash
which apktool
apktool --version
```

### Dashboard가 IDLE 상태

**현상**: Dashboard가 "Waiting for pipeline to start..." 표시

**해결**:
1. `output.properties`가 프로젝트 루트에 있는지 확인
2. `output.dir` 설정이 실제 출력 경로와 일치하는지 확인
3. Dashboard 재시작: `cd dashboard && ./start-dashboard.sh`

### Ollama 연결 실패

```
Error: Failed to connect to Ollama at http://localhost:11434
```

**해결**:
```bash
# Ollama 서버 확인
curl http://localhost:11434/api/tags

# Ollama 시작
ollama serve

# 모델 확인
ollama list
```

### 메모리 부족

대용량 APK 처리 시 메모리 부족 오류가 발생할 수 있습니다.

```bash
# Gradle JVM 메모리 증가
./gradlew run -Dorg.gradle.jvmargs="-Xmx8g" --args="generate large.apk -o output"

# 더 작은 모델 사용
./gradlew run --args="generate large.apk -o output --ai --model deepseek-coder:6.7b"
```

### Dashboard 포트 충돌

```
Error: Port 3000 already in use
```

**해결**:
1. 다른 포트 사용: `cd dashboard && PORT=3001 npm run dev`
2. 또는 `output.properties`에서 `dashboard.port` 변경
3. 자동 선택 스크립트는 이미 다음 포트를 시도함

### 빌드 오류 해결

생성된 프로젝트 빌드 시 발생할 수 있는 일반적인 오류:

1. **Duplicate class 오류**
   - build.gradle에서 중복된 의존성 제거

2. **Missing resource 오류**
   - 난독화로 인해 일부 리소스 참조가 깨질 수 있음
   - R 클래스 참조 수동 수정 필요

3. **API 호환성 오류**
   - minSdk/targetSdk 버전 조정

## 🚫 제한사항

- **난독화된 코드**: ProGuard/R8로 난독화된 코드는 복원되지만 가독성이 떨어짐
- **네이티브 라이브러리**: .so 파일은 복사만 되고 디컴파일되지 않음
- **동적 로딩**: 리플렉션이나 동적 클래스 로딩은 완벽히 복원되지 않을 수 있음
- **정확한 버전**: 라이브러리 버전은 추정치이며 정확하지 않을 수 있음
- **AI 정확도**: AI가 추론한 이름이 항상 정확하지 않을 수 있음

## 📄 라이선스

MIT License

## ⚖️ 법적 고지 및 책임 부인

**중요**: 이 도구는 오직 교육 및 보안 연구 목적으로 제작되었습니다.

### 사용자는 다음 사항에 동의해야 합니다:

1. **합법적인 목적으로만 사용**
   - APK 리버스 엔지니어링에 대한 법적 권한이 있는 경우에만 사용
   - 자신이 개발한 앱 또는 명시적인 허가를 받은 앱에만 적용
   - 타인의 지식 재산권을 침해하는 용도로 사용 금지

2. **책임 부인**
   - 이 도구의 사용으로 발생하는 모든 법적 문제에 대해 개발자는 책임지지 않음
   - 앱 리버스 엔지니어링이 귀하의 거주 국가의 법률을 위반할 수 있음
   - 상업적 용도로 사용 시 발생하는 손해에 대해 보증하지 않음

3. **보안 연구 목적**
   - 취약성 분석, 보안 연구 등 윤리적인 목적에만 사용
   - 연구 결과를 공개할 때는 책임감 있는 방식으로 공개

4. **사용자 책임**
   - 이 도구를 사용함으로써 발생하는 모든 결과에 대한 책임은 전적으로 사용자에게 있음
   - 불법적인 목적으로 사용 시 발생하는 모든 법적 문제는 사용자가 책임짐

**요약**: 이 도구를 사용하여 발생하는 모든 문제에 대해 개발자는 일절 책임지지 않으며, 모든 책임은 사용자 본인에게 있습니다.

---

*Generated by [APK2Project](https://github.com/devload/apk2project)*
