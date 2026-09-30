# Database per Service (local Docker)

## Service ownership

| Service | MySQL container | Database | Application user | Schema source |
| --- | --- | --- | --- | --- |
| Member-Point | `member-point-mysql` | `memberpoint` | `memberpoint_user` | `member-point-service/db/migration` |
| Market | `market-mysql` | `market` | `market_user` | `market-service/db/migration` |
| Battle | `battle-mysql` | `battle` | `battle_user` | `battle-service/db/migration` |
| Insight-Reputation | `insight-mysql` | `insight` | `insight_user` | `insight-reputation/db/migration` |

`MYSQL_DATABASE`, `MYSQL_USER`, and `MYSQL_PASSWORD` provision only an empty database and its user. Flyway migrations packaged with each Spring Boot service are the sole schema source; MySQL init scripts never create tables.

## Startup

```bash
cp .env.example .env
docker compose -f infra/docker-compose.yml up -d
docker compose up -d
```

The MySQL instances are intentionally separate failure domains. The host-only ports are `3307` (Member-Point), `3308` (Market), `3309` (Battle), and `3310` (Insight). App containers use their Docker service name and port `3306`, never a host port.

## Volumes and initialization

The named volumes `memberpoint-mysql-data`, `market-mysql-data`, `battle-mysql-data`, and `insight-mysql-data` are independent. MySQL entrypoint initialization runs only for a newly created volume. Do not remove volumes during normal development.

Development fixtures live in `scripts/seed`. Each SQL file targets one database and `Invoke-TodongsanSeed.ps1` performs cross-service orchestration.

## MySQL-backed Market experiments

The default test suites use H2 and do not share Docker MySQL. Market's optional MySQL integration profile uses the disposable `market-mysql-it` container (`market_test` / `market_test_user`), never the development `market-mysql` volume or the default Compose:

```powershell
docker compose -f infra/docker-compose.yml -f infra/docker-compose.test.yml up -d
```

`market-mysql-it` exposes host port `13308` and stores data in `tmpfs`; start it before the test and let Flyway create the schema. It is intentionally excluded from production and EC2 commands.

## Architecture follow-ups (not part of this change)

- Market point spend, settlement, and refund are REST calls with idempotency keys; timeout recovery and reconciliation remain synchronous-workflow concerns. They are candidates for a future transactional outbox and Saga design.
- Battle point reward retries and Market-to-Insight reputation update tasks are existing asynchronous retry-style tables. They should be assessed before introducing a real event publisher; this change does not turn them into Kafka/RabbitMQ flows.
- REST calls can still propagate outages between services. Database failure is now isolated, but synchronous API availability remains a separate resilience concern.
