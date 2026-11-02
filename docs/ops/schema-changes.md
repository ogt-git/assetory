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

### 2026-10-28 — asset_detail_snapshots `quantity` · `price`

```sql
ALTER TABLE asset_detail_snapshots
    ADD COLUMN quantity DECIMAL(18,4) NULL AFTER amount,
    ADD COLUMN price    DECIMAL(18,2) NULL AFTER quantity;
```

- prod DB가 생기기 전 변경이라 실행하지 않는다. 운영 DB가 생긴 뒤 같은 종류의 변경이 생기면 이 형식을 따른다.
- 기존 행은 NULL로 남긴다(소급 채움 없음). 기준일 행이 NULL이면 그날 시장가격 영향은 NULL이다(전환기 1회, 05 §4.7).
- 되돌리기: `ALTER TABLE asset_detail_snapshots DROP COLUMN price, DROP COLUMN quantity;`
