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

## 규칙

- 금액·등락률 표시는 항상 `shared/lib/format.ts`의 `formatKRW` / `formatSignedKRW` / `formatPercent`를 사용한다. 값이 `null`/`undefined`/`NaN`/`Infinity`이면 `0`이 아니라 `—`로 표시한다.
- 서버 데이터는 TanStack Query로 가져온다. 요청은 `shared/api/client.ts`의 axios 인스턴스로 보낸다(토큰 첨부 · 401 refresh 포함). 응답 envelope 타입은 `shared/api/types.ts`의 `ApiResponse`.
- 클래스명 조합은 `shared/lib/cn.ts`의 `cn()` (clsx + tailwind-merge)을 사용한다.
- 색 토큰은 `src/index.css`의 `@theme`에 정의된 `rise`(상승)·`fall`(하락)·`flat`(변동 없음)·`partial`(데이터 일부 누락/경고) 네 개만 쓴다.

## 미확정 사항

- 회원가입 경로는 아직 만들지 않았다. 필요 여부와 URL이 확정되면 추가한다.
- PWA·웹 푸시 설정은 알림 모듈(`features/notification`)에서 추후 구성한다.
- 화면별 DTO 타입은 그 화면을 만들 때 정의한다.
