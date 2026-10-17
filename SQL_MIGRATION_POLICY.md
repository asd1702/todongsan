# SQL Migration Policy

## 1. 목적

DB 스키마 변경 이력을 추적하고 신규 개발 환경 구성 및 운영 DB 재구성을 가능하게 하기 위함.

---

## 2. 공통 규칙

* Flyway migration은 각 서비스의 `src/main/resources/db/migration`에서만 관리한다.
* DB 스키마 변경 시 해당 서비스에 새 Flyway migration을 추가한다.
* 적용된 Flyway migration은 수정하지 않는다.
* `DROP TABLE` 기반 migration을 금지한다.
* Flyway가 version/history를 관리하므로 migration SQL 자체에 재실행 idempotency를 요구하지 않는다.

### Baseline freeze

Flyway 전환 안정화가 merge된 뒤에는 `V1__baseline.sql`을 변경하지 않는다. 이후 schema 변경은 반드시 새 `V2__...`, `V3__...` migration으로 추가한다. 이미 적용된 migration의 checksum은 불변이다.

전환 중 과거 V1 checksum을 가진 DB의 일회성 복구 절차는 [Flyway pre-freeze V1 recovery](docs/infra/FLYWAY_PRE_FREEZE_RECOVERY.md)를 따른다. `flyway repair`는 자동 startup flow에 넣지 않는다.

---

## 3. 파일명 규칙

형식:

V1__baseline.sql
V2__add_prediction_attempt_no.sql
V3__add_market_comment.sql

예시:

V1__baseline.sql
V2__add_member_status.sql

---

## 4. 적용 순서

신규 환경 구성 시 낮은 번호부터 순서대로 적용한다.

`baseline-on-migrate` is disabled by default. Enable `FLYWAY_BASELINE_ON_MIGRATE=true` only once when adopting an existing non-empty legacy database that has no `flyway_schema_history`; do not use it for normal startup.

예시:

V1
↓
V2
↓
V3

---

## 5. 도메인별 관리

각 서비스는 자신의 source tree에서 migration SQL을 관리한다.

예시:

market-service/src/main/resources/db/migration
member-point-service/src/main/resources/db/migration
battle-service/src/main/resources/db/migration
insight-reputation/src/main/resources/db/migration

다른 도메인의 migration SQL을 수정하지 않는다.

## 6. 주의사항

* Flyway는 서비스 startup 시 해당 datasource에 적용한다.
* 새 migration version은 해당 서비스의 최신 version 기준으로 정한다.
* 위험한 DDL(DROP COLUMN, 대량 DELETE, 컬럼 타입 변경 등)은 팀 합의 후 진행한다.
