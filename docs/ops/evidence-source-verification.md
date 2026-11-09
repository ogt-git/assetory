# 근거 소스(DART · ECOS · 네이버 뉴스 · 한투 지수) 실호출 검증 기록

> 관련: 01 §6 · §14 #7~#11, 04 FR-CA-01 · 02 · 08 · FR-MK-02, 05 `event_sources` · `macro_indicators`
> B0에서 문서로 확인하지 못한 항목을 실제 응답으로 확인한 기록이다. 결과를 보고 B1~B4 설정값(공시 유형 · 시간 범위 · 키워드 규칙 · 통계 코드)을 확정한다.
> 설계 문서(01~05)는 여기서 고치지 않는다. 확정되면 "문서 반영 필요"로 따로 올린다.

## 확정된 방향 (2026-11-07, 사용자 결정 — A안 보강)

- LLM 원인 요약에는 DART 공시 · 지수 동반 등락 · 거시지표만 넣는다.
- 네이버 뉴스는 API 값(제목 · 요약 · 언론사 · 시각 · 링크)을 고치지 않고 "같은 날 보도" 블록으로 따로 보여준다. 네이버 출처와 원문 링크를 붙인다. 기사 본문은 가져오지 않는다.
- 뉴스 행은 21일 뒤 지운다. 뉴스 건수도 LLM에 넣지 않는다(코드가 문장에 넣는다).
- 근거: NAVER API HUB 약관(2026-06-25 시행) 일반조건 7③ · 8, 검색 특약. 2026-09-20 시행 검색 특약 개정(AI 입력 금지 · 이력 조회 목적 21일)은 원문 미확보 — 보도 · 개발자 정리 기준.

## 실행 방법

키 4개가 필요하다. 없는 소스는 그 소스만 "미실행"으로 기록하고 정상 종료한다.

| 환경변수 | 발급 |
|---|---|
| `DART_API_KEY` | OpenDART 인증키 신청 — https://opendart.fss.or.kr |
| `ECOS_API_KEY` | 한국은행 ECOS Open API 인증키 신청 — https://ecos.bok.or.kr/api |
| `NAVER_API_HUB_CLIENT_ID` · `NAVER_API_HUB_CLIENT_SECRET` | 네이버 클라우드 콘솔 NAVER API HUB → Application 등록 → 인증 정보 (개발자센터 키는 쓸 수 없다) |
| `EVIDENCE_SAMPLE_EVENTS` | 샘플 이벤트 `종목코드:종목명:yyyy-MM-dd`를 쉼표로 구분해 넣는다. 아래 1단계 S0 결과의 예시 줄을 쓴다 |

1. 샘플 이벤트 후보 — 한투 키로 `kisLiveTest`를 돌리면 S0(최근 60거래일 ±5% 이상인 날) · K1(업종지수)이 같이 나온다 (`docs/ops/kis-verification.md`).
   결과 파일 `backend/build/kis-live/verification-<시각>.md`의 S0 예시 줄에서 5종목 × 2날짜를 고른다.
2. 근거 소스 검증 (PowerShell):
```powershell
$env:DART_API_KEY = '<DART 키>'; $env:ECOS_API_KEY = '<ECOS 키>'
$env:NAVER_API_HUB_CLIENT_ID = '<Client ID>'; $env:NAVER_API_HUB_CLIENT_SECRET = '<Client Secret>'
$env:EVIDENCE_SAMPLE_EVENTS = '000660:SK하이닉스:2026-10-15,...'
cd backend; .\gradlew.bat evidenceLiveTest
```

- 결과: `backend/build/evidence-live/verification-<시각>.md`(집계) · `n4-classify-<시각>.md`(기사 제목 분류표). `gradlew clean`을 하면 지워진다.
- N4 분류표의 "관련" 칸은 사용자가 직접 O / X / ?로 표시한다. 기사 제목이 들어 있으니 분류가 끝나면 지운다.
- ECOS E1은 거래일 16:00 직후 1회 + 다음 날 아침 1회 돌려 같은 날 값이 언제 생기는지 비교한다.
- 호출량: DART 약 100회(샘플 10건 × 유형 10개) · 네이버 최대 약 450회(샘플 10건 × 키워드 4개 × 최대 10페이지 + N2 · N3) · ECOS 10회 미만.
- 기본 `./gradlew test`에서는 실행되지 않는다(`@Tag("evidence-live")` 제외).
- 키 · 요청 URL · 예외 메시지는 출력하지 않는다(DART · ECOS는 URL에 키가 들어간다). 실패는 예외 클래스 이름만 남는다.

| # | 무엇을 보나 | 결정에 쓰는 곳 |
|---|---|---|
| D1 · D5 | corpCode.xml 크기 · 건수 · 샘플 종목과 ETF의 corp_code 유무 | D4 메모리 캐시, ETF 이벤트의 DART 대상 여부 |
| D2 | list.json 필드 · 시각 필드 유무 · rcept_no 앞 8자리 = rcept_dt | #8 published_at |
| D3 | 잘못된 키 · 범위 초과 · 결과 없음의 status | 오류 코드 → ErrorCode 매핑 |
| D4 | 샘플 이벤트 D−4 ~ D+3 공시 유형별 건수 · 보고서명 예시 | #11 공시 유형 화이트리스트 |
| E1 · E2 | 기준금리 · 원/달러 최근 TIME, 휴일 · 오류 RESULT 코드 | E3 거시지표 사용 방식 |
| K1 | 업종지수 KOSPI 0001 · KOSDAQ 1001 행 · 필드 · 1회 최대 행 수 | FR-MK-02 지수 수집 |
| N2 · N3 | 정렬 · 날짜 필터 없음 확인 · 도달 가능한 가장 오래된 pubDate | 뉴스 소급 가능 범위 |
| N5 | 키워드 4종(종목명 · +주가 · +특징주 · 종목코드)별 건수 | #10 키워드 규칙 |
| N6 | 게시 시각 분포(D 15:30 전 · 15:30~16:00 · 16:00~D+1 09:00 · 이후) | #7 시간 범위 · FR-CA-08 표시 구분 |
| N7 | 필드 형식(`<b>` · 엔티티 · link 호스트 · pubDate) | 표시 정리 규칙 |
| N4 | 기사 제목 분류표 (사용자 표시) | #10 노이즈 판정 |

## 결과

> Phase 3 실행 뒤 집계와 판정만 옮겨 적는다(기사 제목 · 원문은 옮기지 않는다).

### D1 · D5 — corp_code (미실행)

| 항목 | 값 |
|---|---|
| ZIP 크기 · 전체 건수 · 상장 건수 | |
| 샘플 종목 커버리지 | |
| ETF corp_code | |

### D2 · D3 — 목록 필드 · 오류 (미실행)

| 항목 | 값 |
|---|---|
| 시각 필드 | |
| rcept_no 앞 8자리 = rcept_dt | |
| 잘못된 키 · 범위 초과 · 결과 없음 status | |

### D4 — 공시 유형별 건수 (미실행)

| 이벤트 | A | B | C | D | E | F | G | H | I | J |
|---|---|---|---|---|---|---|---|---|---|---|

### E1 · E2 — ECOS (미실행)

| 실행 시각 | 기준금리 최신 TIME | 원/달러 최신 TIME | 휴일 RESULT | 잘못된 키 RESULT |
|---|---|---|---|---|

### K1 — 업종지수 (미실행)

| 업종코드 | 이름 | 최근 행 | 1회 최대 행 수 |
|---|---|---|---|

### N2 · N3 · N5 · N6 · N7 — 네이버 뉴스 (미실행)

| 항목 | 값 |
|---|---|
| 정렬 · 날짜 필터 | |
| 도달 가능한 가장 오래된 pubDate | |
| 키워드별 기간 내 건수 | |
| 게시 시각 분포 | |
| 필드 형식 | |
