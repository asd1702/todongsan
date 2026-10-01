# Todongsan Local Infrastructure Guide

## 1. 목적

본 문서는 Todongsan 프로젝트의 로컬 개발 환경 구성을 설명한다.

모든 개발자는 동일한 Docker Compose 설정을 사용하여 서비스별 독립 MySQL 환경을 구축한다.

---

## 2. 사전 준비

필수 설치 항목

- Git
- Docker Desktop
- JDK 17
- IntelliJ IDEA

Docker Desktop이 정상 실행 중인지 확인한다.

```bash
docker --version
docker compose version
```

---

## 3. 프로젝트 구조

```text
todongsan
│
├── infra
│   ├── docker-compose.yml
│
├── market-service
├── member-point-service
├── battle-service
└── insight-reputation-service
```

---

## 4. Docker MySQL 실행

infra 디렉토리로 이동한다.

```bash
cd infra
```

Docker Compose 실행

```bash
docker compose up -d
```

실행 확인

```bash
docker ps
```

정상 실행 시 다음 컨테이너가 표시된다.

```text
member-point-mysql
market-mysql
battle-mysql
insight-mysql
```

---

## 5. Docker MySQL 종료

```bash
cd infra

docker compose down
```

---

## 6. Docker MySQL 데이터 초기화 주의

EC2와 통합 로컬 검증 환경에서 MySQL 데이터는 Docker volume에 저장된다.

```text
compose volume name: memberpoint-mysql-data, market-mysql-data, battle-mysql-data, insight-mysql-data
```

일반 종료 또는 재기동 시에는 volume을 보존해야 한다.

```bash
cd infra
docker compose down
docker compose up -d
```

### 주의

아래 명령은 MySQL 데이터 volume 삭제 위험이 있으므로 일반 개발/배포 환경에서 사용하지 않는다.

```bash
docker compose down -v
docker volume rm
docker volume prune
```

스키마를 처음부터 다시 만들 필요가 있는 개발 초기 상황이라도, 먼저 백업 필요 여부를 확인한다.

---

## 7. MySQL 접속

컨테이너 접속

```bash
docker exec -it market-mysql bash
```

MySQL 접속

```bash
mysql -u market_user -p market
```

비밀번호

```text
market-local-password
```

---

## 8. 생성되는 Database

각 컨테이너는 자신의 Database 하나만 자동 생성한다.

```text
member-point-mysql → memberpoint
market-mysql       → market
battle-mysql       → battle
insight-mysql      → insight
```

확인

```sql
SHOW DATABASES;
```

---

## 9. Schema 초기화

Docker는 database와 계정만 생성한다. 테이블 생성·변경은 각 Spring Boot 서비스의 Flyway migration이 담당한다. 빈 DB에서 서비스가 처음 기동될 때 `V1__baseline.sql`이 적용되며, 기존 schema에는 Flyway가 baseline history를 기록한 뒤 후속 migration만 적용한다.

---

## 10. Market Service 로컬 실행

### application-local.yml

```yaml
spring:
  datasource:
    driver-class-name: com.mysql.cj.jdbc.Driver
    url: jdbc:mysql://localhost:3308/market?serverTimezone=Asia/Seoul&characterEncoding=UTF-8
    username: market_user
    password: market-local-password
```

### 실행

```bash
./gradlew bootRun --args='--spring.profiles.active=local'
```

또는 IntelliJ Run Configuration

```text
SPRING_PROFILES_ACTIVE=local
```

---

## 11. RDS 사용 정책

현재 비용 문제로 RDS는 사용하지 않는다.

현재 배포 기준은 다음과 같다.

```text
EC2 1대 + Docker Compose + Docker MySQL
```

기존 `.env.rds.example` 또는 `docker-compose.rds.yml` 파일은 향후 RDS 재도입 시 참고용으로 남겨둘 수 있다. 이번 EC2 Docker MySQL 배포에서는 사용하지 않는다.

---

## 12. 환경 변수 관리

다음 정보는 Git에 커밋하지 않는다.

```text
DB_HOST
DB_PORT
MEMBERPOINT_DB_USERNAME
MEMBERPOINT_DB_PASSWORD
MARKET_DB_USERNAME
MARKET_DB_PASSWORD
BATTLE_DB_USERNAME
BATTLE_DB_PASSWORD
INSIGHT_DB_USERNAME
INSIGHT_DB_PASSWORD
```

권장 관리 방법

- .env
- GitHub Secret
- AWS Parameter Store

---

## 13. Troubleshooting

### 포트 충돌 확인

Windows

```bash
netstat -ano | findstr 3308
```

Mac / Linux

```bash
lsof -i :3308
```

### 컨테이너 상태 확인

```bash
docker ps
```

### 컨테이너 로그 확인

```bash
docker logs market-mysql
```

### MySQL 접속 확인

```bash
docker exec -it market-mysql mysql -u market_user -p market
```

---

## 14. 개발 원칙

1. 모든 개발자는 동일한 Docker Compose를 사용한다.
2. Database 생성 및 변경은 SQL 스크립트로 관리한다.
3. 스키마 변경은 SQL 파일 수정 후 Pull Request로 공유한다.
4. 로컬 개발은 Docker MySQL을 사용한다.
5. AWS RDS는 통합 테스트 및 운영 검증 용도로 사용한다.
6. 환경 변수(.env)는 Git에 커밋하지 않는다.

---

## 15. Local MSA Docker Compose

Local integrated Docker execution uses the infra compose plus the root app compose.

```text
infra/docker-compose.yml
  - MySQL only
  - creates todongsan-network
  - runs member-point-mysql, market-mysql, battle-mysql, insight-mysql

docker-compose.yml
  - app services only
  - api-gateway
  - member-point-service
  - battle-service
  - market-service
  - insight-reputation-service
  - uses external todongsan-network
```

`docker-compose.local.yml` is no longer used. The standard local integrated app compose file is root `docker-compose.yml`.

Run order:

```bash
cp .env.example .env
docker compose -f infra/docker-compose.yml up -d
docker network inspect todongsan-network
docker compose config
docker compose build
docker compose up -d
docker compose ps
```

Windows PowerShell:

```powershell
Copy-Item .env.example .env
```

Root Docker Compose uses `.env` for variable substitution. The `.env` file is local-only and must not be committed.

Step-by-step startup:

```bash
docker compose -f infra/docker-compose.yml up -d
docker network inspect todongsan-network
docker ps

docker compose up -d member-point-service market-service
docker logs --tail=100 member-point-service
docker logs --tail=100 market-service
curl http://localhost:8082/actuator/health
curl http://localhost:8082/api/v1/markets

docker compose up -d --no-deps api-gateway
docker logs --tail=100 api-gateway
curl http://localhost:9000/api/v1/markets

docker compose up -d battle-service insight-reputation-service
docker logs --tail=100 battle-service
docker logs --tail=100 insight-reputation-service
docker compose ps
```

Smoke test examples:

```bash
curl http://localhost:9000/api/v1/markets
curl http://localhost:8082/actuator/health
curl http://localhost:8082/api/v1/markets
```

Network rules:

```text
Inside Docker Compose, localhost means the current container itself.
Container-to-container calls must use Docker Compose service names.
Each app connects only to its matching DB service at port 3306.
Use localhost:3307/3308/3309/3310 only from the host PC.
```

Service URL examples for integrated compose:

```text
MEMBER_POINT_SERVICE_URL=http://member-point-service:8080
BATTLE_SERVICE_URL=http://battle-service:8081
MARKET_SERVICE_URL=http://market-service:8082
INSIGHT_SERVICE_URL=http://insight-reputation-service:8083

MEMBER_POINT_SERVICE_BASE_URL=http://member-point-service:8080
BATTLE_SERVICE_BASE_URL=http://battle-service:8081
MARKET_SERVICE_BASE_URL=http://market-service:8082
```

Local environment files:

Linux/macOS/Git Bash:

```bash
cp .env.example .env
```

Windows PowerShell:

```powershell
Copy-Item .env.example .env
```

Actual `.env`, `.env.local`, and `.env.rds` files are local-only files and must not be committed.
Use `.env.example` as the shared placeholder template for root `docker-compose.yml`.
If you test external API features, fill local values such as `REB_API_KEY` and `CLAUDE_API_KEY` in `.env`.
Simple routing smoke tests can use placeholder values.

Environment file roles:

```text
root .env
  - Used only for root docker-compose.yml integrated local app execution.
  - Create it from .env.example and fill local values when needed.
  - Do not commit.

service .env.local
  - Used for running one service independently.
  - Kept separate from root .env.
  - Do not delete and do not commit.

service .env.rds
  - Currently unused for EC2 Docker MySQL deployment.
  - Retained only as future RDS reference material.
  - Do not merge into root .env for local integrated execution.
  - Do not delete and do not commit.
```

JPA local policy:

```text
JPA 서비스는 `ddl-auto=validate`로 Flyway가 만든 schema를 검증한다. H2 기반 테스트 profile만 자체 테스트 schema 전략을 유지하며 Flyway를 비활성화한다.
```

Forbidden commands for normal local verification and EC2 Docker MySQL deployment:

```bash
docker compose down -v
docker volume rm
DROP DATABASE
TRUNCATE
DELETE
```

Use plain `docker compose down` only when containers need to be stopped without deleting volumes.

## 16. EC2 Docker MySQL 배포 안내

현재 EC2 배포 기준은 RDS가 아니라 Docker MySQL이다.

자세한 절차는 다음 문서를 따른다.

```text
docs/infra/EC2_DOCKER_DEPLOY.md
```

핵심 원칙:

```text
1. infra/docker-compose.yml로 네 개의 서비스 전용 MySQL을 먼저 실행한다.
2. root docker-compose.yml로 app service 5개를 실행한다.
3. root .env는 .env.ec2.example을 복사해 EC2에서 수동 작성한다.
4. 외부 진입점은 api-gateway:9000으로 제한한다.
5. MySQL 3306과 내부 서비스 포트 8080~8083은 외부에 공개하지 않는다.
6. docker compose down -v와 docker volume rm/prune은 사용하지 않는다.
7. 시연 또는 위험 작업 전 mysqldump 백업을 남긴다.
```

---

## 17. Kafka / Debezium CDC (로컬)

SSOT는 각 서비스 DB이며, 이벤트는 outbox 테이블 → Debezium(CDC) → Kafka 경로로 발행한다. (루트 `AGENTS.md` 섹션 5)

| 컨테이너 | 이미지 | 호스트 포트 | 내부 주소 |
|---|---|---|---|
| kafka | apache/kafka:3.9.1 (KRaft) | 127.0.0.1:9092 | kafka:29092 |
| kafka-connect | quay.io/debezium/connect:3.0 | 127.0.0.1:18083 | kafka-connect:8083 |

Connect REST는 insight-reputation-service(8083)와 겹치지 않도록 호스트 포트 18083을 사용한다.

### 실행 순서

```bash
cd infra
docker compose up -d market-mysql                      # binlog/GTID 설정 포함
docker compose -f docker-compose.kafka.yml up -d       # kafka + kafka-connect

# Debezium 전용 계정 생성 (최초 1회, 재실행 안전)
docker exec -i market-mysql mysql -uroot -p<MARKET_MYSQL_ROOT_PASSWORD> < debezium/market-debezium-user.sql
```

### 확인

```bash
curl -s http://localhost:18083/connector-plugins   # io.debezium.connector.mysql.MySqlConnector 포함 여부
docker exec market-mysql mysql -uroot -p<PW> -e "SELECT @@log_bin, @@binlog_format, @@binlog_row_image, @@gtid_mode"
```

Git Bash에서 `docker exec kafka /opt/kafka/bin/...`를 실행할 때는 경로 변환을 막기 위해 `MSYS_NO_PATHCONV=1`을 붙인다.

### 커넥터 설정 주의

- MySQL 컨테이너의 `TZ=Asia/Seoul` 때문에 서버 time zone이 `KST`로 잡혀 JDBC 연결이 실패한다. 커넥터 설정에 `"database.connectionTimeZone": "Asia/Seoul"`을 반드시 넣는다.
- `DATETIME` 컬럼은 time zone 정보 없이 epoch millis(UTC 기준 해석)로 발행된다. Consumer는 이 값을 KST 로컬 시각으로 해석해야 한다.
- 종료: `docker compose -f docker-compose.kafka.yml down` (`-v` 사용 금지)

### 비동기 포인트 흐름 실행 (Market 환불 → Member-Point)

설계: `docs/architecture/ASYNC_POINT_FLOW.md`

```bash
# 1) member-point-mysql도 binlog/GTID 설정 포함. Debezium 계정 (최초 1회, 재실행 안전)
docker exec -i member-point-mysql mysql -uroot -p<MEMBERPOINT_MYSQL_ROOT_PASSWORD> < infra/debezium/memberpoint-debezium-user.sql

# 2) outbox 커넥터 등록/갱신 (PUT, 재실행 안전). 각 서비스를 한 번 기동해 Flyway V2가 적용된 뒤 등록한다
./infra/debezium/register-connector.sh market-outbox
./infra/debezium/register-connector.sh memberpoint-outbox

# 3) 서비스 실행 시 환경 변수
#    market-service:       MARKET_POINT_TRANSPORT_MODE=async        (기본 sync)
#                          MARKET_POINT_REQUEST_KEY=member_id       (실험 시 market_id)
#                          MARKET_POINT_RESULTS_CONSUMER_ENABLED=true (기본 false)
#    member-point-service: MARKET_POINT_REQUESTS_CONSUMER_ENABLED=true (기본 false)
#    공통:                 KAFKA_BOOTSTRAP_SERVERS=localhost:9092 (컨테이너 내부에서는 kafka:29092)
#                          KAFKA_LISTENER_CONCURRENCY=3 (기본 3, 파티션 수와 맞춘다)
```

파티션 수: 토픽은 브로커 기본값(`KAFKA_NUM_PARTITIONS: 3`)으로 자동 생성된다. 실제 병렬도는 min(listener 동시성 × 인스턴스 수, 파티션 수)이다.
파티션은 늘리기만 가능하고, 늘리면 key → 파티션 배치가 바뀌어 key 단위 순서 보장이 일시적으로 깨질 수 있다 (`ASYNC_POINT_FLOW.md` §3.3).

| 토픽 | 발행 | 소비 | key |
|---|---|---|---|
| `market.point-requests` | Debezium (`market_outbox_event`) | member-point-service | `member_id` |
| `market.point-requests-dlt` | member-point-service (처리 실패) | 수동 확인 | 원본 key |
| `memberpoint.point-results` | Debezium (`point_outbox_event`) | market-service | `market_id` |
| `memberpoint.point-results-dlt` | market-service (처리 실패) | 수동 확인 | 원본 key |

### 비동기 포인트 흐름 운영 (ASYNC_POINT_FLOW.md §6, §15.1)

**점검** — 커넥터 상태, consumer lag, DLT 적재·미재발행 건수를 한 번에 본다.
커넥터가 `RUNNING`이 아니거나 미재발행 DLT가 있으면 종료 코드 1.

```bash
./scripts/cdc/health.sh
```

**DLT 유입 알림** — 각 서비스가 DLT로 보낼 때 ERROR 로그를 남긴다.

```text
Sent to DLT. topic=market.point-requests, partition=0, offset=12, key=999, cause=...NonRetryableEventException: ...
Failed to publish to DLT. ...   ← DLT 발행 자체가 실패. 레코드는 커밋되지 않고 다시 처리된다
```

**DLT 처리** — 원인을 해결한 뒤 재발행하거나, 재처리할 수 없는 메시지는 확인 후 건너뛴다.

```bash
# 재발행 (소비 측이 멱등이므로 안전). 전용 group으로 이미 재발행한 메시지는 다시 보내지 않는다
./scripts/cdc/replay-dlt.sh market.point-requests-dlt --dry-run
./scripts/cdc/replay-dlt.sh market.point-requests-dlt

# 건너뛰기 (재처리 불가 메시지를 확인 완료로 표시). 메시지는 DLT 토픽에 그대로 남는다
MSYS_NO_PATHCONV=1 docker exec kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:29092 \
  --group dlt-replay.market.point-requests-dlt --topic market.point-requests-dlt --reset-offsets --to-latest --execute
```

**커넥터 재시작** — 상태가 `FAILED`이면 원인(커넥터 로그) 확인 후 재시작한다. outbox row는 DB에 남아 있으므로 binlog 위치부터 다시 발행된다.

```bash
curl -s http://localhost:18083/connectors/market-outbox/status
docker logs kafka-connect --since 10m 2>&1 | grep -E "ERROR|WARN"
curl -s -X POST "http://localhost:18083/connectors/market-outbox/restart?includeTasks=true&onlyFailed=true"
```

**outbox 삭제** — Market·MP 스케줄러가 7일 지난 row를 1,000건 × 최대 10배치씩 매시간 삭제한다
(`market.scheduler.outbox-cleanup.*`, `member-point.outbox-cleanup.*`). Debezium은 DELETE를 발행하지 않는다.

consumer group 상세 (토픽·파티션별 lag):

```bash
MSYS_NO_PATHCONV=1 docker exec kafka /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server localhost:29092 --describe --group member-point-service   # 또는 market-service
```
