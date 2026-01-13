# Obfuscated Sample Project

AI 기반 난독화 복호화 테스트를 위한 샘플 프로젝트입니다.

## 프로젝트 구조

```
obfuscated-sample/
├── src/main/java/com/test/obf/
│   ├── a.java          # MainActivity (Activity)
│   ├── b.java          # DataManager (Singleton)
│   ├── c.java          # NetworkClient (HTTP)
│   ├── d.java          # Utils (정적 유틸리티)
│   ├── e.java          # AuthService (인증)
│   ├── f.java          # DatabaseHelper (SQLite)
│   ├── g.java          # PreferenceManager (설정)
│   ├── h.java          # ImageLoader (이미지 캐싱)
│   ├── i.java          # Validator (입력 검증)
│   └── j.java          # ApiService (REST API)
├── build.gradle.kts
└── settings.gradle.kts
```

## 클래스 설명

### 1. a.java - MainActivity
- **난독화된 이름**: `a`
- **실제 이름**: `MainActivity`
- **역할**: Android Activity, UI 표시
- **주요 메서드**:
  - `onCreate()`: 앱 초기화
  - `a(userId, userName)`: 사용자 정보 표시
  - `h()`: 로그인 화면 표시
  - `i()`: 활동 로깅

### 2. b.java - DataManager
- **난독화된 이름**: `b`
- **실제 이름**: `DataManager`
- **역할**: 싱글톤 데이터 매니저
- **주요 메서드**:
  - `b()`: 싱글톤 인스턴스 가져오기
  - `d()`: 사용자 데이터 조회
  - `a(key, value)`: 데이터 저장
  - `c(timestamp, action)`: 이벤트 로깅

### 3. c.java - NetworkClient
- **난독화된 이름**: `c`
- **실제 이름**: `NetworkClient`
- **역할**: HTTP 클라이언트
- **주요 메서드**:
  - `a(path, callback)`: GET 요청
  - `c(path, body, callback)`: POST 요청
  - `b(path, method)`: 요청 실행
  - `d()`: 연결 풀 종료

### 4. d.java - Utils
- **난독화된 이름**: `d`
- **실제 이름**: `Utils`
- **역할**: 정적 유틸리티 메서드
- **주요 메서드**:
  - `a(input)`: MD5 해시
  - `b(input)`: Base64 인코딩
  - `c(input)`: Base64 디코딩
  - `d(email)`: 이메일 검증
  - `f(text, maxLength)`: 문자열 자르기

### 5. e.java - AuthService
- **난독화된 이름**: `e`
- **실제 이름**: `AuthService`
- **역할**: 인증 서비스
- **주요 메서드**:
  - `a(username, password)`: 로그인
  - `b()`: 로그아웃
  - `c()`: 토큰 조회
  - `d()`: 인증 상태 확인

### 6. f.java - DatabaseHelper
- **난독화된 이름**: `f`
- **실제 이름**: `DatabaseHelper`
- **역할**: SQLite 데이터베이스 헬퍼
- **주요 메서드**:
  - `l()`: 데이터베이스에서 모두 조회
  - `m(key, value)`: 데이터 저장
  - `n(key)`: 값 조회
  - `o(key)`: 값 삭제
  - `p()`: 모두 삭제

### 7. g.java - PreferenceManager
- **난독화된 이름**: `g`
- **실제 이름**: `PreferenceManager`
- **역할**: 설정 관리자
- **주요 메서드**:
  - `k()`: 모든 설정 조회
  - `a(key, value)`: 설정 저장
  - `b(key)`: 설정 조회
  - `f()`: 설정 초기화

### 8. h.java - ImageLoader
- **난독화된 이름**: `h`
- **실제 이름**: `ImageLoader`
- **역할**: 이미지 로더 및 캐싱
- **주요 메서드**:
  - `a(imageUrl, callback)`: 이미지 로드
  - `b(url)`: 캐시된 이미지 조회
  - `c(urlString)`: 이미지 다운로드
  - `d()`: 캐시 초기화

### 9. i.java - Validator
- **난독화된 이름**: `i`
- **실제 이름**: `Validator`
- **역할**: 입력 값 검증
- **주요 메서드**:
  - `a(email)`: 이메일 검증
  - `b(password)`: 비밀번호 검증
  - `c(username)`: 사용자명 검증
  - `f()`: 에러 메시지 조회

### 10. j.java - ApiService
- **난독화된 이름**: `j`
- **실제 이름**: `ApiService`
- **역할**: REST API 클라이언트
- **주요 메서드**:
  - `a(userId, callback)`: 사용자 프로필 조회
  - `b(data, callback)`: 프로필 업데이트
  - `c(callback)`: 알림 조회
  - `e(query, page, callback)`: 검색
  - `f(username, email, password, callback)`: 회원가입

## Call Graph 구조

```
a (MainActivity)
├── b (DataManager)
│   ├── f (DatabaseHelper)
│   └── g (PreferenceManager)
├── e (AuthService)
│   ├── b (DataManager)
│   ├── c (NetworkClient)
│   └── d (Utils)
└── d (Utils)

c (NetworkClient)
└── d (Utils)

e (AuthService)
├── b (DataManager)
├── c (NetworkClient)
└── d (Utils)

h (ImageLoader)
├── c (NetworkClient)
└── d (Utils)

j (ApiService)
├── c (NetworkClient)
├── e (AuthService)
└── d (Utils)

i (Validator)
└── d (Utils)
```

## 난독화 패턴

1. **클래스 이름**: 단일 문자 (`a`, `b`, `c`, ...)
2. **메서드 이름**: 단일 문자 (`a()`, `b()`, `c()`, ...)
3. **변수 이름**: 단일 문자 또는 짧은 이름 (`f`, `g`, `h`, ...)
4. **의미 없는 이름**: 로직 파악 어려움

## 테스트 방법

### 1. 프로젝트 빌드

```bash
cd test-samples/obfuscated-sample
../gradlew build
```

### 2. APK2Project로 난독화 복호화

```bash
# 1단계: 디컴파일 (필요시 APK로 변환 후)
cd ../../
./gradlew run --args="decompile test-samples/obfuscated-sample/src/main/java --output test-samples/decompiled"

# 2단계: AI 기반 난독화 복호화
./gradlew run --args="fix test-samples/decompiled/sources --ai --model deepseek-coder:6.7b --batch-size 5"

# 3단계: 대시보드로 진행 상황 모니터링
cd dashboard
npm run dev
# 브라우저: http://localhost:4000
```

### 3. 예상 결과

AI는 다음과 같이 이름을 추론해야 합니다:

| 난독화된 이름 | 추론된 이름 (예상) |
|--------------|-------------------|
| `a` | MainActivity |
| `b` | DataManager |
| `c` | NetworkClient |
| `d` | Utils |
| `e` | AuthService |
| `f` | DatabaseHelper |
| `g` | PreferenceManager |
| `h` | ImageLoader |
| `i` | Validator |
| `j` | ApiService |

| 메서드 | 추론된 이름 (예시) |
|--------|-------------------|
| `a.onCreate()` | MainActivity.onCreate() |
| `b.b()` | DataManager.getInstance() |
| `c.a()` | NetworkClient.get() |
| `d.a()` | Utils.md5() |
| `e.a()` | AuthService.login() |
| `f.l()` | DatabaseHelper.loadAll() |
| `g.k()` | PreferenceManager.loadAll() |
| `h.a()` | ImageLoader.loadImage() |
| `i.a()` | Validator.validateEmail() |
| `j.a()` | ApiService.getUserProfile() |

## 난독화 복호화 테스트 시나리오

### 시나리오 1: 기본 복호화
```bash
./gradlew run --args="fix test-samples/decompiled/sources --ai --model deepseek-coder:6.7b --batch-size 5"
```

**예상 결과:**
- 클래스 10개 복호화
- 메서드 ~50개 복호화
- 소요 시간: ~3-5분

### 시나리오 2: 한글 번역 포함
```bash
./gradlew run --args="fix test-samples/decompiled/sources --ai --korean --translation-model qwen2.5"
```

**예상 결과:**
- 복호화 + 한글 설명 추가
- 소요 시간: ~5-8분

### 시나리오 3: 대형 모델 사용 (높은 정확도)
```bash
./gradlew run --args="fix test-samples/decompiled/sources --ai --model deepseek-coder:33b"
```

**예상 결과:**
- 더 높은 정확도
- 소요 시간: ~10-15분

## 복잡도 분석

| 클래스 | 메서드 수 | Call Graph 깊이 | 복잡도 |
|--------|----------|----------------|--------|
| a | 4 | 3 | 중간 |
| b | 6 | 2 | 중간 |
| c | 5 | 2 | 중간 |
| d | 10 | 1 | 낮음 |
| e | 5 | 3 | 중간 |
| f | 8 | 1 | 낮음 |
| g | 7 | 1 | 낮음 |
| h | 6 | 2 | 중간 |
| i | 7 | 2 | 중간 |
| j | 8 | 3 | 중간 |

## 참고 사항

1. **실제 APK와 차이점**:
   - 이 샘플은 Java 소스 코드로 제공
   - 실제 환경에서는 APK → DEX → Smali → Java 변환 필요

2. **테스트 목적**:
   - AI 추론 정확도 검증
   - 파이프라인 성능 측정
   - Call Graph 분석 테스트

3. **추가 테스트 케이스**:
   - 더 복잡한 난독화 패턴
   - ProGuard/R8 난독화
   - 리플렉션 사용 코드
   - 동적 코드 로딩

## 라이선스

테스트 목적으로 제작된 샘플 코드입니다.
