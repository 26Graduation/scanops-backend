# scanops-backend

ScanOps 백엔드 — Spring Boot 3.2 + JPA + WebFlux

## 기술 스택

- Java 17 + Spring Boot 3.2.5
- Spring Data JPA (PostgreSQL)
- Spring WebFlux (WebClient — ZAP/AI API 호출)
- Spring Security (CORS 설정)
- Lombok / Gradle

## 패키지 구조

```
com.scanops/
├── scan/           ← 스캔 생성/조회, ZapClient, ScanPipelineRunner
├── vulnerability/  ← 취약점 엔티티, CVSS 계산
├── ai/             ← AiAnalyzer 인터페이스 + GPT/Claude/Gemini 구현체, AiRouter
├── report/         ← 리포트 조회
├── verify/         ← 도메인 인증
└── config/         ← CORS, Security, Async
```

## 로컬 실행

```bash
# 1. PostgreSQL 실행 (Docker)
cd ../scanops-infra && docker compose up postgres -d

# 2. 환경변수 설정
cp .env.example .env
# .env 파일 수정

# 3. 실행
./gradlew bootRun
```

## Railway 배포

1. [railway.app](https://railway.app) → New Project
2. **Add PostgreSQL** 플러그인 추가
3. **New Service → GitHub Repo** → `scanops-backend` 선택
4. Railway가 `Dockerfile` 자동 감지해서 빌드
5. 환경변수 설정 (Variables 탭):
   ```
   JDBC_DATABASE_URL    = ${{Postgres.JDBC_DATABASE_URL}}   ← Railway 자동 연결
   JDBC_DATABASE_USERNAME = ${{Postgres.PGUSER}}
   JDBC_DATABASE_PASSWORD = ${{Postgres.PGPASSWORD}}
   ZAP_HOST             = https://your-zap-service.up.railway.app
   ZAP_API_KEY          = (비워두기 — disablekey=true 사용)
   CORS_ALLOWED_ORIGINS = https://your-app.vercel.app,https://*.up.railway.app
   ```
6. Deploy → 도메인 확인: `https://scanops-backend.up.railway.app`

> `railway.toml`의 healthcheckPath(`/api/scans`)로 헬스체크 수행

## 환경변수 전체 목록

| 변수 | 설명 | 필수 |
|------|------|------|
| `JDBC_DATABASE_URL` | PostgreSQL JDBC URL | ✅ |
| `JDBC_DATABASE_USERNAME` | DB 사용자 | ✅ |
| `JDBC_DATABASE_PASSWORD` | DB 비밀번호 | ✅ |
| `ZAP_HOST` | ZAP 서비스 URL | ✅ |
| `ZAP_API_KEY` | ZAP API 키 (disablekey시 공백) | |
| `OPENAI_API_KEY` | OpenAI API 키 (Phase 1) | |
| `CLAUDE_API_KEY` | Claude API 키 (Phase 2) | |
| `GEMINI_API_KEY` | Gemini API 키 (Phase 2) | |
| `CORS_ALLOWED_ORIGINS` | 허용 Origin 목록 (쉼표 구분) | ✅ |
| `PORT` | 서버 포트 (Railway 자동 주입) | |
