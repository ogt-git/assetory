# 트랙 B(B1~B4) 종합 점검 보고서

> 대상: 시장가격 영향 공식 · 저장(B1 · B2), 보유 종목 시세 소급 · C-04 보완(B3), 전일 · 전월 · 전년 증감 API(B4) (브랜치 `feature/tracking`, 커밋 전 작업 트리)
> 최종 검증 2026-10-29 — `./gradlew clean test` (Docker 켬)
> 키 · 토큰 · 계좌번호 · connectedId는 이 문서에 넣지 않았다.

## 1. 결론

**자동 테스트 기준으로 B1~B4는 완료됐다.** `./gradlew clean test` 411개 · 실패 0. 남은 것은 실호출 · 실계좌 확인(4장)과 커밋이다. 거래정지 필드(V3)는 실호출로 확인했다(2026-10-29, `tr_stop_yn=Y` — `docs/ops/kis-verification.md` V3). 장기 거래정지 종목의 일자별 응답은 아직 가짜 서버 기준이다.

## 2. 테스트 · 실행 결과

| 항목 | 결과 | 근거 |
|---|---|---|
| `./gradlew clean test` (최종) | 통과 | **411개 · 실패 0 · 스킵 0** · 10분 39초 (46개 클래스) |
| 중간 전체 실행 1회 실패(378개 중 1개) | 무효 → 수정 | 새 테스트 컨텍스트가 `create-drop`으로 user_id를 1부터 다시 매기는데 Redis 새로고침 쿨다운 키가 남아 다른 사용자에게 걸림. `IntegrationTestSupport`에서 `codef:refresh:cooldown:*` 정리 후 통과 |
| 중간 전체 실행 1회 실패(227개 중 20개 클래스) | 무효 | Docker 꺼짐(`initializationError`). 켠 뒤 재실행 통과 |
| 로컬 bootRun + Swagger | 통과(일부) | `/v3/api-docs`에 `GET /api/snapshots/changes` · 응답 스키마 · 예시 2개, 토큰 없이 401. 로그인 상태 호출은 미실행(4장) |
| 로컬 DB 컬럼 | 통과 | `asset_detail_snapshots.quantity decimal(18,4) NULL` · `price decimal(18,2) NULL` (information_schema, 05 §4.8과 일치) |
| 요청당 쿼리 수(B4) | 통과 | 최대 7회(최근 기록이 COMPLETE면 6회) — Hibernate Statistics로 테스트 고정 |
| 인덱스(B4, EXPLAIN) | 일부 | 목표 날짜 이하 조회는 `uk_asset_snapshots_user_date` range 사용. 나머지는 테스트 DB 행이 적어 `ALL` — 운영 규모 미확인 |

### 트랙 B 테스트 — 기능별

| 기능 | 테스트 | 핵심 경로 |
|---|---|---|
| 시장가격 영향 계산 (FR-AN-01) | `SnapshotJudgeTest` 44 (+14) | 계속 보유 수량 · 추가 매수 · 일부 매도 · 전량 매도 · **두 계좌 같은 ticker** · 거래정지 · 종목 없음 0 · **기준 quantity NULL → NULL** · 종목별 반올림 · **액면분할 한계 고정** · detail 수량 · 단가 |
| 저장 (FR-AN-01, asset_snapshots) | `SnapshotSaveServiceIntegrationTest` 16 (+5) | 연속 COMPLETE · PARTIAL 사이 기준 · 재실행 재계산 · 다운그레이드 방지 · 전환기 NULL · quantity · price 저장 |
| 배치 · C-04 (FR-AS-02, FR-MK-04) | `DailyAssetBatchIntegrationTest` 23 (+2 · 보강 1) | 거래정지 + 이력 없음 → 소급 성공 COMPLETE / 실패 PARTIAL(0 채움 없음) / 두 사용자 소급 1회 / 이력 있으면 소급 없음 |
| 소급 서비스 (FR-MK-04 · 05) | `StockPriceBackfillServiceIntegrationTest` 5 | 응답 전부 저장(가장 오래된 날 제외) · 종목 실패 격리 · 멱등 · **스냅샷 0** · 보유 0 → 호출 없음 |
| 최초 연동 소급 연결 (05 §11.1) | `InitialSyncBackfillIntegrationTest` 6 | 수집 SUCCESS → 소급 / 실패 · EMPTY_RESULT · 락 충돌 → 소급 없음 / 소급 예외에도 연동 · 수집 유지 / 재연결 중복 없음 |
| 증감 계산 (FR-AN-01 · 02 · 03 · 06) | `AssetChangeCalculatorTest` 15 | 말일 보정 · 윤년 · 기준 없음 · 첫 COMPLETE · 증감률 경계 · 기간 market NULL · 자산군 NULL · 전일 저장값 |
| 증감 조회 | `AssetChangeQueryServiceIntegrationTest` 10 | PARTIAL · FAILED 건너뜀 · 최근 기록 PARTIAL · 기록 20일 · 0건 · **다른 사용자 미포함** · (기준, 최근] 경계 · 쿼리 수 |
| 증감 API | `SnapshotApiIntegrationTest` 4 | 응답 구조 · 0건 200 · 401 · 토큰 주인 데이터만 |
| E2E | `DailyAssetE2eTest` 6 (E1~E7) | E1 U1 market −5,400 · other −257,600, U2 0 · 20,000 / **E7 연동 → 소급 → 스냅샷 0** |

## 3. 수행 내용과 결정 사항

### 수행 내용 (FR 기준)

| ID | 작업 | FR · 테이블 | 결과 |
|---|---|---|---|
| B1 | 시장가격 영향 공식 확정 | FR-AN-01, asset_snapshots, asset_detail_snapshots | 05 §4.7 공식 · 매매일 처리 · NULL 조건 · 한계 2개, §4.8 `quantity` · `price`, §4.11 월간 방식 |
| B2 | market · other 저장 | FR-AN-01 | 일일 배치가 직전 COMPLETE 대비 계산 · 저장. 기준일 detail 조회 N+1 → 1회 |
| B3 | 최초 연동 시세 소급 · C-04 | FR-MK-04 · 05, stock_prices | 수집 성공 뒤 같은 비동기 작업에서 소급, 배치의 거래정지 직전 종가 보완 |
| B4 | 전일 · 전월 · 전년 대비 API | FR-AN-01 · 02 · 03 · 06 | `GET /api/snapshots/changes` — COMPLETE끼리, 기간 미달은 available=false + 연동 이후 누적 |

### 이번 주 결정

| 항목 | 결정 | 반영 |
|---|---|---|
| 01 §14 #1 시장가격 영향 공식 | (asset_id, ticker)별 HALF_UP2[min(q_기준, q_오늘) × (p_오늘 − p_기준)] 합, 기준 = 직전 COMPLETE | 01 §14, 05 §4.7 |
| 01 §14 #2 매매일 처리 | 계속 보유 수량만 반영 — 늘어난 수량 0, 줄어든 수량 제외 (체결 단가 · 시각 미수집) | 01 §14, 05 §4.7 |
| 01 §14 #3 예금 이자 | 별도 식별하지 않고 기타 자산 변화에 포함 (입출금 · 이자 데이터 미수집) | 01 §14, 05 §4.7 |
| 01 §14 #19 PARTIAL 화면 표시 | 금액 · 비교 모두 최근 COMPLETE 값, 기준 날짜 · 상태 표시, PARTIAL 부분 금액 미노출 | 01 §14, 04 FR-AN-06, 03 S-01 · S-02 |
| 소급 범위 "최근 30일" | 최근 20거래일 — 표시 · 이벤트 소급 범위로 적용, 저장은 한투 1회 응답 전부 | 04 FR-MK-04 · FR-DT-08 · NFR-US-03, 05 §11.1 |
| C-04 장기 거래정지 | 직전 종가가 없으면 시세 소급 1회(종목당 실행 1회) 후 판정, 그래도 없으면 그 종목 실패 | 05 §4.7 · §11.2, 6주차 보고서 C-04 |
| 전월 · 전년 기준 | 최근 COMPLETE 날짜의 1개월 · 1년 전 같은 날(말일 보정) 이하의 마지막 COMPLETE | 04 FR-AN-02 · 03 |
| prod 스키마 | 마이그레이션 도구 없이 수동 ALTER 기록. 이번 컬럼은 첫 배포 전이라 불필요 | `docs/ops/schema-changes.md` |

### 보류 결정 (문서 수정 없음)

| 항목 | 결정 | 다시 볼 때 |
|---|---|---|
| 메트릭 `market.price.backfill{result}` | 보류 — 로그로 충분 | 15주차 운영 지표 정리 |
| 인덱스 `(user_id, status, snapshot_date)` | 보류 — 지금은 UNIQUE `(user_id, snapshot_date)` | 데이터 축적 후 EXPLAIN |
| 공개 API 목록 문서 | 만들지 않음 — Swagger가 명세 | — |

## 4. 외부 검증 남은 항목

| 항목 | 영향 | 확인 방법 · 시점 |
|---|---|---|
| ~~V3 `tr_stop_yn=Y` (거래정지 종목)~~ | 거래정지 판정(HALTED) 자체 | **확인됨(2026-10-29)** — 거래정지 종목 308100에서 `Y`, 일반 종목 `N` (`docs/ops/kis-verification.md` V3) |
| 장기 거래정지 종목의 한투 일자별 응답(거래정지 전 거래일 행이 오는지) | C-04 해소 여부 — 비어 있으면 소급해도 PARTIAL 유지 | 10주차 안 실호출 — 같은 절차 |
| 01 §14 #4 CODEF 보유수량 반영 시각 · 체결 반영 | 시장가격 영향의 q_오늘 정확도 | 실계좌 점검 |
| 05 §4.6 CODEF 보유 응답이 전체 목록인지 | "전량 매도" 판정, 시장가격 영향 | 실계좌 점검 |
| 로그인 상태 `GET /api/snapshots/changes` 실호출 | 실데이터 응답 확인 | 화면 · API 흐름은 fake 프로필 데모 계정으로 확인(2026-10-31, C1~C3 — `docs/ops/fake-profile.md`). 실계좌 데이터 응답은 실계좌 점검 때 |

## 5. 이월 목록

| 항목 | 내용 | 시점 |
|---|---|---|
| FR-DT-08 이벤트 소급 | 20거래일 범위를 설정값(`assetory.*`)으로 적용, `user_settings.backfill_completed` 함께 | FR-DT-08 구현 시 |
| 전환기 market NULL 화면 처리 | B2 배포 직후 기간 안에 market NULL 행이 섞여 전월 · 전년 · 누적 market · other가 null → "계산 불가" 표시 | 화면 작업 시 |
| 월간 결산 시장가격 영향 | `monthly_reports.market_change_amount` = 그 달 COMPLETE 일별 합 (05 §4.11) | 12주차 |
| 신규 매수 종목 소급 | FR-MK-04(최초 연동 시) 범위 밖 — 구현하지 않음. 거래정지 종목만 배치가 보완 | — |
| prod 초기 DDL | `validate`라 빈 RDS로 기동 불가 → 첫 배포 때 초기 DDL 적용 절차 | 15주차 |
| Redis 사용자 키 테스트 격리 | 쿨다운 외 사용자 단위 키도 컨텍스트 간 user_id 재사용 영향 가능 — 실패 시 `IntegrationTestSupport`에 패턴 추가 | 재발 시 |
| `codefSyncExecutor` 풀 크기 | core 1 — 연동 직후 소급 시간만큼 다음 연동의 최초 수집이 대기 | 사용자 증가 시 |

## 6. B5 총자산 기간별 추이 (FR-AN-05 · 06) — 2026-10-29 추가

| 항목 | 결과 | 근거 |
|---|---|---|
| `GET /api/snapshots/trend?period=1M\|1Y\|ALL` (생략 시 1Y) | 완료 | COMPLETE 기록 점 목록 + 기간 요약. 시작점 = 1M · 1Y는 FR-AN-02 · 03과 같은 기준 기록(없으면 첫 COMPLETE, `fullPeriod=false`), ALL은 첫 COMPLETE |
| B4 일치 | 통과 | 1M full = `/changes` month, 1Y full = year, ALL · 기간 미달 = sinceFirstRecord (서비스 테스트에서 직접 비교) |
| 기간 시장가격 영향 | 통과 | 시작점을 뺀 (시작, 끝] 점의 market 메모리 합, NULL 하나라도 있으면 market · other NULL (05 §4.7) |
| 잘못된 period | 통과 | `3M` · `1m` · `1y` · `ONE_MONTH` → 400 `INVALID_PARAMETER`. Converter 빈은 실패 시 enum 이름(ONE_MONTH)으로 다시 변환돼 통과하므로 컨트롤러 `@InitBinder` PropertyEditor로 묶음 |
| 요청당 쿼리 수 | 통과 | 최대 3회(최근 COMPLETE + 시작 기준 + 점 목록) — Hibernate Statistics로 테스트 고정 |
| 인덱스 | 일부 | `possible_keys = uk_asset_snapshots_user_date`, 테스트 DB 행이 적어 `ALL` — 운영 규모 미확인 |
| 로컬 Swagger | 통과(일부) | `/trend` 노출 · period enum 1M/1Y/ALL · 기본 1Y · 토큰 없이 401. 로그인 상태 호출은 미실행(4장) |
| `./gradlew clean test` | 통과 | **444개 · 실패 0 · 스킵 0** · 5분 46초 (B5 추가: TrendPeriodTest 12, AssetChangeCalculatorTest +3, AssetTrendQueryServiceIntegrationTest 12, SnapshotApiIntegrationTest +6) |

### B5 이월

| 항목 | 내용 | 시점 |
|---|---|---|
| `hasEvent` 점 필드 | 이번에는 넣지 않음 — 차트 날짜 클릭 시 이벤트 표시(01 §3.2)는 감지 기능과 함께 추가 | FR-DT 구현 시 |
| 전환기 market NULL 구간 | B2 이전 행은 점의 market · other가 null → 차트에서 시장가격 영향 · 기타 구분 표시를 그 구간에서 생략 | 화면 작업 시 |

## 7. B6 자산 기록 목록 · 날짜별 상세 (FR-AS-06, FR-AN-01 종목 단위) — 2026-10-29 추가

| 항목 | 결과 | 근거 |
|---|---|---|
| `GET /api/snapshots?month=yyyy-MM` (생략 시 최근 기록이 있는 달) | 완료 | 그 달 기록 전체(상태 무관) 내림차순. 누락일(PARTIAL · FAILED)은 `missing=true`, 직전 COMPLETE 총자산 + `referenceDate`, 증감 null — 부분 금액 미노출 (01 §14 #19) |
| 월 요약 · 상승 · 하락일 | 완료 | 그 달 첫 · 마지막 COMPLETE (FR-MR-01), market은 (첫, 마지막] 일별 합 · NULL 포함 시 NULL (05 §4.11). 상승 · 하락은 COMPLETE 행 전일 대비 부호 |
| `GET /api/snapshots/{date}` | 완료 | 자산군 금액 · 기준일 대비 증감, 자산별 묶음(예수금 · 수기 잔액), 종목 수량 · 단가 · 금액 · 전일 대비(FR-AN-01). 누락일은 `assets []`. 비활성 수기 자산도 표시 |
| 종목명 | 완료 | `stock_prices`에서 ticker별 그 날짜 이하 최신 이름 1회 조회, 없으면 ticker |
| 없는 날짜 · 다른 사용자 날짜 | 통과 | 404 `SNAPSHOT_NOT_FOUND`(같은 응답). 잘못된 형식(`2026-13` · `2026-02-30` · `abc`) → 400 `INVALID_PARAMETER` |
| 계좌번호 | 통과 | 저장된 마스킹 값만 — 응답 본문 · 결과 객체에 원문 없음(테스트) |
| 쿼리 수 | 통과 | 목록 최대 3회, 상세 COMPLETE 4회 · 누락일 2회(자산 fetch join, N+1 없음) — Hibernate Statistics |
| 인덱스 | 통과 | 월 행 조회 `uk_asset_snapshots_user_date` range, 첫 · 최근 날짜 MIN/MAX는 인덱스로 처리(Select tables optimized away). 종목명 서브쿼리는 미확인 |
| 경로 | 확정(2026-10-30) | 날짜 경로에 정규식을 두지 않고 형식이 틀린 값(`abc` 등)은 400 `INVALID_PARAMETER`로 유지 — 사용자 확정. `/changes` · `/trend`는 고정 경로라 먼저 매칭(테스트) |
| 로컬 Swagger | 통과(일부) | 두 엔드포인트 노출 · 토큰 없이 401. 로그인 상태 호출은 미실행(4장). **스키마 이름 `HoldingItem` 겹침 발견(아래)** |
| `./gradlew clean test` | 통과 | **477개 · 실패 0 · 스킵 0** · 6분 9초 (B6 추가: SnapshotRecordCalculatorTest 7, SnapshotMonthQueryIntegrationTest 11, SnapshotDetailQueryIntegrationTest 8, SnapshotApiIntegrationTest +7) |

### B6 이월 · 남은 문제

| 항목 | 내용 | 시점 |
|---|---|---|
| Swagger 스키마 `HoldingItem` 겹침 | **수정됨 (2026-10-30)** — B6 상세의 `HoldingItem`이 기존 `/api/assets`의 `HoldingItem` 스키마를 덮어쓰던 문제(실행 동작은 정상). 자산 기록 응답(`AssetChange` · `AssetTrend` · `SnapshotMonth` · `SnapshotDetail`)의 내부 record 9개에 `@Schema(name = "<바깥 이름><내부 이름>")`(예: `SnapshotDetailHoldingItem`)을 붙이고 규칙을 AGENTS 6장에 추가. 회귀 테스트 `SwaggerIntegrationTest.apiDocs_holdingItemSchemasSeparate` · `apiDocs_snapshotNestedSchemaNames`. 기존 `/api/assets` · 연동 응답의 내부 record(`SecuritiesAccount` · `HoldingItem` · `Totals` · `AccountResult` 등)는 범위 밖이라 그대로 | 완료 |
| `events[]` | 상세에 넣지 않음 — S-02 펼침의 이벤트 · [원인 보기]는 감지 기능과 함께 추가 | FR-DT 구현 시 |
| 전환기 null | B2 이전 기록은 market · detail `quantity` · `price`가 null → 화면에서 빈 값 처리 | 화면 작업 시 |
| 월 요약 기준 | 그 달 첫 거래일의 전월 말 대비 변화는 포함하지 않음 — 12주차 월간 결산과 같은 기준 | 12주차 |

## 8. B7 보유 종목 손익 · 자산 비중 (FR-AS-03 · 04) — 2026-10-30 추가

| 항목 | 결과 | 근거 |
|---|---|---|
| `GET /api/snapshots/portfolio` | 완료 | 최근 COMPLETE 기록 기준. 자산군(SECURITIES · DEPOSIT · CASH) 금액 · 비중, 종목 행(계좌 · 종목 1개당 1행) 평가 · 원금 · 손익 · 손익률 · 비중 · 당일 변동 |
| 평가 기준 | 통과 | 평가금액 · 수량 · 단가 · 당일 변동 = 그날 asset_detail_snapshots(한투 종가 × 수량). holdings의 CODEF 평가액 · 손익은 쓰지 않음(엉뚱한 값을 심어 검증) |
| 원금 · 손익 | 통과 | 원금 = 기록 수량 × 현재 평균 매입가(HALF_UP 2자리), 매칭 (asset_id, ticker). 평균 매입가 없음 · 그 뒤 전량 매도 · 기록 수량 없음 → null. 손익률 HALF_UP 4자리, 원금 0이면 손익률만 null |
| 비중 | 통과 | 분모 = 그날 총자산, HALF_UP 2자리, 합계 보정 없음(예: 33.33 × 3 = 99.99). 총자산 · 자산군 금액은 `/changes`와 같음 |
| 종목명 | 통과 | 시세 이름(그 날짜 이하 최신) → 현재 holdings 이름 → ticker |
| 계좌번호 | 통과 | 저장된 마스킹 값만 — 응답 본문에 원문 없음(테스트) |
| 쿼리 수 | 통과 | 최대 5회(최근 기록 · 최근 COMPLETE · detail + 자산 · 현재 holdings · 종목명), N+1 없음 |
| Swagger | 통과(일부) | `/portfolio` 노출, 스키마 `PortfolioAssetClassItem` · `PortfolioHoldingItem`(AGENTS 6장 이름 규칙) — 기존 `HoldingItem`(`/api/assets`)과 겹치지 않음, 토큰 없이 401. 로그인 상태 호출은 미실행(4장) |
| `./gradlew clean test` | 통과 | **503개 · 실패 0 · 스킵 0** · 5분 17초 (B7 추가: PortfolioCalculatorTest 9, PortfolioQueryServiceIntegrationTest 10, SnapshotApiIntegrationTest +5) |

### B7 결정

| 항목 | 결정 | 반영 |
|---|---|---|
| 기준 데이터 | 최근 COMPLETE 기록(한투 종가 × 수량) — `/api/assets`(CODEF 기준)는 그대로 | 04 FR-AS-03 · 04 |
| API | 새 엔드포인트 `GET /api/snapshots/portfolio` | Swagger |
| 원금 | 기록 수량 × 현재 평균 매입가, 알 수 없으면 null | 04 FR-AS-03, 05 §4.6 |
| 비중 | 분모 총자산, 소수 2자리, 합계 보정 없음 | 04 FR-AS-04 |
| holdings.eval_amount | 문서를 구현에 맞춤 — CODEF 평가액(현재 자산 표시용), 공식 평가는 asset_detail_snapshots | 05 §4.6 |

### B7 이월

| 항목 | 내용 | 시점 |
|---|---|---|
| `/api/assets`와 대시보드 값 차이 | `/api/assets`는 CODEF 평가액(수동 새로고침 반영), 대시보드(`/changes` · `/portfolio`)는 최근 COMPLETE 기록 — 같은 화면에 함께 보이면 기준 날짜 · 출처를 표시 | 화면 작업 시 |
| 원금 한계 | 기록 이후 추가 매수로 평균 매입가가 바뀌면 원금이 어긋날 수 있음(수동 새로고침 뒤에만) | — |
| `institutionName` | 증권 계좌 `Asset.name`(연동 때 기관명) 사용 — 계좌 별칭 기능이 생기면 기관 엔티티를 함께 읽도록 변경 | 별칭 기능 시 |
