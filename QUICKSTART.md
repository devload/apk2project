# APK2Project 빠른 시작 가이드

## 1분 안에 시작하기

### Step 1: 도구 설치 (macOS)

```bash
brew install jadx apktool
```

### Step 2: 프로젝트 빌드

```bash
cd apk2project
./gradlew build
```

### Step 3: APK → Gradle 프로젝트 변환

```bash
./gradlew run --args="generate /path/to/your.apk -o ./output"
```

끝! `./output` 폴더에 Android Studio에서 열 수 있는 프로젝트가 생성됩니다.

---

## 자주 사용하는 명령어

### APK에서 완전한 프로젝트 생성

```bash
./gradlew run --args="generate app.apk -o ./my_project"
```

### 의존성만 분석

```bash
./gradlew run --args="analyze app.apk"
```

### 소스 코드만 추출

```bash
./gradlew run --args="decompile app.apk -o ./sources"
```

### 생성된 프로젝트 검증

```bash
./gradlew run --args="verify ./my_project"
```

---

## 안드로이드 기기에서 APK 추출

```bash
# 1. 패키지 찾기
adb shell pm list packages | grep 앱이름

# 2. APK 경로 확인
adb shell pm path com.example.app

# 3. APK 다운로드
adb pull /data/app/.../base.apk ./app.apk
```

---

## 문제 해결

| 문제 | 해결 |
|------|------|
| `JADX not found` | `brew install jadx` |
| `Could not decode manifest` | `brew install apktool` |
| 메모리 부족 | `./gradlew run -Dorg.gradle.jvmargs="-Xmx4g" --args="..."` |

---

## 상세 문서

- [README.md](README.md) - 전체 사용 가이드
- [CLAUDE.md](CLAUDE.md) - 개발자 가이드
