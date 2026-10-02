# assetory frontend

자산 가계부 assetory의 프론트엔드. 현재는 백엔드 연동 전 단계로, 라우팅과 폴더 구조만 갖춘 골격 상태다.

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
├─ pages/      화면별 폴더 (현재는 제목만 있는 플레이스홀더)
├─ features/   auth, asset, analysis, goal, deposit, notification (모듈 준비 중, .gitkeep만 존재)
└─ shared/
   ├─ api/          client.ts (axios 인스턴스)
   ├─ components/   layout/ (BottomTabLayout), ui/
   ├─ hooks/
   └─ lib/          cn.ts, format.ts
```

의존 방향: `pages → features → shared`. `features/*`는 아직 비어 있다.

경로 별칭 `@/*`는 `src/*`를 가리킨다 (`tsconfig.app.json`, `vite.config.ts` 양쪽에 설정).

## 라우트

| 화면 | URL |
|---|---|
| 로그인 | `/login` |
| S-01 대시보드 | `/` |
| S-02 자산 기록 | `/records` (날짜는 `?date=YYYY-MM-DD`) |
| S-03 월간 결산 | `/reports`, `/reports/:yearMonth` |
| S-04 원인 분석 | `/analysis/:stockCode/:date` |
| S-05 종목 상세 | `/stocks/:stockCode` |
| S-06 목표 | `/goals`, `/goals/:goalId` |
| S-07 예적금 비교 | `/deposits/compare` |
| S-08 알림 | `/notifications` |
| S-09 알림 설정 | `/settings/notifications` |
| S-10 계좌 연동 | `/connect` |
| S-11 온보딩 | `/onboarding` (하단 탭 없음) |
| 404 | 그 외 모든 경로 |

URL은 직접 문자열로 만들지 말고 `app/paths.ts`의 함수를 사용한다. S-01~S-10은 하단 탭 레이아웃(`shared/components/layout/BottomTabLayout.tsx`) 안에서 렌더링되며, 탭은 홈·기록·결산·목표·알림 다섯 개다. `/login`, `/onboarding`은 탭 레이아웃 밖에 있다.

인증 가드는 아직 없다. 모든 경로에 누구나 접근할 수 있다.

## 규칙

- 금액·등락률 표시는 항상 `shared/lib/format.ts`의 `formatKRW` / `formatSignedKRW` / `formatPercent`를 사용한다. 값이 `null`/`undefined`/`NaN`/`Infinity`이면 `0`이 아니라 `—`로 표시한다.
- 서버 데이터를 다루게 되면 TanStack Query로 가져온다. `shared/api/client.ts`의 axios 인스턴스를 기반으로 하되, 아직 토큰 첨부·401 처리·refresh 로직은 없다.
- 클래스명 조합은 `shared/lib/cn.ts`의 `cn()` (clsx + tailwind-merge)을 사용한다.
- 색 토큰은 `src/index.css`의 `@theme`에 정의된 `rise`(상승)·`fall`(하락)·`flat`(변동 없음)·`partial`(데이터 일부 누락/경고) 네 개만 쓴다.

## 미확정 사항

- 인증 방식과 토큰 refresh 계약 (저장 위치, 만료 처리 등)이 정해지지 않았다.
- 회원가입 경로는 아직 만들지 않았다. 필요 여부와 URL이 확정되면 추가한다.
- PWA·웹 푸시 설정은 알림 모듈(`features/notification`)에서 추후 구성한다.
- API 응답 envelope, DTO, 에러 타입은 백엔드 계약이 확정된 뒤 정의한다.
