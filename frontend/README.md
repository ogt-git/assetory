# assetory frontend

자산 가계부 assetory의 프론트엔드. 로그인 · S-01 대시보드(② · ③ · ⑤ · ⑥) · S-02 자산 기록까지 백엔드와 연결돼 있고, 나머지 화면은 라우트만 있는 자리 페이지다.

## 실행 방법

Node 버전은 `.nvmrc`를 따른다 (`nvm use`).

```bash
npm ci
npm run dev        # http://localhost:5173, /api 요청은 http://localhost:8080 으로 프록시
npm run lint
npm run format      # prettier --write
npm run typecheck
npm run build
```

환경 변수는 `.env.example`을 참고해 `.env.local`을 만든다. `.env.local`은 git에 커밋하지 않는다.

## 폴더 구조

```
src/
├─ app/        App.tsx, router.tsx, providers.tsx, paths.ts
├─ pages/      화면별 폴더 (dashboard · records · login 외에는 제목만 있는 자리 페이지)
├─ features/   auth(로그인 · 토큰), asset(자산 기록 조회 · 대시보드 영역), analysis · goal · deposit · notification(준비 중)
└─ shared/
   ├─ api/          client.ts (axios 인스턴스), types.ts (응답 envelope · 공통 enum)
   ├─ components/   layout/ (BottomTabLayout), ui/
   ├─ hooks/
   └─ lib/          cn.ts, format.ts
```

의존 방향: `pages → features → shared`. `shared`는 `features`를 import하지 않는다.

경로 별칭 `@/*`는 `src/*`를 가리킨다 (`tsconfig.app.json`, `vite.config.ts` 양쪽에 설정).

## 라우트

| 화면             | URL                                    |
| ---------------- | -------------------------------------- |
| 로그인           | `/login`                               |
| S-01 대시보드    | `/`                                    |
| S-02 자산 기록   | `/records` (날짜는 `?date=YYYY-MM-DD`) |
| S-03 월간 결산   | `/reports`, `/reports/:yearMonth`      |
| S-04 원인 분석   | `/analysis/:stockCode/:date`           |
| S-05 종목 상세   | `/stocks/:stockCode`                   |
| S-06 목표        | `/goals`, `/goals/:goalId`             |
| S-07 예적금 비교 | `/deposits/compare`                    |
| S-08 알림        | `/notifications`                       |
| S-09 알림 설정   | `/settings/notifications`              |
| S-10 계좌 연동   | `/connect`                             |
| S-11 온보딩      | `/onboarding` (하단 탭 없음)           |
| 404              | 그 외 모든 경로                        |

URL은 직접 문자열로 만들지 말고 `app/paths.ts`의 함수를 사용한다. S-01~S-10은 하단 탭 레이아웃(`shared/components/layout/BottomTabLayout.tsx`) 안에서 렌더링되며, 탭은 홈·기록·결산·목표·알림 다섯 개다. `/login`, `/onboarding`은 탭 레이아웃 밖에 있다.

탭 레이아웃 안의 화면은 `features/auth/RequireAuth`가 감싼다. 로그인하지 않았으면 `/login`으로 보내고, 로그인하면 원래 가려던 경로로 돌아온다.

## 인증

- AT(30분)는 로그인 응답 본문으로 받아 메모리(`features/auth/authStore`)에만 둔다. localStorage · sessionStorage에 저장하지 않는다.
- RT(14일)는 HttpOnly 쿠키(Path `/api/auth`)라 프론트에서 읽지 않는다. 새로고침하면 AT가 사라지고, 가드가 `/api/auth/refresh`로 다시 받는다.
- API 요청이 401이면 refresh를 한 번 하고 원래 요청을 다시 보낸다. 여러 요청이 동시에 401이어도 refresh는 하나만 보낸다(`features/auth/session.ts`). RT가 회전되므로 같은 RT로 두 번 요청하면 서버가 세션 전체를 폐기한다.
- refresh가 실패하면 세션을 지우고 `/login`으로 보낸다.
- 로컬에서 데모 계정으로 확인하려면 백엔드를 fake 프로필로 띄운다(`docs/ops/fake-profile.md`).

## 화면 확인 (로컬)

```bash
cd backend && ./gradlew bootTestRun   # fake 프로필 — Docker 필요, 로그에 [FakeSeed] 데모 데이터 준비 완료가 찍힌 뒤 접속
cd frontend && npm run dev            # http://localhost:5173
```

데모 계정(`demo1`~`demo4@assetory.test`, 로컬 전용 더미 비밀번호)과 계정별 확인할 상태는 `docs/ops/fake-profile.md` 참고.

## S-01 대시보드 (03 §2)

| 영역                        | 컴포넌트 (`features/asset/`) | API                        | 상태                                                                         |
| --------------------------- | ---------------------------- | -------------------------- | ---------------------------------------------------------------------------- |
| ① 오늘의 변화 카드          | —                            | —                          | 원인 분석 기능에서 추가                                                      |
| ② 총자산 요약               | `AssetSummary`               | `/snapshots/changes`       | 전일 · 전월 · 전년 탭, 시장가격 영향 · 기타 자산 변화, 누락 배너(01 §14 #19) |
| ③ 총자산 추이               | `AssetTrendChart`            | `/snapshots/trend?period=` | 1개월 · 1년(기본) · 전체, 점 클릭 → `/records?date=`                         |
| ④ 목표 진척                 | —                            | —                          | 투자 목표 기능에서 추가                                                      |
| ⑤ 자산 구성                 | `AssetComposition`           | `/snapshots/portfolio`     | 자산군 금액 · 비중(⑥과 요청 공유)                                            |
| ⑥ 보유 종목                 | `HoldingList`                | `/snapshots/portfolio`     | 평가금액 · 손익 · 당일 변동                                                  |
| ⑦ 결산 배너 · ⑧ 예적금 배너 | —                            | —                          | 각 기능에서 추가                                                             |

- 기록이 하나도 없으면 영역 대신 `NoRecordsNotice`(계좌 연동 안내)를 보여준다.
- 영역마다 따로 조회한다 — 한 영역이 실패해도 다른 영역은 보이고, 실패한 영역에 다시 시도 버튼이 있다.
- 증감 · 비중 · 손익 · 시장가격 영향은 백엔드 값을 그대로 표시한다(프론트에서 다시 계산하지 않는다).

## S-02 자산 기록 (03 §3)

`/records` — 월 단위 날짜별 기록. 컴포넌트는 `features/asset/RecordsMonth`(월 화면) · `RecordDetail`(행 펼침).

| 요소         | API                 | 내용                                                                                                            |
| ------------ | ------------------- | --------------------------------------------------------------------------------------------------------------- |
| 월 헤더 ◀ ▶  | `/snapshots?month=` | `firstRecordMonth` ~ `latestRecordMonth` 밖이면 비활성. 월을 옮기면 `?month=yyyy-MM`                            |
| 이번 달 요약 | 같은 응답 `summary` | 그 달 첫 COMPLETE → 마지막 COMPLETE(월간 결산과 같은 기준). 항상 펼침, 상승일 · 하락일 수                       |
| 날짜 행      | 같은 응답 `records` | 날짜 내림차순, 휴장일은 행 없음. 🔔는 `hasEvent=true`일 때만(클릭 없음)                                         |
| 누락 행      | 같은 응답           | "9/12 (금) · 일부 누락 — 9/11 (목) 기준 ₩…" — 직전 COMPLETE 금액 + 기준 날짜(01 §14 #19), 부분 금액 · 증감 없음 |
| 행 펼침      | `/snapshots/{date}` | 펼칠 때만 조회(다시 펼치면 캐시). 자산군 → 자산 → 종목. 누락 행은 조회하지 않고 안내만                          |

- URL: `?month=yyyy-MM` · `?date=yyyy-MM-dd`. `date`가 있으면 그 달을 열고 그 날짜 행을 펼친 뒤 한 번 스크롤한다(대시보드 추이 차트 점 클릭). 형식이 틀린 값은 무시하고 최근 기록 달을 연다.
- 기록이 하나도 없으면 대시보드와 같은 `NoRecordsNotice`.
- 아직 연결하지 않은 것: [원인 보기](S-04), 이번 달 요약 → 월간 결산(S-03).

## 규칙

- 금액·등락률 표시는 항상 `shared/lib/format.ts`의 `formatKRW` / `formatSignedKRW` / `formatPercent`(비율 입력 0.0523) / `formatRate`(퍼센트 값 입력 5.23 — 백엔드 changeRate · ratio)를 사용한다. 값이 `null`/`undefined`/`NaN`/`Infinity`이면 `0`이 아니라 `—`로 표시한다.
- 날짜는 `formatShortDate`("10/29 (목)", 기준 날짜와 해가 다르면 연도를 붙임), 기록 상태는 `snapshotStatusLabel`을 쓴다.
- 서버 데이터는 TanStack Query로 가져온다. 요청은 `shared/api/client.ts`의 axios 인스턴스로 보낸다(토큰 첨부 · 401 refresh 포함). 응답 envelope 타입은 `shared/api/types.ts`의 `ApiResponse`.
- 클래스명 조합은 `shared/lib/cn.ts`의 `cn()` (clsx + tailwind-merge)을 사용한다.
- 의미 있는 색은 `src/index.css`의 `@theme`에 정의된 `rise`(상승)·`fall`(하락)·`flat`(변동 없음)·`partial`(데이터 일부 누락/경고) 네 개만 쓴다. 자산군처럼 상승 · 하락과 무관한 구분은 무채색(`neutral`) 단계로 나눈다.
- 사용자 문구에 원인을 단정하는 표현("때문에", "~로 인해")이나 매매 권유 · "추천"을 쓰지 않는다(AGENTS 5-D).

## 미확정 사항

- 회원가입 경로는 아직 만들지 않았다. 필요 여부와 URL이 확정되면 추가한다.
- PWA·웹 푸시 설정은 알림 모듈(`features/notification`)에서 추후 구성한다.
- 화면별 DTO 타입은 그 화면을 만들 때 정의한다(자산 기록 조회는 `features/asset/api.ts`).
