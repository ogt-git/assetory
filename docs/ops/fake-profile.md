# fake 프로필 — 데모 계정으로 화면 확인하기

> 관련: C1(fake 프로필 + 데모 계정), 05 §4.7 · §4.8(asset_snapshots · asset_detail_snapshots), 01 §14 #19(누락일 표시)
> 실제 한투 · CODEF 키나 계좌 없이, 며칠 치 자산 기록이 쌓인 상태를 브라우저에서 확인하기 위한 **로컬 전용** 실행 방식이다.

---

## 1. 실행

Docker가 켜져 있어야 한다(MySQL · Redis를 Testcontainers로 띄운다).

```bash
cd backend && ./gradlew bootTestRun
```

```bash
cd frontend && npm run dev
```

- 백엔드는 `http://localhost:8080`, 프론트는 `http://localhost:5173`(`/api`는 8080으로 프록시).
- 로그에 `[FakeSeed] 데모 데이터 준비 완료`가 찍힌 뒤 로그인한다. 서버는 그 전에 이미 요청을 받지만, 시드가 끝나기 전에는 계정이 없다.
- 기동부터 시드 완료까지 약 80초(컨테이너 약 20초 · 스프링 초기화 · 시드 약 27초, 2026-10-30 측정).
- 일반 `./gradlew bootRun`(local 프로필)과 같은 8080 포트를 쓰므로 **둘 중 하나만** 띄운다.
- Swagger: `http://localhost:8080/swagger-ui/index.html` (fake에서는 켜져 있다).

### 종료

- 터미널에서 실행했으면 Ctrl+C로 끈다. 컨테이너는 앱 JVM이 끝나면 자동으로 정리된다.
- Windows에서 Gradle 작업만 끊기고 앱이 남아 8080을 계속 잡는 경우가 있었다. 포트가 안 풀리면 8080을 잡은 java 프로세스를 직접 종료한다.

```powershell
Get-NetTCPConnection -LocalPort 8080 -State Listen | Select-Object -ExpandProperty OwningProcess -Unique | ForEach-Object { Stop-Process -Id $_ -Force }
```

## 2. 매번 초기화

- 기동할 때마다 **새 MySQL · Redis 컨테이너**를 만들고 데모 데이터를 다시 넣는다. 화면에서 바꾼 데이터(수기 자산, 연동, 새로 만든 계정)는 재기동하면 사라진다.
- docker-compose의 로컬 DB(`docker compose up -d`)는 쓰지도 바꾸지도 않는다.
- 날짜는 **기동한 날의 직전 평일**을 마지막 기록일로 해서 거꾸로 만든다. 언제 띄워도 최근 데이터처럼 보인다. 평일을 모두 거래일로 보고 공휴일은 무시한다.

## 3. 데모 계정

비밀번호는 모든 계정 공통 `Demo1234!` — **로컬 전용 더미 값**이다. 실제 서비스 · 다른 환경에 쓰지 않는다.

| 계정 | 상황 | 자산 | 기록 | 확인할 화면 상태 |
|---|---|---|---|---|
| `demo1@assetory.test` | 기본(1년 이상) | 삼성증권 계좌(예수금 + 005930 · 000660 · 069500 · 035720), 정기예금, 현금 | 400 평일 | 전일 · 전월 · 전년 증감, 1개월 · 1년 · 전체 추이, 급등 · 급락일, 추가 매수 · 전량 매도, 누락일(PARTIAL 3 · FAILED 1) |
| `demo2@assetory.test` | 최근 누락 | 삼성증권 계좌(연동 상태 AUTH_FAILED), 현금 | 30 평일 | 최근 2일 PARTIAL — 직전 COMPLETE 금액과 기준 날짜 표시(#19), 부분 금액 숨김 |
| `demo3@assetory.test` | 연동 직후 | 삼성증권 계좌(예수금 + 035720) | 1일 | 비교 대상 없음(전일 대비 `available=false`) |
| `demo4@assetory.test` | 빈 계정 | 없음 | 없음 | 기록 없음 · 연동 유도 |

### demo1 이벤트 날짜

마지막 기록일에서 **몇 평일 전**인지로 정해져 있다. 실제 날짜는 기동일에 따라 바뀐다.

| 평일 전 | 이벤트 | 기록 상태 |
|---|---|---|
| 300 | 069500 시세 수집 실패 | PARTIAL |
| 200 | 005930 전 거래일 대비 +7% | COMPLETE |
| 150 | 035720 시세 수집 실패 | PARTIAL |
| 120 | 005930 10주 추가 매수(20주 → 30주) | COMPLETE |
| 90 | 증권 계좌 · 수기 자산 모두 실패 | FAILED |
| 60 | 000660 5주 전량 매도 | COMPLETE |
| 45 | 035720 전 거래일 대비 −8% | COMPLETE |
| 20 | 증권 계좌 수집 실패 | PARTIAL |

- 달이 바뀐 첫 평일마다 현금이 50만 원 늘어난다 — "기타 자산 변화"가 보이게 하기 위한 값이다.
- FAILED 날은 수기 자산 값도 비워서 만들었다. 실제 배치에서는 수기 자산 값이 항상 있으므로 수기 자산이 있는 사용자는 FAILED가 되지 않는다. 화면의 FAILED 표시 확인용이다.
- 시세 수집 실패로 둔 (종목, 날짜)는 `stock_prices`에도 없다.

## 4. 가짜 외부 연동

| 대상 | 동작 |
|---|---|
| 한투 | E2E용 가짜 서버(`FakeKisServer`)를 띄우고 `assetory.kis.base-url`을 그 주소로 바꾼다. 실제 `KisApiClient` · 토큰 관리를 그대로 거친다. 시세는 데모 시세와 같다 |
| CODEF | `FakeCodefApiClient`가 고정 응답을 준다. 연동은 항상 성공한다(새로 연동한 계좌는 예수금 100만 원 + 005930 10주 + 069500 5주). demo1 · demo3 수동 새로고침은 마지막 기록과 같은 구성을 받는다 |
| 메일 | 보내지 않고 인증코드를 로그에 출력한다(`[LocalMail]`). 화면에서 가입 · 비밀번호 재설정을 시험할 때 로그에서 코드를 본다 |
| 일일 배치(16:00) | 꺼져 있다. 기록은 시드가 넣는다 |

- **가격은 가짜다.** 종목코드 · 이름만 실제 값이고, 시세는 고정 시드로 만든 값이라 실제 가격과 관계없다.
- 계좌번호 · connectedId · 키는 모두 더미 값이다.
- demo2는 인증 만료 계좌라 수동 새로고침 대상이 아니다(`CONNECTION_NOT_FOUND`, 기존 규칙).

## 5. 구성 — 운영 jar에 포함되지 않음

fake 코드와 설정은 **테스트 소스에만** 있다. `./gradlew bootJar` 결과물에 들어가지 않는다(2026-10-30 jar 목록 검사로 확인).

| 파일 | 역할 |
|---|---|
| `backend/src/test/java/.../fake/FakeAssetoryApplication.java` | `bootTestRun` 진입점. 원래 앱에 아래 설정을 붙이고 `fake` 프로필만 켠다(`local` · `application-local.yml`은 읽지 않음) |
| `FakeInfraConfig` | MySQL · Redis 컨테이너, 로그 출력 메일 |
| `FakeKisConfig` | 가짜 한투 서버 + base-url 교체 |
| `FakeCodefConfig` · `FakeCodefApiClient` | 가짜 CODEF(`@Primary`) |
| `FakeSeedConfig` · `DemoDataSeeder` | 기동 직후 데모 데이터 시드. 기록은 실제 `SnapshotSaveService`로 날짜 오름차순 저장 |
| `DemoScenario` | 날짜 · 시세 · 수량 · 수집 결과를 만드는 순수 계산(`DemoScenarioTest`로 고정) |
| `backend/src/test/resources/application-fake.yml` | 더미 키, 쿠키 Secure false, Swagger 켬, 일일 배치 끔 |

- fake 설정은 모두 `@TestConfiguration`이라 기존 `@SpringBootTest`의 컴포넌트 스캔에 잡히지 않는다(`FakeProfileIsolationTest`).
- 주의: 테스트 소스에 `@Component` · `@Configuration` 클래스를 새로 만들면 `bootTestRun` 실행 때 스캔에 잡힌다. 테스트 전용 설정은 `@TestConfiguration`으로 만든다.
