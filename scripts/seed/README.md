# Development seed data

Seed data is separate from Flyway and every SQL file targets one service database. SQL files must not contain `USE` or cross-schema SQL; orchestration happens in `Invoke-TodongsanSeed.ps1`.

Run seed only after Flyway has created the service schemas. The required sequence is:

```text
1. Start MySQL containers
2. Start Spring Boot services
3. Wait for each service's Flyway migration to complete
4. Run seed
```

For local Docker:

```powershell
docker compose -f infra/docker-compose.yml up -d
docker compose up -d
```

Then run a scenario from the repository root:

```powershell
.\scripts\seed\Invoke-TodongsanSeed.ps1 -Scenario frontend
```

Available scenarios are `frontend`, `supplement`, `realistic`, `closed-battle`, and `public-data`.

`realistic` and `closed-battle` depend on IDs created by prior scenarios. Apply them in this order when setting up the complete development fixture:

```text
frontend → supplement → realistic → closed-battle
```

The script verifies database connectivity, `flyway_schema_history`, and a successful V1 migration before applying each service seed. The script defaults to local Compose passwords. Supply the four password parameters when a local environment uses different credentials. Never add seed inserts to `db/migration`.
