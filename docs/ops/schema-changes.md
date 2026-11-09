# 스키마 변경 기록 (prod 수동 반영)

> 관련: 05 §1.1 변경 이력, `application-prod.yml` `ddl-auto: validate`
> 마이그레이션 도구(Flyway · Liquibase)를 쓰지 않는다. local은 `ddl-auto: update`, test는 `create-drop`이라 엔티티 변경이 자동 반영되지만, **prod는 `validate`라 아래 SQL을 배포 전에 직접 실행하지 않으면 기동이 실패한다.**
> 엔티티 · 컬럼 변경은 05를 먼저 고친 뒤 이 표에 SQL을 남긴다. 설계 문서(01~05)는 여기서 고치지 않는다.

## 첫 배포 (아직 prod DB 없음 — 15주차 AWS EC2 / RDS, 01 §10)

- 첫 배포 전에 생긴 변경은 ALTER가 필요 없다 — 처음 만드는 RDS 스키마에 이미 포함된다. 아래 표에서 "불필요(첫 배포 전)"로 표시한다.
- prod는 `validate`라 빈 RDS로는 기동하지 않는다. 첫 배포 때 엔티티 기준 초기 DDL을 한 번 만들어 RDS에 적용한 뒤 `validate`로 기동한다(절차는 배포 작업 때 정한다).

## 반영 절차 (prod DB가 생긴 뒤의 변경)

1. 배포 전 prod DB에 아래 SQL을 실행한다.
2. 실행 결과(`DESC <테이블>`)로 컬럼 · 타입을 확인한다.
3. "prod 적용" 칸에 적용 날짜를 적는다.

## 변경 목록

| 날짜 | 대상 테이블 | 내용 | 근거 | prod 적용 |
|---|---|---|---|---|
| 2026-10-28 | asset_detail_snapshots | `quantity` · `price` 추가 — 종목 행의 평가 수량 · 단가 (시장가격 영향 계산 기준) | 05 §4.7 · §4.8, 01 §14 #1 · #2 | 불필요(첫 배포 전 변경, 초기 스키마에 포함) |
| 2026-11-05 | detection_rules | 신규 테이블 — 감지 규칙 · 기본 임계치 · 허용 범위 · 발송 상한. 초기 6행은 `data.sql`(id 1~6 고정, 기동마다 덮어씀). 시각 컬럼 없음 | 05 §8.1, FR-DT-01 | 불필요(첫 배포 전 변경, 초기 스키마에 포함) |
| 2026-11-05 | user_detection_settings | 신규 테이블 — 규칙별 사용자 오버라이드. UNIQUE `uk_user_detection_settings_user_rule (user_id, detection_rule_id)`, `target_scope` · `channel_preference` JSON | 05 §8.2, FR-DT-02 | 불필요(첫 배포 전 변경, 초기 스키마에 포함) |
| 2026-11-05 | user_settings | 신규 테이블 — 사용자당 1행(PK = user_id). 가입 시 만들지 않고 처음 조회할 때 기본값으로 생성 | 05 §3.2 | 불필요(첫 배포 전 변경, 초기 스키마에 포함) |
| 2026-11-05 | stock_event_analyses | 신규 테이블 — 종목 · 날짜 단위 공유 분석. UNIQUE `uk_stock_event_analyses_ticker_date`, 인덱스 `idx_stock_event_analyses_status_created (analysis_status, created_at)`. PENDING 단계 컬럼 8개 NULL 허용 | 05 §5.1 · §10, FR-CA-10 | 불필요(첫 배포 전 변경, 초기 스키마에 포함) |
| 2026-11-05 | user_market_events | 신규 테이블 — 사용자 이벤트. UNIQUE `uk_user_market_events_user_analysis`, 인덱스 `idx_user_market_events_user_date (user_id, event_date)` — 05 §10의 DESC 대신 ASC | 05 §5.3 · §10, FR-CA-12 | 불필요(첫 배포 전 변경, 초기 스키마에 포함) |
| 2026-11-05 | detection_events | 신규 테이블 — 감지 기록. UNIQUE `uk_detection_events_user_key (user_id, event_key)`, `payload` JSON NOT NULL, `user_market_event_id` NULL 허용 | 05 §8.3 · §10, FR-DT-06 | 불필요(첫 배포 전 변경, 초기 스키마에 포함) |
| 2026-11-09 | event_sources | 신규 테이블 — 공유 분석의 근거. UNIQUE `uk_event_sources_analysis_external (analysis_id, external_id)`, 인덱스 `idx_event_sources_analysis_published (analysis_id, published_at)`. `published_at` NULL 허용(시각 미제공 공시). `created_at` · `updated_at`은 05 §5.2에 없는 감사 컬럼 | 05 §5.2 · §10, FR-CA-01 · 02 · 04 | 불필요(첫 배포 전 변경, 초기 스키마에 포함) |

### 2026-11-09 — event_sources (신규)

첫 배포 전 변경이라 실행하지 않는다. 초기 DDL을 만들 때 엔티티와 같은지 대조하는 참고용이다(Hibernate 6 · MySQL 8.0 기준).

```sql
CREATE TABLE event_sources (
    id             BIGINT        NOT NULL AUTO_INCREMENT,
    analysis_id    BIGINT        NOT NULL,
    source_type    ENUM('DISCLOSURE','MACRO','NEWS') NOT NULL,   -- @Enumerated STRING → Hibernate 6 MySQL 네이티브 ENUM(값 알파벳순)
    title          VARCHAR(500)  NOT NULL,
    source_name    VARCHAR(100)  NOT NULL,
    published_at   DATETIME(6)   NULL,
    url            VARCHAR(1000) NOT NULL,
    snippet        TEXT          NULL,
    relevance_rank INT           NULL,
    external_id    VARCHAR(200)  NOT NULL,
    created_at     DATETIME(6)   NOT NULL,
    updated_at     DATETIME(6)   NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_event_sources_analysis_external UNIQUE (analysis_id, external_id),
    INDEX idx_event_sources_analysis_published (analysis_id, published_at),
    CONSTRAINT fk_event_sources_analysis FOREIGN KEY (analysis_id) REFERENCES stock_event_analyses (id)
);
```

- FK 이름은 Hibernate가 자동으로 정한다(엔티티에 이름 지정 없음) — 초기 DDL을 만들 때 실제 이름으로 맞춘다.
- 되돌리기: `DROP TABLE event_sources;`

### 2026-10-28 — asset_detail_snapshots `quantity` · `price`

```sql
ALTER TABLE asset_detail_snapshots
    ADD COLUMN quantity DECIMAL(18,4) NULL AFTER amount,
    ADD COLUMN price    DECIMAL(18,2) NULL AFTER quantity;
```

- prod DB가 생기기 전 변경이라 실행하지 않는다. 운영 DB가 생긴 뒤 같은 종류의 변경이 생기면 이 형식을 따른다.
- 기존 행은 NULL로 남긴다(소급 채움 없음). 기준일 행이 NULL이면 그날 시장가격 영향은 NULL이다(전환기 1회, 05 §4.7).
- 되돌리기: `ALTER TABLE asset_detail_snapshots DROP COLUMN price, DROP COLUMN quantity;`
