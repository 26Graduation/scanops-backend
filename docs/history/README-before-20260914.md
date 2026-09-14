# ScanOps

PR을 올리면 자동으로 보안 취약점을 분석해주는 GitHub App

[![GitHub App](https://img.shields.io/badge/GitHub%20App-설치하기-238636?logo=github)](https://github.com/apps/scanops-security-scanner)

---

## GitHub App으로 PR 자동 보안 분석

ScanOps GitHub App을 내 레포에 설치하면, PR을 올릴 때마다 자동으로 보안 취약점 검사가 시작됩니다. XSS, 코드 인젝션, SSRF 같은 취약점이 발견되면 해당 코드 줄에 바로 댓글이 달리고, 뭐가 문제인지 어떻게 고치면 되는지 한국어로 알려줍니다.

**[→ GitHub App 설치하기](https://github.com/apps/scanops-security-scanner)** — Install 누르고 레포 선택하면 끝. 그 다음 PR 올리면 자동으로 분석이 시작됩니다.

---

## 프로젝트 구조

| 서비스 | 설명 | 링크 |
|--------|------|------|
| `scanops-backend` | Spring Boot 백엔드 (Webhook 수신·분석 오케스트레이션) | [26Graduation/scanops-backend](https://github.com/26Graduation/scanops-backend) |
| `scanops-model` | 보안 분석 AI 모델 서버 (QLoRA + RAG) | [26Graduation/scanops-model](https://github.com/26Graduation/scanops-model) |
| `scanops-frontend` | 대시보드 UI | [scanops-frontend.vercel.app](https://scanops-frontend.vercel.app) |
| `scanops-infra` | ZAP + 인프라 구성 | [26Graduation/scanops-infra](https://github.com/26Graduation/scanops-infra) |

---

## scanops-backend

ScanOps 백엔드 — Spring Boot 3.2 + JPA + WebFlux

---

## 기술 스택

| 기술 | 버전 | 용도 |
|------|------|------|
| Java | 17 | 런타임 |
| Spring Boot | 3.2.5 | 애플리케이션 프레임워크 |
| Spring Data JPA | - | PostgreSQL ORM |
| Spring WebFlux (WebClient) | - | ZAP / AI API 비동기 HTTP 호출 |
| Spring Security | - | CORS 설정 |
| PostgreSQL | 15 | 메인 데이터베이스 |
| Lombok | - | 보일러플레이트 제거 |
| Gradle | 8.x | 빌드 툴 |

---

## 패키지 구조

```
com.scanops/
├── scan/               ← 스캔 생성·조회, ZAP 연동, 파이프라인 실행
│   ├── ScanController.java       REST API 엔드포인트
│   ├── ScanService.java          비즈니스 로직 (스캔 생성/조회)
│   ├── ScanPipelineRunner.java   비동기 스캔 파이프라인 (@Async)
│   ├── ScanJob.java              DB 엔티티 (scan_jobs 테이블)
│   ├── ScanRequest.java          요청 DTO
│   ├── ScanStatus.java           PENDING / RUNNING / DONE / FAILED
│   ├── ZapClient.java            ZAP REST API 호출 클라이언트
│   ├── ZapAlert.java             ZAP 알럿 파싱 DTO
│   └── ScanJobRepository.java    JPA Repository
│
├── vulnerability/      ← 취약점 엔티티, CVSS 계산
│   ├── VulnerabilityController.java   AI 메타 수동 재생성 API
│   ├── VulnerabilityService.java      jobId 기반 취약점 조회
│   ├── VulnerabilityRepository.java   JPA Repository
│   ├── Vulnerability.java             DB 엔티티 (vulnerabilities 테이블)
│   ├── CvssCalculator.java            CVSS 점수·벡터 계산
│   ├── RiskLevel.java                 LOW / MEDIUM / HIGH / INFORMATIONAL
│   └── VulnMetaMigrationService.java  서버 시작 시 AI 메타 누락분 재시도
│
├── ai/                 ← AI 분석 라우터 + 모델별 구현체
│   ├── AiAnalyzer.java           인터페이스 (analyze / generateMeta)
│   ├── AiModel.java              GPT / CLAUDE / GEMINI / CUSTOM
│   ├── AiRouter.java             우선순위 기반 폴백 라우터
│   ├── GptAnalyzer.java          OpenAI GPT-4o-mini 구현체
│   ├── ClaudeAnalyzer.java       Anthropic Claude claude-sonnet-4-6 구현체
│   └── VulnMetaResult.java       record(summary, description, solution)
│
├── report/             ← 스캔 리포트 조회
│   ├── ReportController.java
│   ├── ReportService.java
│   └── ReportResponse.java
│
├── verify/             ← 도메인 소유권 인증
│   ├── DomainVerifyService.java
│   ├── DomainVerification.java   DB 엔티티 (domain_verifications 테이블)
│   └── DomainVerificationRepository.java
│
└── config/
    ├── AsyncConfig.java     스레드풀 "scanExecutor" 설정
    ├── CorsConfig.java      CORS 허용 Origin 설정
    └── SecurityConfig.java  Spring Security 기본 설정 (CSRF 비활성화)
```

---

## 데이터베이스 구조

### scan_jobs 테이블

```sql
CREATE TABLE scan_jobs (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    target_url  VARCHAR NOT NULL,
    status      VARCHAR NOT NULL,   -- PENDING | RUNNING | DONE | FAILED
    owner_email VARCHAR,
    verified    BOOLEAN NOT NULL DEFAULT false,
    created_at  TIMESTAMP,
    finished_at TIMESTAMP
);
```

| 컬럼 | 타입 | 설명 |
|------|------|------|
| `id` | UUID | 스캔 식별자 (자동 생성) |
| `target_url` | VARCHAR | 스캔 대상 URL |
| `status` | VARCHAR | 현재 스캔 상태 |
| `owner_email` | VARCHAR | 스캔 요청자 이메일 |
| `verified` | BOOLEAN | 도메인 소유권 인증 여부 |
| `created_at` | TIMESTAMP | 스캔 생성 시각 (자동) |
| `finished_at` | TIMESTAMP | 스캔 완료 시각 |

### vulnerabilities 테이블

```sql
CREATE TABLE vulnerabilities (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    job_id      UUID NOT NULL,          -- scan_jobs.id 참조
    vuln_type   VARCHAR,                -- ZAP alert 이름 (e.g. "SQL Injection")
    url         VARCHAR,                -- 취약점이 발견된 URL
    parameter   VARCHAR,                -- 취약한 파라미터명
    risk_level  VARCHAR,                -- LOW | MEDIUM | HIGH | INFORMATIONAL
    cvss_score  DOUBLE PRECISION,       -- CVSS 3.1 점수 (0.0 ~ 10.0)
    cvss_vector VARCHAR,                -- e.g. "CVSS:3.1/AV:N/AC:L/PR:N/..."
    summary     TEXT,                   -- AI 생성 한 줄 요약
    description TEXT,                   -- AI 생성 취약점 설명
    solution    TEXT,                   -- AI 생성 해결 방법
    ai_analysis TEXT,                   -- AI 심층 분석 (선택)
    ai_model    VARCHAR,                -- 분석에 사용된 AI 모델
    created_at  TIMESTAMP
);
```

| 컬럼 | 설명 |
|------|------|
| `job_id` | 어떤 스캔에서 발견된 취약점인지 |
| `vuln_type` | ZAP이 탐지한 취약점 유형명 |
| `cvss_score` | `CvssCalculator`가 계산한 CVSS 점수 |
| `cvss_vector` | CVSS 3.1 벡터 문자열 |
| `summary/description/solution` | AI가 생성한 한국어 메타 정보 |
| `ai_model` | 실제 응답한 AI 모델 (GPT/CLAUDE/GEMINI/CUSTOM) |

### domain_verifications 테이블

```sql
CREATE TABLE domain_verifications (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    domain       VARCHAR UNIQUE NOT NULL,
    verify_token VARCHAR NOT NULL,
    verified     BOOLEAN NOT NULL DEFAULT false
);
```

---

## API 명세

### 스캔 (Scan)

#### `POST /api/scans` — 스캔 생성

```json
// Request Body
{
  "targetUrl": "http://localhost:4280",
  "ownerEmail": "admin@example.com"
}
```

```json
// Response 200
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "targetUrl": "http://localhost:4280",
  "status": "PENDING",
  "ownerEmail": "admin@example.com",
  "verified": false,
  "createdAt": "2026-04-30T10:00:00",
  "finishedAt": null
}
```

- `verified` 필드: `domain_verifications` 테이블에 해당 도메인이 인증된 경우 `true`
- 응답 직후 백그라운드에서 `ScanPipelineRunner.run()` 비동기 실행 시작

#### `GET /api/scans/{id}` — 스캔 상태 조회

```json
// Response 200
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "targetUrl": "http://localhost:4280",
  "status": "RUNNING",
  "ownerEmail": "admin@example.com",
  "verified": false,
  "createdAt": "2026-04-30T10:00:00",
  "finishedAt": null
}
```

`status` 값: `PENDING` → `RUNNING` → `DONE` 또는 `FAILED`

#### `GET /api/scans/{id}/vulnerabilities` — 스캔 결과 취약점 목록

```json
// Response 200
[
  {
    "id": "vuln-uuid-1",
    "jobId": "550e8400-e29b-41d4-a716-446655440000",
    "vulnType": "SQL Injection",
    "url": "http://localhost:4280/vulnerabilities/sqli/",
    "parameter": "id",
    "riskLevel": "HIGH",
    "cvssScore": 9.8,
    "cvssVector": "CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:H/I:H/A:H",
    "summary": "SQL 인젝션: 사용자 입력이 DB 쿼리에 직접 삽입되는 취약점",
    "description": "입력값이 필터링 없이 SQL 쿼리에 연결되어...",
    "solution": "PreparedStatement 사용: `SELECT * FROM users WHERE id = ?`",
    "aiAnalysis": null,
    "aiModel": "GPT",
    "createdAt": "2026-04-30T10:15:00"
  }
]
```

#### `GET /api/scans` — 전체 스캔 목록

```json
// Response 200
[
  { "id": "...", "targetUrl": "...", "status": "DONE", ... },
  { "id": "...", "targetUrl": "...", "status": "RUNNING", ... }
]
```

---

### 리포트 (Report)

#### `GET /api/reports/{jobId}` — 스캔 리포트 조회

```json
// Response 200
{
  "targetUrl": "550e8400-e29b-41d4-a716-446655440000",
  "maxCvssScore": 9.8,
  "vulnerabilities": [
    {
      "id": "...",
      "vulnType": "SQL Injection",
      "riskLevel": "HIGH",
      "cvssScore": 9.8,
      ...
    },
    {
      "id": "...",
      "vulnType": "Cross Site Scripting (Reflected)",
      "riskLevel": "MEDIUM",
      "cvssScore": 6.1,
      ...
    }
  ]
}
```

- `maxCvssScore`: 해당 스캔의 취약점 중 가장 높은 CVSS 점수
- `vulnerabilities`: 해당 jobId에 연결된 전체 취약점 목록

---

### 취약점 (Vulnerability)

#### `POST /api/vulnerabilities/{id}/meta` — AI 메타 수동 재생성

description이 비어있는 취약점에 대해 AI를 재호출하여 메타 정보를 생성합니다.

```json
// Response 200
{
  "summary": "SQL 인젝션: 사용자 입력이 DB 쿼리에 직접 삽입되는 취약점",
  "description": "이 취약점은 사용자 입력이 적절한 이스케이프 없이...",
  "solution": "PreparedStatement를 사용하세요:\n```java\nPreparedStatement ps = conn.prepareStatement(...);\n```"
}
```

---

## ZAP 취약점 탐색 파이프라인

스캔이 생성되면 `ScanPipelineRunner`가 비동기(`@Async("scanExecutor")`)로 실행됩니다.

### 파이프라인 단계

```
1. accessUrl      URL을 ZAP 내부 사이트 트리에 시딩
       ↓
2. Spider 스캔    대상 사이트를 크롤링하여 모든 URL 수집
       ↓ (5초 간격 폴링, progress = 100% 될 때까지 대기)
3. Active 스캔   수집된 URL에 실제 공격 페이로드 주입 시도
       ↓ (5초 간격 폴링, progress = 100% 될 때까지 대기)
4. getAlerts     탐지된 취약점 알럿 목록 수집
       ↓
5. AI 메타 생성  각 알럿에 대해 AiRouter로 한국어 설명 생성
       ↓
6. DB 저장       vulnerabilities 테이블에 저장
```

### ZAP REST API 호출 상세

백엔드는 ZAP의 REST API를 Spring WebClient로 호출합니다.

| 단계 | ZAP 엔드포인트 | 설명 |
|------|---------------|------|
| URL 시딩 | `GET /JSON/core/action/accessUrl/?url={url}&followRedirects=true` | ZAP이 대상 URL에 직접 접근하여 사이트 트리 초기화 |
| Spider 시작 | `GET /JSON/spider/action/scan/?url={url}&recurse=true` | 크롤러 시작, scan ID 반환 |
| Spider 진행률 | `GET /JSON/spider/view/status/?scanId={id}` | `{"status": "75"}` — 0~100 |
| Active 스캔 시작 | `GET /JSON/ascan/action/scan/?url={url}&recurse=true` | 능동 스캔 시작, scan ID 반환 |
| Active 스캔 진행률 | `GET /JSON/ascan/view/status/?scanId={id}` | `{"status": "42"}` — 0~100 |
| 알럿 수집 | `GET /JSON/core/view/alerts/?baseurl={url}` | 탐지된 취약점 전체 반환 |

### ZAP 알럿 응답 구조

```json
{
  "alerts": [
    {
      "alert": "SQL Injection",
      "risk": "High",
      "url": "http://target/page?id=1",
      "param": "id",
      "description": "SQL injection may be possible...",
      "solution": "Use parameterized queries..."
    },
    {
      "alert": "Cross Site Scripting (Reflected)",
      "risk": "Medium",
      "url": "http://target/search?q=test",
      "param": "q",
      "description": "Cross-site Scripting (XSS) is...",
      "solution": "Encode user-supplied data before rendering..."
    }
  ]
}
```

`risk` 값은 `High` / `Medium` / `Low` / `Informational` → `RiskLevel` enum으로 변환

---

## CVSS 점수 계산

`CvssCalculator`가 `RiskLevel`과 취약점 유형명을 기반으로 점수를 결정합니다.

| 취약점 유형 | CVSS 점수 | 벡터 |
|------------|----------|------|
| SQL Injection | 9.8 | `AV:N/AC:L/PR:N/UI:N/S:U/C:H/I:H/A:H` |
| Command Injection | 9.8 | `AV:N/AC:L/PR:N/UI:N/S:U/C:H/I:H/A:H` |
| SSRF | 8.8 | HIGH 기본값 |
| XXE | 8.2 | HIGH 기본값 |
| Path Traversal | 7.5 | `AV:N/AC:L/PR:N/UI:N/S:U/C:H/I:N/A:N` |
| XSS (Stored) | 6.8 | `AV:N/AC:L/PR:N/UI:R/S:C/C:L/I:L/A:N` |
| XSS (Reflected) | 6.1 | `AV:N/AC:L/PR:N/UI:R/S:C/C:L/I:L/A:N` |
| CSRF | 6.5 | `AV:N/AC:L/PR:N/UI:R/S:U/C:H/I:N/A:N` |
| Cookie No HttpOnly | 3.7 | LOW 기본값 |
| Missing Headers | 2.1 | LOW 기본값 |

---

## AI 분석 라우팅

`AiRouter`는 우선순위 순서대로 폴백하며 가용한 모델을 선택합니다.

```
GPT (OpenAI gpt-4o-mini)
  → 실패 시 CLAUDE (Anthropic claude-sonnet-4-6)
    → 실패 시 GEMINI
      → 실패 시 CUSTOM
```

### GPT 호출 (OpenAI)

```
POST https://api.openai.com/v1/chat/completions
Authorization: Bearer {OPENAI_API_KEY}
Content-Type: application/json

{
  "model": "gpt-4o-mini",
  "messages": [
    {
      "role": "user",
      "content": "웹 취약점 유형 \"SQL Injection\"에 대해 아래 JSON 형식으로만 응답하세요...\n{\"summary\":\"...\",\"description\":\"...\",\"solution\":\"...\"}"
    }
  ],
  "response_format": { "type": "json_object" }
}

// Response
{
  "choices": [
    {
      "message": {
        "content": "{\"summary\":\"...\",\"description\":\"...\",\"solution\":\"...\"}"
      }
    }
  ]
}
```

### Claude 호출 (Anthropic)

```
POST https://api.anthropic.com/v1/messages
x-api-key: {CLAUDE_API_KEY}
anthropic-version: 2023-06-01
Content-Type: application/json

{
  "model": "claude-sonnet-4-6",
  "max_tokens": 1024,
  "messages": [
    {
      "role": "user",
      "content": "웹 취약점 유형 \"SQL Injection\"에 대해 아래 JSON 형식으로만 응답하세요...\n{\"summary\":\"...\",\"description\":\"...\",\"solution\":\"...\"}"
    }
  ]
}

// Response
{
  "content": [
    {
      "type": "text",
      "text": "{\"summary\":\"...\",\"description\":\"...\",\"solution\":\"...\"}"
    }
  ]
}
```

### AI가 생성하는 메타 JSON 구조

```json
{
  "summary": "SQL 인젝션: 사용자 입력이 필터링 없이 DB 쿼리에 삽입되는 취약점",
  "description": "이 취약점은 사용자 입력값이 적절한 이스케이프 처리 없이 SQL 쿼리에 직접 연결될 때 발생합니다. 공격자는 악의적인 SQL 구문을 삽입하여 데이터베이스의 민감한 정보를 열람하거나 데이터를 조작할 수 있습니다.",
  "solution": "PreparedStatement를 사용하여 파라미터를 바인딩하세요:\n```java\nPreparedStatement ps = conn.prepareStatement(\"SELECT * FROM users WHERE id = ?\");\nps.setString(1, userInput);\n```"
}
```

- `INFORMATIONAL` 등급 취약점은 AI 호출 생략 (ZAP 원문 그대로 저장)
- AI 호출 실패 시 `description = null`로 저장, 서버 시작 시 `VulnMetaMigrationService`가 재시도

---

## 로컬 실행

```bash
# 1. PostgreSQL + ZAP 실행
cd ../scanops-infra && docker compose up postgres zap -d

# 2. 환경변수 설정
export OPENAI_API_KEY=sk-...
export ZAP_HOST=http://localhost:8090
export ZAP_API_KEY=

# 3. 실행
./gradlew bootRun
```

## Railway 배포

1. [railway.app](https://railway.app) → New Project
2. **Add PostgreSQL** 플러그인 추가
3. **New Service → GitHub Repo** → `scanops-backend` 선택
4. Variables 탭 환경변수 설정:

```
JDBC_DATABASE_URL      = ${{Postgres.JDBC_DATABASE_URL}}
JDBC_DATABASE_USERNAME = ${{Postgres.PGUSER}}
JDBC_DATABASE_PASSWORD = ${{Postgres.PGPASSWORD}}
ZAP_HOST               = https://your-zap-service.up.railway.app
ZAP_API_KEY            = (비워두기)
OPENAI_API_KEY         = sk-...
CLAUDE_API_KEY         = sk-ant-...
CORS_ALLOWED_ORIGINS   = https://your-app.vercel.app
```

## 환경변수 전체 목록

| 변수 | 설명 | 필수 |
|------|------|------|
| `JDBC_DATABASE_URL` | PostgreSQL JDBC URL | ✅ |
| `JDBC_DATABASE_USERNAME` | DB 사용자 | ✅ |
| `JDBC_DATABASE_PASSWORD` | DB 비밀번호 | ✅ |
| `ZAP_HOST` | ZAP 서비스 URL | ✅ |
| `ZAP_API_KEY` | ZAP API 키 (disablekey 시 공백) | |
| `OPENAI_API_KEY` | OpenAI API 키 | |
| `CLAUDE_API_KEY` | Claude API 키 | |
| `GEMINI_API_KEY` | Gemini API 키 | |
| `CORS_ALLOWED_ORIGINS` | 허용 Origin 목록 (쉼표 구분) | ✅ |
| `PORT` | 서버 포트 (Railway 자동 주입) | |
