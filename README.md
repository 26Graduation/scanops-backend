# ScanOps 백엔드

Spring Boot 기반 인증·스캔·리포트 서버입니다. **Java CPG + LLM과 기존 파인튜닝 모델을 언어에 따라 연결합니다.**

| 분석 대상 | 경로 |
|---|---|
| Java / Java Spring Boot | 별도 Java 분석 API → Joern CPG + Qwen3.8-Max 앙상블 |
| 비Java | 기존 모델 API → Qwen3.5-9B QLoRA |
| 웹 URL | OWASP ZAP |

## 코드 보기

- [ScanopsModelClient](src/main/java/com/scanops/scan/ScanopsModelClient.java): Java 라우팅, 인증키 분리, 복수 경고·원본 라인 전달, 불완전 결과 거절
- [GithubPipelineRunner](src/main/java/com/scanops/scan/GithubPipelineRunner.java): 저장소 분석 흐름
- [GitHubAppWebhookController](src/main/java/com/scanops/scan/GitHubAppWebhookController.java): GitHub App 연동
- [JavaModelRoutingTest](src/test/java/com/scanops/scan/JavaModelRoutingTest.java), [ModelEnsembleContractTest](src/test/java/com/scanops/scan/ModelEnsembleContractTest.java): 라우팅·응답 계약 테스트

## 설정

| 변수 | 용도 |
|---|---|
| `SCANOPS_JAVA_MODEL_URL` | Java 전용 분석 API private URL |
| `SCANOPS_JAVA_API_KEY` | Java API의 `SCANOPS_API_KEY`와 동일한 값 |
| `SCANOPS_MODEL_URL` | 기존 비Java 모델 URL |
| `SCANOPS_API_KEY` | 기존 비Java 모델 인증키 |

Java URL을 설정하지 않으면 기존 경로를 유지합니다. 현재 CPG + LLM 연결에는 Java 전용 두 변수를 모두 설정해야 합니다.
DB·OAuth·GitHub App 등 나머지 설정은 [application.yml](src/main/resources/application.yml)을 기준으로 주입합니다.
인프라의 [현재 배포 안내](https://github.com/26Graduation/scanops-infra/blob/main/docs/JAVA_DEPLOYMENT.md)를 함께 확인하세요.

## 개발 및 검증

Java 17과 프로젝트 Gradle Wrapper를 사용합니다.

```sh
./gradlew test
# DB 및 인증 환경변수 설정 후
./gradlew bootRun
```

`main`의 기존 설문 기능과 DB 마이그레이션은 유지됩니다.
2026-09-08 사이트에서 Java 2파일 분석이 검증됐으며, 대규모 저장소와 실제 PR 완료 경로는 추가 검증이 필요합니다.

## 프로젝트 자료

- [현재 CPG + LLM 코드](https://github.com/26Graduation/scanops-model#readme)
- [기존 파인튜닝 모델 및 실제 가중치](https://github.com/26Graduation/scanops-model/blob/main/docs/FINETUNED_MODEL.md)
- [프론트엔드](https://github.com/26Graduation/scanops-frontend)
- [이전 README](docs/history/README-before-20260914.md): 과거 API·Railway 구성 참고용, 현재 계약은 소스 기준
