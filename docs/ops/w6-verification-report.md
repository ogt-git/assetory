# 6주차 종합 점검 보고서

> 대상: 한투 클라이언트 · 거래일 판정 · 종목 시세 수집 · STOCK/ETF 판별 · 스냅샷 판정 · 일일 자산 기록 배치 (브랜치 `feature/KISAPI`, 커밋 전 작업 트리)
> 점검 ① 2026-10-13 17:38~ · 점검 ② 2026-10-13 18:10~18:40
> 키 · 토큰 · 계좌번호 · connectedId는 이 문서에 넣지 않았다.

## 1. 결론

**자동 테스트 · 실호출(한투) 기준으로는 7주차 기반으로 써도 된다.** 단, 실제 CODEF 계좌로 돌리는 E2E(E1 · E2)를 아직 못 했다. 7주차 집계 작업 전에 E1 · E2와 V4(다음 날 비교)를 끝내는 것을 조건으로 둔다.

## 2. 테스트 · 실행 결과

| 항목 | 결과 | 근거 |
|---|---|---|
| `./gradlew clean build` (점검 ①) | 통과 | 341개 · 실패 0 · 5분 19초 (Docker 켬) |
| `./gradlew clean build` (점검 ② 후) | 통과 | 345개 · 실패 0 · 7분 59초 |
| `./gradlew clean build` (C-02 삭제 · E2E 자동화 후, 2026-10-14) | 통과 | **348개 · 실패 0** · 8분 51초 (C-02 테스트 −2, E2E +5) |
| 중간 빌드 1회 실패(304개 중 36개) | 무효 | 다른 세션이 같은 작업 트리에서 동시에 빌드해 클래스 파일이 사라짐(`NoClassDefFoundError`). 코드 문제 아님 — 재실행 통과 |
| kisLiveTest V1 (prdy_ctrt 기준) | 통과 | 18:40 실행: 30/30일 일치, 2018-05-04 `prdy_vrss=−1,100`(기준가 53,000 대비). 18:06 실행은 EGW00201로 분할일 조회 실패 → C-07 수정 후 통과 |
| kisLiveTest V2 (ETF 판별) | 통과 | 069500 `EF`, 005930 `ST` |
| kisLiveTest V3 (거래정지 필드) | 일부 | 정상 종목 `tr_stop_yn=N`만 확인. 거래정지 종목의 `Y`는 미확인 |
| kisLiveTest V4 (16:00 종가 확정) | 1차만 | 2026-10-13 18:06: 종가 236,500 · −5.59% (장중 13:24는 231,000). 다음 날 같은 행과 비교 필요 |
| kisLiveTest V5 (휴장일 응답) | 미실행 | 주말 필요 |
| E1 정상 배치 · E2 재실행 (가짜 외부 API, 자동) | 통과 | `DailyAssetE2eTest.e1_normalDay_e2_rerun` — U1~U5 시나리오, 3장 SQL 점검 · 토큰 재사용(발급 1회) · Redis TTL · 지표 · 로그 민감정보까지 단언 |
| E1 · E2 (실 CODEF 계좌) | 미실행 | 실제 응답 형식 · 예수금 의미 확인용으로만 남김 (01 §14 #4) |
| E3 장 마감 전 실행 | 통과(자동) | `DailyAssetE2eTest.e3_beforeClose` — 호출 0 · 스냅샷 0 · ERROR 로그 |
| E4 휴장일 | 통과(자동) | `e4_holiday` — 거래일 판정 호출 1회 외 0 |
| E5 한투 장애 | 통과(자동) | `e5_kisDown` — 가짜 서버가 연결을 끊음 → UNKNOWN 중단 · ERROR 로그 |
| E6 한투 키 오류 | 통과(자동) | `e6_badKey` — 토큰 발급 1회 실패 · 재시도 없음 · 키가 로그에 없음 |
| 3장 SQL 점검 | 통과(자동) | total = 자산군 합, 실패 자산 detail 없음, 예수금 행 중복 없음, PARTIAL 변화 컬럼 NULL, 시장/기타 변화 NULL, stock_prices 합집합 · 부호 · 직전 종가, security_type, sync_jobs |
| 3장 교차 검증 · 사용자 시세 · 예수금 비교 | 미실행 | 실계좌 필요 |
| 4장 운영 지표 · Redis 토큰 TTL | 통과(자동) | 관리 포트 지표 이름 · 태그(종목코드 · 사용자 · 키 없음), API 포트 actuator 미노출, `kis:access-token` TTL = 만료 − 10분, 다른 `kis:*` 키 없음 |
| Grafana 패널 확인 | 미실행 | 화면 확인 필요(배치 지표 패널은 추가 제안 — 15주차) |
| 5장 코드 점검(정적) | 완료 | 아래 3장 |

### 6주차 테스트 — 기능별

| 기능 | 테스트 | 핵심 경로 |
|---|---|---|
| 한투 클라이언트 | `KisApiClientTest` 12 · `KisTokenManagerTest` 6 · `KisConfigTest` 2 | 토큰 재사용 · 동시 발급 1회 · 만료 재발급 1회 · EGW00201 재시도 · 0 채움 금지 · uri 태그 · 네트워크 오류(C-06) |
| 거래일 판정 | `TradingDayServiceTest` 9 | 주말 · 휴장 · **UNKNOWN(장애)** · 가드 전 · 지연일 |
| 시세 수집 · 판별 | `StockPriceCollectServiceIntegrationTest` 13 | 가드 · 매핑 · 분할일 · 멱등 · HALTED/FAILED · **0 채움 금지** · 실패 격리 · 연속 실패 중단 · EF/ST/기타 |
| 판정 | `SnapshotJudgeTest` 30 | 규칙 1~15 · 자산군 NULL · **0 채움 금지** · 전일 대비 |
| 저장 | `SnapshotSaveServiceIntegrationTest` 11 | 멱등 · 직전 COMPLETE · detail 규칙 · **COMPLETE 덮어쓰기 금지** · 메트릭 |
| 배치 | `DailyAssetBatchIntegrationTest` 21 | 휴장 · **UNKNOWN 중단** · 지연일 · **이월 방지** · EMPTY_RESULT · 락 재시도 · AUTH_FAILED · 대상 선정 · 거래정지 · 재실행 · 사용자 격리 · **수동 새로고침 스냅샷 없음** · 가드 전 중단(C-03) · 자산 행 없음(C-06) |
| CODEF 동기화(수정분) | `StockSyncIntegrationTest` +2 | security_type 유지 · EMPTY_RESULT 표시(C-06) |

남은 테스트 공백: 스케줄러 빈 등록(`assetory.sync.batch.enabled`) · 실제 Redis TTL(목만 사용) — E2E로 확인 예정.

## 3. 발견한 문제

| ID | 내용 | 영향 | 심각도 | 수정 제안 | 작업량 | 7주차 전 |
|---|---|---|---|---|---|---|
| C-01 | 테스트 소스에 임시 실험 파일 `tmpprobe/MockitoRaceProbeTest` | 빌드마다 2만 회 스레드 실험 · 패키지 규칙 위반 | 개선 | 삭제 | 1분 | 필요 |
| C-02 | `StockSyncBatchService`를 어디서도 호출하지 않음(이식 코드) | 죽은 코드 · 기존 테스트만 유지 | 개선 | 삭제하고 port-record에 기록 / 남김 | 30분 | 아님 |
| C-03 | 일반일에 장 마감 가드 전 실행되면 INFO + `deferred`로 조용히 끝남 | cron 오설정 시 그날 기록 누락이 안 보임 | 위험 | 지연일만 deferred, 일반일은 ERROR + `aborted` | 30분 | 필요 |
| C-04 | 오래 거래정지된 종목은 직전 종가 행이 없어 매일 PARTIAL | 해당 사용자 비교 · 감지 정지 | 위험 | 7주차 30일 소급(FR-MK-04) 연결 시 해소 | 7주차 | 아님 |
| C-05 | 장 마감 지연일(`2026-11-19`)을 설정값으로 연 1회 갱신해야 함, 그날 실제 매매시간은 KRX 공지 미확인 | 갱신 누락 시 지연일 기록 누락 | 위험 | 운영 문서 체크리스트 + 11월 초 KRX 공지 확인 | 문서 | 아님(11월 전) |
| C-06 | 테스트 공백: EMPTY_RESULT 표시 · 한투 네트워크 오류 · 증권 자산 행 없는 ACTIVE 연동 | 회귀 감지 불가 | 개선 | 테스트 추가 | 1시간 | 필요 |
| C-07 | 실호출 V1의 검증용 직접 호출이 스로틀 없이 나가 EGW00201 | 검증 결과 누락 | 개선 | 호출 전 대기 + EGW00201 재시도 | 15분 | 필요 |
| C-08 | 다른 세션이 같은 작업 트리 · 브랜치에서 동시에 수정 · 빌드 | 변경 덮어쓰기 · 빌드 무효 | 위험 | 한 브랜치에는 한 세션만, 병행은 worktree 분리 | 운영 | 필요 |

### 점검 ② 처리 결과

| ID | 처리 | 수정 내용 · 테스트 | 재점검 |
|---|---|---|---|
| C-01 | **이미 해소** | 점검 ② 시작 시점에 다른 세션이 이미 삭제 | 빌드 345개에 포함 안 됨 확인 |
| C-02 | **수정됨 (2026-10-14)** | `StockSyncBatchService` 삭제, 이 클래스만 검증하던 `StockRefreshAndBatchIntegrationTest`의 테스트 2개 삭제(같은 내용은 일일 배치 · 동기화 테스트가 검증), 스케줄러 주석 정리, port-record §2에 대체 · 삭제 기록 | 빌드 348개 통과 |
| C-03 | **수정됨** | `DailyAssetBatchService.run()` — 지연일이면 INFO + `deferred`, 일반일이면 ERROR + `aborted`. 테스트 `regularDay_beforeGuard_aborted`(수정 전 실패 확인 → 수정 후 통과), 기존 `lateClose_regularRun_deferred` 유지 | E3 대체: 통합 테스트 통과. 실제 E3는 다음 거래일 15:40 전 |
| C-04 | **수정됨 (7주차, 2026-10-29 갱신)** | `DailyAssetBatchService.haltedPreviousClose` — 거래정지 종목의 직전 종가가 없으면 시세 소급(FR-MK-04) 1회 후 다시 찾고, 그래도 없으면 그 종목 실패. 종목당 실행 1회. 최초 연동 소급(`StockPriceBackfillService`)은 응답을 자르지 않고 저장. 테스트 `DailyAssetBatchIntegrationTest.halted_noHistory_*` 3개(소급 성공 → COMPLETE, 소급 실패 → PARTIAL, 두 사용자 → 소급 1회). 05 §4.7 · §11.2 반영 | 2026-10-29: `DailyAssetBatchIntegrationTest` 23개 통과(소급 3개 포함), 전체 `./gradlew test` 411개 통과 · 실패 0. 실제 장기 거래정지 종목의 한투 응답(`tr_stop_yn=Y`)은 V3(kis-verification.md)로 확인 |
| C-05 | 보류 | 문서 · 운영 항목 (D-21) | — |
| C-06 | **테스트 추가** | `StockSyncIntegrationTest.emptyResult_markedInResult`, `KisApiClientTest.networkError_noRetry`, `DailyAssetBatchIntegrationTest.activeLinkWithoutAssetRow_failed` — 기존 동작이 맞아 수정 전부터 통과(회귀 방지용) | 빌드 통과 |
| C-07 | **수정됨** | `KisLiveVerificationTest` — 검증용 직접 호출을 `itemChartPriceAround20180504()`로 분리, 호출 전 1초 대기 + EGW00201 최대 2회 재시도. 실호출 전용이라 단위 테스트로 재현 불가 | kisLiveTest 18:40: `rt_cd=0`, V1 통과 |
| C-08 | 운영 | 이번에는 다른 세션 종료 후 재빌드로 해결 | — |

### 테스트 데이터 · 가짜 외부 API (2026-10-14 추가)

키 · 실제 계좌 없이 `./gradlew test`만으로 E1~E6 · SQL · 지표를 매번 검증한다.

| 구성 | 위치 | 내용 |
|---|---|---|
| 가짜 한투 서버 | `src/test/java/.../e2e/FakeKisServer.java` | JDK 내장 HttpServer. 토큰 · 일자별 시세 · 종목정보 응답, 모드 NORMAL / NETWORK_DOWN / BAD_KEY, 호출 횟수 기록. 실제 `KisApiClient` · Redis 토큰 · 메트릭을 그대로 거친다 |
| 가짜 CODEF 응답 | `src/test/resources/e2e/codef/*.json` | 정상 포트폴리오(주식 · ETF · 거래정지), 어제 값, 실패 |
| 시나리오 데이터 | `src/test/java/.../e2e/E2eFixture.java` | U1 연동+수기 / U2 수기만(직전 COMPLETE 있음) / U3 AUTH_FAILED / U4 오늘 실패(이월 방지) / U5 해지만 |
| E2E 테스트 | `src/test/java/.../e2e/DailyAssetE2eTest.java` | E1 · E2 · E3 · E4 · E5 · E6 (5개 테스트) |

실제 한투 · CODEF로만 확인할 수 있는 것(실제 종가 일치, 예수금 D+0/D+2, 거래정지 실코드, V4 · V5)은 `kisLiveTest`와 실계좌 점검에 남는다.

> 참고: Windows 콘솔(cp949)에서는 로그의 대시(—)가 `?`로 찍힌다. 동작 문제는 아니며, 테스트는 대시 앞뒤 문구로 확인한다.

## 4. 코드 점검 (정적)

| 항목 | 결과 |
|---|---|
| 트랜잭션 경계 | 통과 — `market` · `snapshot`에 `@Transactional` 없음. 외부 호출은 트랜잭션 밖, 저장은 종목 · 사용자 단위 `TransactionTemplate` |
| 5-C 0 채움 · 이월 | 통과 — `BigDecimal.ZERO`는 자산군 합계 초기값 1곳뿐. 실패 계좌는 DB 값을 읽지 않음(이월 방지 테스트) |
| 보안 · 로그 | 통과 — 로그에는 DB id · 유형 · 코드만. 토큰 · 키 · 응답 원문 없음. `connectedAccountId`는 DB id(CODEF connectedId 아님) |
| 소유자 검증 | 해당 없음 — 새 코드는 사용자 요청 경로가 없음(배치 내부 `findById` 1곳) |
| 설정값 | 통과 — 가드 시각 · 기준 종목 · 연속 실패 · 지연일 · 재시도 대기 · 호출 간격 모두 `assetory.*` + 환경변수. `.env.example`은 이름만 |
| 외부 호출 | 통과 — 타임아웃 10초, 재시도는 EGW00201 · 토큰 오류 1회만, 스로틀 50ms |
| 시간대 | 통과 — `now()`는 모두 `Clock`(Asia/Seoul) 또는 KST 지정 |
| Redis 키 | 통과 — `kis:access-token`만 (05 §9) |
| 금지 표현 | 해당 없음 — 새 사용자 문구는 `MARKET_DATA_UNAVAILABLE` 1개 |

## 5. 문서 변경 목록

| ID | 문서 · 절 | 현재 | 변경안 | 근거 |
|---|---|---|---|---|
| D-01 | 01 §14 #5 | 확인 필요 | ✅ 한투 `prdy_ctrt`(기준가 대비) 그대로, 자체 조정 없음 | kis-verification V1 |
| D-02 | 01 §14 #24 · 05 §4.6 | 구현 시 확정 | ✅ 한투 종목정보 `scty_grp_id_cd` `EF`=ETF · `ST`=STOCK, 그 외 NULL. CODEF 재동기화 때 유지 | `StockPriceCollectService`, `StockSyncService` |
| D-03 | 01 §14 #23 · 04 배치 일정 | 구현 시 확정 | 16:00 유지(V4 최종 확인 후 ✅), 휴장일은 기준 종목 최신 거래일로 판정, 지연일(수능) 16:50 실행 | `TradingDayService`, `DailyAssetBatchService` |
| D-04 | 01 §14 #4 · port-record §4 | 확인 필요 | CODEF 응답 항목은 E1 + 사용자 샘플 후 갱신 (변경 없음) | 미실행 |
| D-05 | 04 FR-MK-06 | 휴장일 수집 생략 | + 판정 불가(UNKNOWN)는 휴장이 아니라 실행 중단 | 확정 3 |
| D-06 | 04 FR-AS-02 · 05 §4.7 | — | 거래정지 종목은 직전 종가로 평가해 성공 / 수기 자산만 있는 사용자도 기록 / EMPTY_RESULT는 누락(임시) | Step 0 확정 |
| D-07 | 05 §4.7 · §11.1 | 첫 행 = 계좌 연동일 | 첫 행 = 연동 뒤 첫 정기 배치일 (안 A) | Step 0 확정 2 |
| D-08 | 05 §4.7 | 자산군별(수집 성공분 합계) | 자산군 단위가 전부 실패하면 NULL, 대상 없으면 0 | Step 4 확정 7 |
| D-09 | 05 §4.7 | — | 재실행: 기존 COMPLETE는 PARTIAL · FAILED로 덮어쓰지 않음 | Step 4 확정 2 |
| D-10 | 05 §4.7 | 판정 대상 시각 구현 시 확정 | T = 배치 시작 시각. 연동은 T 이전 생성 · DISCONNECTED 제외, 수기 자산은 T 시점 balance | `findSnapshotTargets` |
| D-11 | 05 §4.7 | 변화율 단위 없음 | `day_change_rate` 퍼센트, 소수 4자리 HALF_UP | Step 3 확정 |
| D-12 | 05 §4.7 · §4.8 · §4.9 | created_at · updated_at 없음 | `BaseTimeEntity` 컬럼 추가 | Step 2 · 3 확정 |
| D-13 | 05 §4.8 | `day_change_amount` NOT NULL로 읽힘 | NULL 허용. 기준 = 총자산과 같은 직전 COMPLETE 날짜의 같은 (asset_id, ticker) 행, 없으면 NULL | `SnapshotSaveService.baseAmount` |
| D-14 | 05 §4.8 | UNIQUE (asset_id, ticker, snapshot_date) | ticker NULL 행은 MySQL UNIQUE가 막지 못해 앱 upsert로 보장 | Step 3 확정 |
| D-15 | 05 §4.9 | prev_close · change_rate 정의 부족 | 원주가 종가(`FID_ORG_ADJ_PRC=0`), change_rate = `prdy_ctrt`, prev_close = 직전 거래일 종가(참고용) · 없으면 저장 안 함 | Step 2 확정 |
| D-16 | 05 §1.3 | 반올림 규칙 없음 | 평가액은 종목별 HALF_UP 소수 2자리 후 합산 | Step 3 확정 |
| D-17 | 05 §11.2 · 04 배치 일정 | CODEF → 시세 → 판정 | 가드 확인 → 거래일 판정 → CODEF → 시세 → 판정. UNKNOWN · 일반일 가드 전은 중단 | `DailyAssetBatchService` |
| D-18 | 05 §4.3 | EMPTY_RESULT 처리 없음 | sync_jobs SUCCESS + 결과 errorType EMPTY_RESULT, 스냅샷은 누락 | `StockSyncService` |
| D-19 | 05 §12 | 메트릭 후보 표 | `external.api.*`에 `api` 태그, `market.price.collect{result}`, `batch.run{batch, result=completed\|holiday\|aborted\|deferred}` 추가. `batch.step.duration` step 세분화 제안 | 코드 |
| D-20 | 05 §1.2 · AGENTS.md 3장 | market · snapshot 패키지 없음 | `market`(시장 데이터: kis · stock_prices) · `snapshot`(자산 기록 · 일일 배치) 추가 | 패키지 구조 |
| D-21 | 01 §14 · 운영 문서 | 지연일 없음 | 신규 항목: 수능일 장 마감 지연 — 설정 `assetory.market.late-close-dates` 연 1회 갱신, KRX 공지 확인 | C-05 |
| D-22 | 04 배치 · 설정 이름 | `assetory.sync.batch.*` | 일일 자산 기록 배치 설정임을 명시(이름 변경 `assetory.daily-batch.*`는 제안) | 스케줄러 |

### 점검 ③ 처리 결과 (2026-10-20)

문서 버전 · 변경 이력은 올리지 않았다(별도 단계, 사용자 허락 필요).

| ID | 반영 위치 | 처리 |
|---|---|---|
| D-01 | 01 §14 ✅ 확정 · 미결 #5 | 반영 — #5를 ✅로 이동 |
| D-02 | 01 §14 ✅ · 미결 #24 · ✅ 목록의 기존 CODEF 항목, 04 FR-CN-03, 05 §4.6 | 반영 — #24를 ✅로 이동 |
| D-03 | 01 §3.1 · 미결 #23, 04 배치 일정 | 반영 — #23은 "구현 완료 · 실호출 확인 남음"(V4 · V5)으로 유지 |
| D-04 | 01 §14 #4 · port-record §4 | 변경 없음 (CODEF 실제 응답 확인 전) |
| D-05 | 04 FR-MK-06 | 반영 |
| D-06 | 01 §14 ✅, 04 FR-AS-02, 05 §4.7 | 반영 |
| D-07 | 01 §14 ✅ · v6 #15 보완, 04 FR-AS-05, 05 §4.7 · §11.1 | 반영 |
| D-08 · D-09 · D-10 · D-11 · D-12 | 05 §4.7 (D-12는 §4.8 · §4.9 포함) | 반영 |
| D-13 · D-14 | 05 §4.8 | 반영 — `day_change_amount` NULL 허용 |
| D-15 | 05 §4.9, 04 FR-MK-01 | 반영 |
| D-16 | 05 §1.3, 01 §3.1 | 반영 |
| D-17 | 05 §11.2, 04 배치 일정 | 반영 |
| D-18 | 05 §4.3 | 반영 |
| D-19 | 05 §12 | 반영 |
| D-20 | AGENTS.md 3장 | 반영 — 05 §1.2는 이미 "시장 데이터" 도메인이 있어 변경 없음 |
| D-21 | 01 §14 미결 #25 (신규) | 반영 — 매년 11월 초 확인 |
| D-22 | 04 배치 일정 | 반영 — 설정 이름 변경(`assetory.daily-batch.*`)은 제안으로만 남김 |
| **D-23 (신규)** | 02 UC(증권 연동) 기본 흐름 5 · 사후조건 | 반영 — "연동일 첫 스냅샷 생성"이 D-07(첫 기록 = 정기 배치)과 충돌해 함께 수정 |

## 6. 사용자에게 묻는 것

1. **실계좌 확인** — 예수금 D+0/D+2(01 §14 #4)만 실계좌로 1회 확인 (추천) / 하지 않음
2. **D-xx 반영 시점** — 점검 ③에서 D-01~D-22 일괄 반영 (추천) / V4 결과 뒤 반영
(C-02 삭제 · C-08 한 세션 진행은 2026-10-14 사용자 확정)

## 7. 미실행 항목과 다음 실행 시점

| 항목 | 다음 실행 |
|---|---|
| 실계좌 E1 · 교차 검증 · 예수금 의미 (01 §14 #4) | 사용자가 실계좌로 확인하고 싶을 때 (자동 E2E로 흐름 검증은 끝남) |
| V4 다음 날 비교 | 2026-10-14(수) 아무 때나 |
| E3 실제 실행 | 다음 거래일 15:40 전 (자동 테스트로 대체됨) |
| E4 · V5 | 2026-10-17(토) |
| V3 거래정지 종목 | 거래정지 종목코드를 알게 되면 |
| 사용자 시세 · 예수금 비교 (01 §14 #4) | E1 직후 |
