# AGENTS.md — Assetory 작업 규칙
<!-- Claude Code · Codex 공통 원본. Claude Code 전용 규칙은 CLAUDE.md. 지금 코드 설명이 아니라 앞으로의 작업 규칙이다 -->

## 1. 역할
- 시니어 백엔드 개발자(Java/Spring) 겸 PM으로 일한다. 동의보다 정확한 판단을 우선한다.
- 판단 기준은 15주(12월 종료) 1인 일정이다. 일정에 안 맞으면 축소안을 같이 낸다(01 §10 리스크: 예적금 비교부터 축소).
- 설계 · 구현 선택은 "발표에서 이유를 설명할 수 있는가"로 한 번 더 점검한다.

## 2. 프로젝트 개요
- 자산 가계부(Assetory): 지출 대신 **자산의 변화**를 자동 기록하고, 왜 변했는지 근거와 함께 보여준다.
- 구조: 기반(자산 기록 · 추이 · 월간 결산) + 핵심 기능 3개(① 종목 급등락 원인 분석 ② 투자 목표 ③ 예적금 비교 · 만기 갈아타기).
- 캡스톤 1인 프로젝트. 실서비스가 아니라 아이디어 구현이 목적 — 기능은 단순하게, 핵심 경험은 완성도 있게.

## 3. 프로젝트 구조
- `backend/` Spring Boot · `frontend/` React(Vite) · `docs/` 설계 문서 · `docker-compose.yml` 로컬 MySQL · Redis
- 백엔드: `com.project.assetory.<도메인>/{controller,service,repository,domain,dto,exception}` 도메인 우선 분리. 공통은 `global/`.
  - 현재 도메인: `auth` · `user`(회원 · 인증), `connect`(기관 연동 · CODEF), `asset`(자산 · 보유 종목), `market`(시장 데이터 — 한투 `kis` 클라이언트 · `stock_prices` · 거래일 판정), `snapshot`(일별 자산 기록 · 일일 배치)
- 프론트: `src/app`(라우터 · 프로바이더), `src/features/<도메인>`, `src/pages/<화면>`, `src/shared`.

| 찾는 것 | 문서 |
|---|---|
| 서비스 방향 · 범위 · 불변 원칙 · 일정 · 미결(§14) | `docs/requirement/01_방향정의_최종.md` (**기준**) |
| 사용자 요구 UR-xx · 유스케이스 UC-xx | `docs/requirement/02_사용자요구사항_유스케이스.md`, `UC-04_유스케이스명세.md` |
| 화면 S-xx · 화면 흐름 | `docs/design/03_화면흐름_리텐션설계.md` |
| FR · NFR · 배치 시각 · 제약사항 | `docs/requirement/04_요구사항명세서.md` |
| 테이블 · 컬럼 · ENUM · Redis 키 · 데이터 흐름 | `docs/design/05_ERD_테이블설계서.md` |
| 이식 모듈 범위 · 이식 시 결정(D-·M-·N-) | `docs/migration/port-record.md` |

## 4. 기술 스택 (고정 · 주의할 것만)
- Java 17 · Spring Boot 3.5 · MySQL 8.0 · Redis 7. 시간대 Asia/Seoul.
- React **18.3.1 고정**(`@types/react` 포함, 19로 올리지 않는다) · TypeScript 6 · Vite 8 · Tailwind 4 · Node 24(`frontend/.nvmrc`).
- 프론트 린트는 ESLint가 아니라 **oxlint**, 포맷은 prettier(singleQuote).

## 5. 개발 규칙
### A. 문서 우선순위
- 01이 기준이다. 문서끼리 다르면 01을 따르고, 구현에 영향이 있으면 **진행 전에** 충돌 위치를 보고한다.
- 컬럼명 · ENUM · 한도 수치 · 배치 시각을 추측하지 않는다. 04 · 05에서 찾고, 없으면 묻는다.
- 새 ID(UR · UC · FR · S-xx) · 테이블 · 컬럼을 임의로 확정하지 않는다. "제안"으로 보고한다.
- 01 §14의 `[확인 필요]` · `[구현 시 확정]` 항목은 설정값 · 상수로 빼고 `// TODO(01-§14 #번호)`를 남긴 뒤 보고한다.
- 설계 문서(docs/requirement, docs/design)는 요청받은 작업에서만 수정한다. 다르게 구현해야 하면 보고의 "문서 반영 필요"에 적는다.

### B. 범위
- 제외(제안 · 구현 금지): 지출 내역 · 카드 연동 · 지출 분류, 증권 ↔ 예적금 효율 비교, 주식 매매 · 자금 이동, 커뮤니티, 투자 추천 엔진, 해외 주식, 제품 내 관리자 화면(운영 지표는 Grafana), 부하 테스트, 휴면 처리. 대출 비교 · 소셜 로그인 · 이메일 알림은 향후 확장.
- 알림 채널: 사용자는 웹 푸시(기본, 요약 + 딥링크) + 텔레그램(선택, 전문). 디스코드는 개발자용 운영 오류 · 배포 결과 알림 전용이라 사용자 채널로 쓰지 않는다. 이메일(SMTP)은 가입 인증 · 비밀번호 재설정에만 쓴다.
- 이식 모듈(CODEF 연동, AES-256 암호화, 인증)은 다시 만들지 않는다. 수정은 버그 · ERD 정합성 범위만.
- Redis Stream · 분산 락 라이브러리 · Spring Batch · 새 라이브러리는 도입 전에 필요한 이유 · 더 단순한 대안 · 운영 비용을 보고하고 승인받는다. 배치는 `@Scheduled`가 기본이다.

### C. 도메인 불변 조건 — 어기면 버그
- 비교 · 감지 · 결산 · 목표 계산은 **COMPLETE 스냅샷만** 쓴다. 비교 기준은 **직전 COMPLETE**. 월간 결산의 월초 · 월말 = 그 달 첫 · 마지막 COMPLETE.
- 수집 실패 자산을 직전 값으로 이월하지 않는다. 누락 · 해석 불가 값을 0으로 채우지 않는다(정상 0은 유효) → null + 실패 처리.
- 공식 일별 기록은 그 배치 실행의 수집 결과로만 판정한다. 수동 새로고침은 현재값만 갱신하고 스냅샷 · 감지 · 알림을 만들지 않는다.
- 사용자 자산 기록은 연동일부터. 과거 자산을 소급 생성하지 않는다(종목 시세 · 이벤트만 소급, 소급 이벤트는 알림 없음).
- 기타 자산 변화 = 총자산 변화 − 시장가격 영향. 원인을 추측하는 문장을 만들지 않는다.
- 평가금액 = 한투 종가 × 같은 기준 시점 보유수량.
- 급등락 = 전 거래일 대비, 기본 ±5%(사용자 조정 가능), 상승 · 하락 모두. 총자산 급변은 원인 분석(뉴스 수집) 대상이 아니다.
- 공유 분석은 (종목, 날짜)당 1회. Redis 락이 1차, DB UNIQUE가 최종 방어선.
- 장 마감 이후 공개된 자료를 당일 가격 변동의 원인처럼 표현하지 않는다.
- 목표 계산은 수익률 0%, 현재 자산 + 월 납입액. 최근 증가액을 공식에 넣지 않는다.
- 외부 수집 · 이벤트 · 결산은 유니크 기준으로 멱등 처리한다. 기록 · 결산 · 진척 조회는 배치가 저장한 값으로 응답한다(실시간 연산 금지).

### D. 사용자 문구 · LLM
- 인과 단정 금지: "~때문에 올랐습니다" ❌ → "같은 시점에 ~가 있었습니다" ⭕. 근거에는 출처 · 시각 · 링크.
- 근거 0건이면 LLM을 호출하지 않고 "찾지 못했습니다" 템플릿을 쓴다.
- 매수 · 매도 권유 금지. 금융상품은 "추천" ❌ → "비교" ⭕. "현재가 가장 유리합니다" 같은 범위 밖 단정 금지.
- 금액을 제시하면 가정과 계산 기준을 같이 보여준다. 근거가 없으면 금액을 만들지 않는다.
- AI 사용량 · 비용은 사용자 화면 · API 응답에 노출하지 않는다.
- LLM은 원인 요약 · 목표 코멘트 · 월간 총평에만 쓴다. 숫자는 코드가 계산해 넣는다(LLM에게 계산시키지 않는다).
- 한도(01 §7.1: 분석당 1회, 사용자당 신규 분석 일 10건, 목표 코멘트 이벤트 기반, 총평 월 1회, 전역 예산)는 설정값으로 둔다.
- LLM 실패 · 한도 초과여도 근거 · 계산 결과는 항상 응답한다(NO_EVIDENCE · SUMMARY_SKIPPED는 오류가 아닌 정상 상태).

## 6. 코딩 컨벤션 (이 프로젝트에서 정한 것만)
- 응답은 모두 `global/response/ApiResponse`로 감싼다. 실패는 `ErrorCode` + 도메인 예외 + `GlobalExceptionHandler`로 처리한다.
- 외부 API 원문 메시지 · 예외 메시지를 응답에 그대로 내보내지 않는다 → `ErrorCode` 메시지로 바꾼다.
- DTO 이름은 `*Request` / `*Response`. (`*Dto`는 이식 잔재 — 새로 만들지 않는다)
- DTO 안의 내부 record에는 `@Schema(name = "<바깥 DTO 이름에서 Request/Response를 뺀 것><내부 이름>")`을 붙인다(예: `SnapshotDetailHoldingItem`). Swagger는 단순 이름이 같으면 스키마를 하나로 덮어써 다른 API 문서가 틀려진다.
- 엔티티는 `BaseTimeEntity`를 상속한다. 금액은 `BigDecimal`(`double` 금지), 타입 정밀도는 05 §1.3.
- 임계치 · 한도 · cron · 재시도 횟수는 하드코딩하지 않고 `application.yml`의 `assetory.*` 아래 설정값 + 환경변수 오버라이드로 둔다.
- `data.sql`은 기동마다 실행된다 → id를 고정하고 `ON DUPLICATE KEY UPDATE`로 멱등하게 작성한다.

## 7. 명명 규칙
- 테이블 · 컬럼 · ENUM 값은 05 그대로 쓴다(snake_case, 암호화 컬럼 `_enc`). 05에 없으면 제안으로 보고한다.
- Redis 키는 05 §9 표의 패턴만 쓴다(`auth:*`, `codef:*`, `lock:analysis:*`, `ai:*`, `notify:*`, `kis:*`). 새 키는 제안으로 보고한다.
- 문서 ID: 요구 `UR-xx` · 유스케이스 `UC-xx` · 화면 `S-xx` · 기능 `FR-<영역>-xx` · 비기능 `NFR-<영역>-xx`(영역 코드는 04 §1.3).
- 커밋: `<접두사>: <한글 요약>`. 접두사 feat · fix · refactor · test · docs · chore, 이식 코드는 port.
- 브랜치: `main`(배포) ← `develop`(통합) ← `feature/<도메인>`(예: `feature/snapshot`). <!-- 제안 — 기존 브랜치 구성에 맞춤, 사용자 확정 필요 -->

## 8. 디자인 패턴 (05 §1.3 · §13, 04 NFR-SC)
- 공유 데이터와 개인 데이터 분리: 종목 · 날짜의 사실은 공유 테이블(`stock_event_analyses`), 사용자 비중 · 손익은 사용자 테이블(`user_market_events`).
- 설정 계층화: 시스템 기본값(`detection_rules`) + 사용자 오버라이드(`user_detection_settings`).
- 현재 상태(assets · holdings)와 기록(snapshots)을 분리하고, 계산은 배치에서 미리 저장한다.
- 확장 지점은 인터페이스로 둔다: 알림 채널, 근거 수집기, 알림 큐 구현(`auth/mail/AuthMailSender` 방식 참고).
- 외부 API 클라이언트는 그 API를 쓰는 도메인 패키지 안에 둔다(예: `connect/codef`). `global/`에 두지 않는다.

## 9. 보안 및 인증
- 민감정보 컬럼은 `_enc`(AES-256-GCM, `AesEncryptConverter`), 마스킹 값은 별도 컬럼. 이메일은 `email_enc` + `email_hash`로 조회.
- 비밀번호 · 토큰 · connectedId · 계좌번호 원문 · 기관 로그인 정보 · CODEF 응답 원문을 로그에 남기지 않는다.
- 모든 자산 · 연동 데이터 접근은 소유자 검증을 거친다 → `findByIdAndUserId`로 조회, `findById` 후 비교 ❌.
- 인증: JWT(sub = userId, typ = AT/RT). AT 30분은 응답 본문, RT 14일은 HttpOnly 쿠키(Path `/api/auth`).
- RT는 테이블 없이 Redis에 SHA-256 해시로 저장, 회전된 RT 재사용 시 사용자 전체 세션 폐기(05 §3.3). `refresh_tokens` 테이블을 만들지 않는다.
- 키는 환경변수로 받는다(`.env.example`에 이름만). `.env` · `application-local.yml` · 키 파일은 수정 · 커밋하지 않는다.
- 외부 API 타임아웃 10초. 인증 실패는 재시도하지 않는다(기관 계정 잠금 위험).
- Actuator는 외부에 노출하지 않는다(별도 포트 또는 IP 제한, NFR-SE-08).

## 10. TDD 및 테스트
- 감지 · 원인 분석 · 목표 · 결산의 계산 로직은 **테스트를 먼저** 쓴다. 5-C 불변 조건은 테스트로 고정한다.
- 계산 · 파서 로직은 스프링 없는 단위 테스트, API 흐름은 `support/IntegrationTestSupport` 상속 통합 테스트(Testcontainers MySQL · Redis, Docker 필요).
- 외부 API는 mock(`@MockitoBean`). 응답 픽스처는 `src/test/resources/<api>/`에 JSON으로 둔다.
- 테스트 · 픽스처에 실제 키 · 계좌번호 · 이메일을 넣지 않는다 → 더미 값 + "테스트 전용" 주석.
- 테스트를 통과시키려고 테스트를 지우거나 단언을 약하게 바꾸지 않는다. 기대값이 틀렸다고 판단되면 근거와 함께 보고한다.

## 11. 개발 워크플로우
1. 시작: 관련 FR · 테이블 · 01 원칙을 문서에서 찾는다. 범위 밖 · 충돌이면 여기서 멈추고 보고한다.
2. 계획: 바꿀 파일 · 테스트 · 미결 항목을 정리한다. 모호하면 질문 1~3개.
3. 구현: 테스트 먼저(10장) → 구현. 요청 범위 밖 리팩터링은 하지 않고 보고에 적는다.
4. 검증: 13장 명령어로 테스트 · 빌드를 돌린다.
5. 보고: 변경 요약(파일 · 관련 FR) / 테스트 결과(못 돌렸으면 이유) / 문서 반영 필요 / 확인 필요 / 커밋 제안.
- 에이전트는 **커밋 · push를 하지 않는다**. 커밋 단위와 메시지만 제안한다.
- 이식 코드(port)와 수정 코드(refactor · fix)는 커밋을 나눠 제안한다(발표에서 구분해 보여주기 위해).
- 커밋 제안 전 `git status`에 `.env` · `application-local.yml` · 키 파일이 없는지 확인한다.

## 12. 주의사항 / 금지사항
- **멈추고 물을 조건**: 기능 추가 · 범위 변경 · 방향 변경, 문서에 없는 값 필요, 문서 간 충돌, 새 라이브러리 · 인프라 도입, 테이블 · 컬럼 변경.
- 이식 전 프로젝트 이름을 코드 · 문서 · 커밋에 쓰지 않는다 → "기존 프로젝트"로 쓴다.
- 엔티티 변경 = 스키마 변경이다. 운영은 `ddl-auto: validate`라 05와 어긋나면 기동 실패 → 05 반영 필요로 보고한다.
- 기관 미지원 판단을 사전 차단 목록으로 만들지 않는다 → CODEF 오류 코드로만 안내한다.
- 프론트 의존성 메이저 업그레이드 금지(React 18 고정) → 필요하면 이유와 영향을 보고한다.

## 13. 명령어
```bash
docker compose up -d                       # 루트. 로컬 MySQL · Redis (.env 필요, .env.example 참고)
cd backend && ./gradlew bootRun            # 기본 프로필 local, 루트 .env를 읽는다
cd backend && ./gradlew test               # 전체 테스트 (Docker 필요)
cd backend && ./gradlew test --tests '*StockAssetParserTest'   # 단일 테스트
cd backend && ./gradlew build              # 빌드 + 테스트
cd frontend && npm ci && npm run dev       # 개발 서버
cd frontend && npm run lint -- --deny-warnings && npm run typecheck && npm run build   # CI와 동일
cd frontend && npm run format              # prettier
```
<!-- 백엔드 린트 도구 없음, 백엔드 CI 없음(.github/workflows는 frontend-ci만) — NFR-MT-03 미충족, 확인 필요 -->
