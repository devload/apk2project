# APK2Project

Android APK 파일을 분석하여 빌드 가능한 Gradle 프로젝트로 복원하는 CLI 도구

## 주요 기능

- **APK 디컴파일**: DEX 파일을 Java 소스코드로 변환
- **리소스 추출**: 레이아웃, 이미지, 문자열 등 모든 리소스 복원
- **의존성 분석**: 사용된 라이브러리 자동 감지 (54개 라이브러리 시그니처 내장)
- **프로젝트 생성**: 바로 빌드 가능한 Gradle 프로젝트 구조 생성
- **빌드 검증**: 생성된 프로젝트의 빌드 가능성 확인

## 시스템 요구사항

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

### 설치 확인

```bash
# JADX 확인
jadx --version

# APKTool 확인
apktool --version
```

## 빌드 방법

```bash
# 프로젝트 클론
git clone https://github.com/devload/apk2project.git
cd apk2project

# 빌드
./gradlew build

# 실행 가능한 JAR 생성 (선택사항)
./gradlew shadowJar
```

## 사용 방법

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
# 기본 사용법
./gradlew run --args="generate <APK파일> -o <출력디렉토리>"

# 예시
./gradlew run --args="generate ./sample.apk -o ./output/sample_project"

# 상세 로그 출력
./gradlew run --args="generate ./sample.apk -o ./output/sample_project -v"
```

**옵션**:
- `-o, --output <DIR>`: 출력 디렉토리 (기본: `./<앱이름>_project`)
- `-v, --verbose`: 상세 로그 출력
- `--skip-sources`: 소스 파일 복사 생략 (빠른 테스트용)

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

## 테스트 결과 예시

| 항목 | 결과 |
|------|------|
| APK 크기 | ~150 MB |
| DEX 파일 수 | 10개 (Multi-DEX) |
| 디컴파일된 클래스 | ~35,000개 |
| 성공률 | 95-99% |
| 감지된 의존성 | ~50개 |
| 처리 시간 | ~3분 |

## 감지 가능한 라이브러리 (54개)

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

## 트러블슈팅

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

### 메모리 부족

대용량 APK 처리 시 메모리 부족 오류가 발생할 수 있습니다.

```bash
# Gradle JVM 메모리 증가
./gradlew run -Dorg.gradle.jvmargs="-Xmx4g" --args="generate large.apk -o output"
```

### 빌드 오류 해결

생성된 프로젝트 빌드 시 발생할 수 있는 일반적인 오류:

1. **Duplicate class 오류**
   - build.gradle에서 중복된 의존성 제거

2. **Missing resource 오류**
   - 난독화로 인해 일부 리소스 참조가 깨질 수 있음
   - R 클래스 참조 수동 수정 필요

3. **API 호환성 오류**
   - minSdk/targetSdk 버전 조정

## 제한사항

- **난독화된 코드**: ProGuard/R8로 난독화된 코드는 복원되지만 가독성이 떨어짐
- **네이티브 라이브러리**: .so 파일은 복사만 되고 디컴파일되지 않음
- **동적 로딩**: 리플렉션이나 동적 클래스 로딩은 완벽히 복원되지 않을 수 있음
- **정확한 버전**: 라이브러리 버전은 추정치이며 정확하지 않을 수 있음

## 라이선스

MIT License

## 법적 고지

이 도구는 교육 및 보안 연구 목적으로 제작되었습니다. APK 리버스 엔지니어링에 대한 법적 권한이 있는 경우에만 사용하세요.

---

*Generated by [APK2Project](https://github.com/devload/apk2project)*
