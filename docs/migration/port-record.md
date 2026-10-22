# 기존 모듈 이식 기록

- 기간: 2026-10-03 ~ 2026-10-11
- 대상: 기존 프로젝트(로컬 최신 작업본)의 회원·인증, 보안, CODEF 증권 연동, 자산 조회 모듈 → Assetory `backend/`
- 기준 문서: `docs/requirement/01_방향정의_최종.md`(우선), `04_요구사항명세서.md`, `docs/design/05_ERD_테이블설계서.md`
- 상태: **이식 완료**, 테스트 230개 통과. 설계 문서 01·02·03·04·05에 결과 반영 완료(버전·변경 이력 갱신은 별도 작업)
- 이 문서는 이식 과정의 분석·계획·진행 기록·문서 반영 목록을 하나로 줄인 최종 기록이다

---

## 1. 이식 결과표

경로: 기존 = 기존 프로젝트 소스 루트 기준, Assetory = `backend/src/main/java/com/project/assetory/` 기준
구분: **그대로** = 로직을 옮기고 이름·패키지만 맞춤 / **수정** = 옮긴 뒤 ERD·보안·결정에 맞춰 고침 / **신규** = 기존에 없어 새로 작성

| 기능 | 기존 경로 | Assetory 경로 | 구분 |
|---|---|---|---|
| 공통 응답 | `dto/ApiResponse`, `dto/PageResponse` | `global/response/ApiResponse`, `PageResponse` | 수정 — 인증 API 성공 응답도 감쌈, 실패 응답에 data 허용 |
| 전역 예외 처리 | `exception/GlobalExceptionHandler`, `ErrorCode` | `global/exception/*` | 수정 — CODEF 원문 비노출, 409(동기화 중)·429(쿨다운 + 남은 초)·422(미지원)·400(본문 오류), 제외 기능 코드 제거 |
| 생성·수정 시각 | — | `global/entity/BaseTimeEntity`, `global/config/JpaAuditingConfig` | 신규 |
| AES 암호화 | `util/AesEncryptionUtil`, `converter/AesEncryptConverter` | `global/crypto/AesEncryptionUtil`, `AesEncryptConverter` | 수정 — CBC → GCM, 키 `ASSETORY_AES_KEY`, 키 없으면 기동 실패 |
| 해시 | `util/AesEncryptionUtil.hash()` | `global/crypto/HashUtil` | 수정 — 분리, 토큰용 `sha256Hex` 추가 |
| 마스킹 | `util/MaskingUtil` | `global/crypto/MaskingUtil` | 수정 — 하이픈 제거, 짧은 번호 전체 마스킹 |
| JWT | `jwt/JwtUtil`, `jwt/JwtFilter` | `global/security/JwtUtil`, `JwtFilter` | 수정 — sub = userId, typ(AT/RT) 클레임, RT jti, 예외 메시지 로그 제거 |
| 보안 설정 | `config/SecurityConfig` | `global/security/SecurityConfig` | 수정 — 401/403 JSON, 제외 기능 경로 제거 |
| Redis 설정 | `config/RedisConfig` | `global/config/RedisConfig` | 그대로 |
| 비동기·스케줄 | — | `global/config/AsyncConfig`, `SchedulingConfig` | 신규 — 용도별 executor, 배치 트리거 |
| 헬스체크 | `controller/HealthController` | `global/controller/HealthController` | 그대로 |
| 회원 엔티티 | `domain/User` | `user/domain/User`, `UserStatus` | 수정 — ERD 기준 재작성 |
| 회원가입·로그인·로그아웃 | `controller/AuthController`, `service/AuthService` | `auth/controller/AuthController`, `auth/service/AuthService` | 수정 — 이메일 정규화, ACTIVE 아닌 회원 차단, 비밀번호 8~64자·72바이트 |
| Refresh Token | `service/RedisTokenService` (사용자당 1개, 원문 저장) | `auth/service/RedisTokenService`, `auth/support/RefreshTokenCookieProvider` | 수정 — 해시 키, 기기별 다중 세션, 회전 표식으로 재사용 탐지, Lua 원자 처리 |
| 이메일 인증 | `service/EmailVerificationService` | `auth/service/EmailVerificationService`, `auth/support/OtpStore` | 수정 — 시도 5회 제한, 쿨다운 60초, 코드 해시 저장 |
| 비밀번호 재설정 | `service/PasswordResetService` | `auth/service/PasswordResetService` | 수정 — 응답 통일, 재설정 후 전체 로그아웃, 메일 비동기 |
| 인증 메일 발송 | (서비스 안에서 메일 직접 발송) | `auth/mail/*` | 신규 — 발송 인터페이스, local 전용 로그 구현 |
| CODEF 설정·클라이언트 | `config/CodefConfig`, `codef/CodefApiClient` | `connect/codef/CodefConfig`, `CodefApiClient`, `CodefTimeouts` | 수정 — 10초 타임아웃(JVM 기본값), 제외 기능 API 제거 |
| CODEF 오류 분류 | `exception/CodefErrorType`, `codef/CodefErrorClassifier` | `connect/codef/CodefErrorType`, `CodefErrorClassifier` | 수정 — 13개 유형, 유형별 재시도·상태 전이 |
| 기관 목록 | (코드 상수) | `connect/domain/Institution`, `resources/data.sql` | 신규 — CODEF 목록 기준 증권 22·은행 20 |
| 기관 연결·해지·연동 목록 | `service/CodefService`, `controller/CodefController` | `connect/service/ConnectService`, `connect/controller/ConnectController` | 수정 — 연동 계정 1행(재연결 시 갱신), 해지는 DISCONNECTED·인증정보 파기·행 유지, 미확인 사전 차단 목록 제거 |
| 연동 계정·수집 이력 | `domain/CodefConnectedAccount` | `connect/domain/ConnectedAccount`, `SyncJob` | 수정 — ERD 기준 재작성, sync_jobs 기록 신규 |
| 증권 자산 수집 | `service/CodefSyncService` (증권 부분) | `connect/codef/stock/StockAssetParser`, `connect/service/StockSyncService` | 수정 — 파서 분리, 환율 제거, 누락값은 0으로 채우지 않고 실패, 소유자 토큰 락, 재시도 유형 제한 |
| 최초 연동 수집 | `service/CodefSyncService` (연동 직후 수집) | `connect/event/*` | 수정 — 커밋 후 비동기 이벤트 |
| 수동 새로고침 | `service/CodefSyncService`, `controller/CodefController` | `connect/service/StockRefreshService`, `ConnectController` | 수정 — 남은 초 응답, 409, 쿨다운 반환 규칙 |
| 배치 동기화 | `service/CodefBatchService` | `connect/service/StockSyncBatchService`, `StockSyncBatchScheduler` | 수정 — ACTIVE만, 설정값으로 켜고 끔(기본 비활성), 연동 비활성화·이중 재시도 제거 |
| 자산 엔티티 | `domain/AssetAccount`, `AssetItem`, `ManualAsset` | `asset/domain/Asset`, `Holding`, `DepositDetail` | 수정 — ERD 기준 재작성 |
| 수기 자산 CRUD | `service/ManualAssetService`, `controller/AssetController` | `asset/service/ManualAssetService`, `asset/controller/AssetController` | 수정 — 유형 3개, 예적금 상세 필수, soft delete, 연동 자산 수정 거부 |
| 자산 조회 | `service/AssetService` (증권 조회) | `asset/service/AssetQueryService` (`GET /api/assets`) | 수정 — 증권 + 수기 통합, 자산군별 합계, 불완전 표시 |
| 카카오 로그인 | `service/oauth/*`, `controller/OAuthController` | — | **제외(향후 확장)** |

**가져오지 않음** (CLAUDE.md 이식 원칙 3): 카드 연동·청구, 지출 저장·GPT 분류, AI 포트폴리오, 챌린지, 환율 API, 8분류 자산 enum, 커뮤니티, 투자 가능 금액·자산 요약, 회원 탈퇴(FR-AU-06 기준 신규 개발 대상), 초기 동기화 상태 조회.

### 구현된 API

| Method | Path | 인증 | 설명 |
|---|---|---|---|
| POST | `/api/auth/signup` · `login` · `refresh` · `logout` | 공개 / AT | 가입(인증 토큰 필수) · 로그인(AT 본문, RT 쿠키) · 갱신 · 로그아웃 |
| POST | `/api/auth/email-verify/request` · `confirm` | 공개 | 가입 인증코드 요청 · 확인 |
| POST | `/api/auth/password-reset/request` · `confirm` | 공개 | 재설정 코드 요청(항상 같은 응답) · 재설정 |
| GET | `/api/health` | 공개 | 헬스체크 |
| GET / POST / DELETE | `/api/codef/connections` · `/connect` · `/connect/{id}` | AT | 연동 목록 · 기관 연결(multipart) · 해지 |
| POST | `/api/codef/sync/stock` | AT | 증권 수동 새로고침 (5분 쿨다운) |
| GET | `/api/assets` | AT | 통합 자산 조회 |
| POST / GET / PUT / DELETE | `/api/assets/manual` · `/{assetId}` | AT | 수기 자산 등록 · 목록 · 수정 · 삭제(soft delete) |

---

## 2. 핵심 결정 (지금도 유효한 것)

| ID | 결정 |
|---|---|
| D-2 · D-3 | Refresh Token은 테이블 없이 Redis에 해시로 저장, 회전된 토큰 재사용 시 사용자 전체 세션 폐기 |
| D-4 | RT 쿠키 HttpOnly · Path `/api/auth`, 운영은 Secure · SameSite None |
| D-5 → 변경 | 카카오 로그인은 **이식 제외(향후 확장)** (2026-10-09). `password_hash` NULL 허용은 확장 대비로 유지 (2026-10-10) |
| D-7 | 예적금 · 입출금은 CODEF 수집 대신 수기 입력 (입출금은 CASH 유형, 2026-10-10) |
| D-8 | 배치는 기본 스케줄러(`@Scheduled`)만 사용, 분산 락 도구는 추가하지 않음 |
| D-9 · D-10 | AES-256-GCM, 이메일은 암호문 + SHA-256 해시로 저장 |
| D-14 | 기관당 증권 계좌 1개 |
| D-19 · D-23 | 수기 자산 유형은 DEPOSIT · SAVINGS · CASH, 예적금은 상세 필수, 수기 자산은 기관 없음(NULL) 허용 |
| D-21 | 연동 해지 = DISCONNECTED, 기관 인증 정보 즉시 파기, 행은 유지 |
| N-1 ~ N-9 | 승인된 Redis 키 · 환경변수 · 컬럼 · enum 값 · 제약 변경 (카카오 관련 N-3 · `oauth:state`는 미사용) |
| M-1 | CODEF는 국내 주식과 ETF를 구분하지 않음 → 수집 시 NULL, 다른 소스로 채움 |
| M-2 | `assets`에 DB 유니크 제약을 두지 않음 |
| M-3 | 검증된 인증 오류 → AUTH_FAILED, 인증 · 연동 만료 → EXPIRED, 그 외 상태 유지 + 제한된 재시도 |
| M-4 | 평가금액 = 한투 종가 × 같은 기준 시점 보유수량 (확정 유지, 실측은 아래 확인 필요) |
| M-5 | 휴면 회원 처리 범위 제외 |
| 2026-10-10 | 비밀번호 8~64자 + 72바이트 · 누락값 0 치환 금지(계좌 단위 FAILED, 부분 갱신 없음) · 수동 새로고침은 편의 기능(사용자 단위 쿨다운, 스냅샷과 분리) · 스냅샷은 그 배치 실행 결과로만 판정, 락 충돌 시 1회 재시도 후 누락 처리 · 미지원 안내는 CODEF 오류 코드로만 · 수기 자산 soft delete · FR-AS-07 필수, FR-CN-04 범위 제외(향후) |

---

## 3. 설계 문서 반영 요약 (원본 설계 문서 대비)

| 구분 | 내용 |
|---|---|
| 테이블 | refresh_tokens 삭제(Redis 대체) → **27개**. 소셜 로그인 테이블은 두지 않음 |
| users | `email_enc` + `email_hash`, `password_hash` NULL, `email_verified_at` · `terms_version` 추가 |
| connected_accounts | `DISCONNECTED`, `connected_id_enc` NULL, `account_password_enc` · `created_at` 추가, 재연결 · 해지 · 상태 전이 규칙 |
| sync_jobs | job_type 사용 규칙, error_type 13개, 응답 불완전 기준, 수동 실행과 판정 분리 |
| assets · deposit_details · holdings | 기관 NULL, `updated_at`, balance NULL(첫 정상 수집 전), 수기 유형 · 입출금 = CASH, soft delete, 상세 NOT NULL, `security_type` NULL, 평가 기준, 0 치환 금지 |
| asset_snapshots | 판정 대상 · COMPLETE 조건, 배치 실행 결과로만 판정 |
| Redis 키 | `auth:rt:*`, `auth:email-verify:*`, `auth:pw-reset:*`, `codef:connect:lock:*`, `codef:lock:*`, `codef:refresh:cooldown:*` |
| 요구사항 | 신규 FR-AU-08(이메일 인증) · FR-AU-09(비밀번호 재설정) · FR-CN-09(수동 새로고침). 변경 FR-AU-01~04 · 06, FR-CN-01~08, FR-AS-01 · 02 · 07, NFR-SE-01 · 07 |

---

## 4. 남은 확인 필요

| 항목 | 위치 | 시점 |
|---|---|---|
| CODEF 예수금 필드(`resDepositReceived`)의 실제 의미 · 빈 응답의 의미 | 05 §4.4, 01 §14 #4 | 데모 · 실제 응답 확인 시 |
| 보유 종목 응답이 전체 목록인지, 빈 목록 = 무보유인지 (응답에 없는 종목 삭제 규칙의 전제) | 05 §4.6 | 같은 시점 |
| CODEF 보유수량 반영 시각 · 종가 기준일 · 체결 반영 · 배치 계산 시점 | 01 §14 #4 | 공식 스냅샷 구현 전 |
| `resProductType` 실제 값, 시연 증권사 로그인 방식 지원 | 05 §4.6, 03 S-10 | 데모 응답 확인 · 프론트 연결 전 |
| 스냅샷 판정 대상 시각 · 연결 시각 기준, 최초 연동 첫 기록 생성 시점 | 05 §4.7 | 구현 시 확정 |
| 수기 자산 가치 갱신 방식 · 변경분 반영 시점 | 01 §14 #20 | 구현 시 확정 |
| 실제 SMTP 설정 (`MAIL_*`, local `log-only: false`) | 설정 | 메일 기능 개발 시 |
| 설계 문서 버전 · 변경 이력 갱신 | 01 · 04 · 05 | 별도 작업 |

---

## 5. 커밋 순서 (커밋은 사용자가 직접)

`port:`(기존 코드를 옮긴 상태)와 `refactor:` · `feat:`(보완 · 신규)를 나눠 커밋할 수 있도록 단계마다 차이를 패치로 남겼다.
패치 위치: `backend/build/port-patches/` (gitignore 대상, 저장소에 올라가지 않음). 패치 경로는 `a/` · `b/` 접두 형식이다.
나눠 커밋하기 번거로우면 단계 단위로 묶어 커밋하고, 본문에 아래 패치 번호를 적어 구분해도 된다.

| 단계 | 내용 | 패치 | 제안 커밋 |
|---|---|---|---|
| 0 | Boot 3.5 starter 정리, 의존성, `application*.yml`, `.env.example`, docker-compose | — | `refactor: Boot 3.5 의존성·설정 정리` |
| 1 | 공통(응답 · 예외 · 암호화 · 해시 · 마스킹 · 생성시각) | 1 · 2 | `port: 공통 모듈` → `refactor: AES-GCM 전환·마스킹 보완` |
| 2 | 회원 · 인증 · 보안 | 3 ~ 6 | `port: 회원·인증` → `refactor: JWT 타입·RT 해시 재사용 탐지·이메일 정규화·응답 통일` |
| 3 | 이메일 인증 · 비밀번호 재설정 | 7 ~ 9 | `port: 이메일 인증·비밀번호 재설정` → `refactor: 메일 발송 분리·시도 제한` |
| 4 | CODEF 연동 · 기관 연결 | 10 ~ 16 | `port: CODEF 연동` → `refactor: CODEF 연동 ERD 반영·오류 정책·기관 시드` |
| 5 | 증권 자산 수집 | 17 ~ 19 | `port: 증권 자산 파싱·최초 수집` → `refactor: 수집 저장·sync_jobs` |
| 6 | 수동 새로고침 · 배치 | 20 · 21 | `port: 동기화 락·수동 새로고침·배치 이식` → `refactor: 쿨다운 응답·락 충돌 처리·배치 설정화` |
| 7 | 수기 자산 · 통합 조회 · 정리 · 로그 | 22 ~ 25 | `port: 수기 자산 CRUD·자산 조회` → `refactor: 수기 자산 ERD 반영` + `feat: 통합 자산 조회 API` → `refactor: 이식 잔재 정리` → `refactor: 로그 민감정보 제거` |
| 8 | 최종 결정 반영 | 26 | `refactor: 비밀번호 64자·72바이트, 예수금 누락 FAILED, 0 치환 금지, 미확인 차단 목록 제거` |
| 문서 | 설계 문서 최종 수정 | — | `docs: 설계 문서 최종 수정 — 이식 결과·수집·스냅샷·새로고침 결정 반영` |
| 문서 | 이식 기록 · CLAUDE.md 정리 | — | `docs: 이식 기록 정리` |

커밋 전 `git status`로 `.env`, `application-local.yml`, 키 파일이 목록에 없는지 확인한다.

---

## 6. 최종 점검 결과 (2026-10-10)

| 점검 | 결과 |
|---|---|
| 전체 테스트 (`./gradlew cleanTest test`) | 230개 통과 (20개 클래스, 실패 · 오류 · 건너뜀 0) |
| 잔재 검사 (`backend/` 137개 파일) | 기존 프로젝트 이름 · 제외 기능(카드 · 지출 · 챌린지 · 포트폴리오 · 환율 API) 문자열 0건 |
| 시크릿 검사 (변경 파일 141개) | 실제 비밀 값 포함 0개, `.env` · `application-local.yml` gitignore, 하드코딩 값은 테스트 전용 더미뿐 |
| `.env.example` | 설정이 참조하는 환경변수 25개 모두 있음 |
| 로그 검사 (log 호출 48곳) | 비밀번호 · 토큰 · connectedId · 계좌번호 원문 출력 0건 (인증코드 출력은 local + log-only일 때만) |

---

## 7. 발표용 요약

1. **무엇을 이식했나** — 기존 프로젝트의 회원·인증(이메일 인증·비밀번호 재설정), 암호화·JWT 보안, CODEF 증권 연동·수집·수동 새로고침·배치, 자산 조회·수기 자산 모듈을 새 서비스로 옮겼다.
2. **무엇을 고쳤나** — 테이블을 ERD 기준으로 새로 만들고, 리프레시 토큰은 해시로 저장해 재사용을 탐지하고, 암호화를 AES-GCM으로 바꾸고, 빠진 값을 0으로 채우지 않고 실패로 기록하며, 삭제는 soft delete로, 로그에서는 민감정보를 뺐다.
3. **왜 고쳤나** — "확인할 수 없는 자산은 지어내지 않는다"는 사실 기록 원칙과, 소유자 검증·민감정보 비노출 같은 보안 요구사항(NFR-SE)을 지키기 위해서다.
