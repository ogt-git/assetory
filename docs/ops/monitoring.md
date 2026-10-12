# 운영 지표 — 접근 · 수집 · 성능 기록

> 관련: 01 §8(운영 지표 — 관리자 영역), 04 FR-OP-01 · FR-OP-08 · NFR-SE-08 · NFR-PF-01, 05 §12
> 운영 지표는 Grafana로만 본다. 제품 안에 관리자 화면을 만들지 않고, 프론트에서 actuator를 프록시하지 않는다(FR-OP-08).

---

## 1. 구성

```
앱(API 포트 8080) ── 사용자 API (/api/**)
앱(관리 포트 8082) ── /actuator/health · /actuator/prometheus  ← Prometheus가 15초마다 수집 ← Grafana가 조회
```

| 항목 | 값 | 설정 |
|---|---|---|
| 관리 포트 | `8082` | `management.server.port` (환경변수 `MANAGEMENT_SERVER_PORT`) |
| 노출 엔드포인트 | `health`, `prometheus`만 (`/actuator` 링크 목록도 닫음) | `management.endpoints.web.exposure.include`, `discovery.enabled: false` |
| health 상세 | 표시 안 함 (`{"status":"UP"}`만) | `management.endpoint.health.show-details: never` |
| mail health | 끔 — SMTP 상태로 앱 전체를 DOWN 판정하지 않음 | `management.health.mail.enabled: false` |
| 공통 태그 | `application="assetory"` | `management.metrics.tags.application` |
| HTTP 응답시간 | histogram + SLO 버킷 100ms · 300ms · 500ms · 1s | `management.metrics.distribution.*` (`MANAGEMENT_HTTP_SLO`) |
| DB 접근 시간 | repository · method별 histogram | `spring.data.repository.invocations` |

API 포트(8080)에는 `/actuator/**`가 없다. 비인증 요청은 기존 규칙대로 401, 인증 요청은 404.

## 2. 외부 비노출 전제 (NFR-SE-08)

- 관리 포트는 **인증 없이** 열려 있다(`SecurityConfig.actuatorFilterChain`). 보호는 **포트 분리 + 네트워크**로 한다.
- 운영(EC2): 보안그룹에서 **관리 포트(8082)를 외부에 열지 않는다.** Prometheus는 같은 내부 네트워크에서만 수집한다.
- 리버스 프록시(Nginx 등)를 두면 API 포트만 프록시하고 관리 포트는 연결하지 않는다.
- 관리 포트를 API 포트와 같게 설정하면 actuator 요청도 JWT 인증을 받는다(안전한 쪽으로 동작). 그래도 같은 포트로 두지 않는다.
- 메트릭 태그에는 userId · 종목코드 · 원문 URL · 토큰 · 계좌번호 · 이메일을 넣지 않는다(NFR-SE-07, 카디널리티). HTTP `uri` 태그는 경로 템플릿(`/api/assets/manual/{assetId}`)으로 남는다.

## 3. 로컬 실행

1. 루트 `.env`에 `GRAFANA_ADMIN_USER`, `GRAFANA_ADMIN_PASSWORD`를 넣는다(`.env.example` 참고).
2. 앱과 모니터링을 띄운다.

```bash
docker compose --profile monitoring up -d
```

```bash
cd backend && ./gradlew bootRun
```

| 대상 | 주소 |
|---|---|
| 앱 지표 원문 | http://localhost:8082/actuator/prometheus |
| 앱 health | http://localhost:8082/actuator/health |
| Prometheus | http://localhost:9090 (Status → Targets에서 `assetory` UP 확인) |
| Grafana | http://localhost:3000 → Dashboards → Assetory → **Assetory Overview** |

- `docker compose up -d`(프로필 없이)는 MySQL · Redis만 띄운다.
- Prometheus는 `host.docker.internal:8082`를 수집한다. 관리 포트를 바꾸면 `monitoring/prometheus/prometheus.yml`의 `targets`도 바꾼다.
- Linux Docker는 `extra_hosts: host-gateway`로 `host.docker.internal`을 만든다. 앱이 `127.0.0.1`에만 바인딩돼 있으면 수집되지 않는다.

### 대시보드 패널 (Assetory Overview)

| 패널 | 용도 |
|---|---|
| 애플리케이션 상태 | Prometheus 수집 성공 여부(`up`) |
| HTTP 요청 수 · 5xx 비율 | 트래픽 · 서버 오류 |
| HTTP 응답시간 p95 / p99 (uri별) | NFR-PF-01 판정 (500ms 선 표시) |
| Repository 메서드 p95 | DB 접근 시간 개선 전후 비교 |
| HikariCP 커넥션 | 활성 · 대기 · 최대 |
| JVM 힙 · GC 일시정지 | 메모리 · GC |

배치 · 외부 API · LLM · 분석 품질 · 알림 패널(FR-OP-07)과 05 §12 커스텀 메트릭은 해당 기능을 구현할 때 추가한다.

## 4. 자주 쓰는 PromQL

```promql
# HTTP p95 (uri별, 5분) — NFR-PF-01: 주요 조회 API 0.5초 이내
histogram_quantile(0.95, sum by (le, method, uri) (rate(http_server_requests_seconds_bucket{application="assetory", uri!~"/actuator.*"}[5m])))

# 특정 API만
histogram_quantile(0.95, sum by (le) (rate(http_server_requests_seconds_bucket{application="assetory", uri="/api/assets"}[5m])))

# SLO 500ms 이내 비율
sum(rate(http_server_requests_seconds_bucket{application="assetory", uri="/api/assets", le="0.5"}[5m]))
  / sum(rate(http_server_requests_seconds_count{application="assetory", uri="/api/assets"}[5m]))

# Repository 메서드 p95
histogram_quantile(0.95, sum by (le, repository, method) (rate(spring_data_repository_invocations_seconds_bucket{application="assetory"}[5m])))

# 5xx 오류율
sum(rate(http_server_requests_seconds_count{application="assetory", status=~"5.."}[5m]))
  / sum(rate(http_server_requests_seconds_count{application="assetory"}[5m]))

# Redis 명령 지연 (Lettuce 자동 수집, histogram 없음 → 명령별 평균 · 최대)
sum by (command) (rate(lettuce_command_completion_seconds_sum{application="assetory"}[5m]))
  / sum by (command) (rate(lettuce_command_completion_seconds_count{application="assetory"}[5m]))
max by (command) (lettuce_command_completion_seconds_max{application="assetory"})
```

## 5. 성능 기록표

개선 작업 전후를 같은 조건으로 측정해 남긴다. 부하 테스트는 하지 않는다(FR-OP-08) — 로컬에서 같은 요청을 같은 횟수로 보내 비교한다.

- 측정 조건에는 데이터 규모(사용자 · 보유 종목 · 스냅샷 수), 요청 횟수, 측정 구간을 적는다.
- p95는 위 PromQL을 측정 구간에 맞춰 `[5m]` 등으로 조회한 값이다.

| 날짜 | 커밋 | 대상 API / 쿼리 | 측정 조건 | p95 전 | p95 후 | 변경 내용 |
|---|---|---|---|---|---|---|
| _(예시)_ 2026-11-02 | `abc1234` | `GET /api/assets` | 사용자 1명 · 보유 30종목, 100회 순차 요청, 5분 구간 | 420ms | 180ms | _(예시)_ holdings 조회 N+1 → fetch join |
