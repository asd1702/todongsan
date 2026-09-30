# Flyway pre-freeze V1 recovery

## Scope

This procedure exists only for a development or preserved database that ran an
early, pre-freeze `V1__baseline.sql`. For example, an early Insight V1 created
`insight_report.report_content` as `TINYTEXT`, while the frozen V1 now correctly
defines it as `TEXT`. Flyway records a migration checksum, so changing V1 before
the freeze can make a pre-freeze database fail validation before later migrations
run.

`FLYWAY_BASELINE_ON_MIGRATE` remains `false` by default. It is only for adopting
a non-empty legacy database that has no `flyway_schema_history`; it is not a
checksum-recovery switch.

## Development database with disposable data

1. Stop the service that owns the database.
2. Dispose of only that service's development database or Docker volume.
3. Start the service against an empty database.
4. Confirm Flyway applies the complete current chain in order.

For Insight, the recreated chain is `V1 → V2 → V3 → V4`. This is the preferred
approach because it proves that the frozen migrations alone reproduce the schema.

## Database whose data must be preserved

Use this path only after a backup and an explicit schema comparison confirm that
the V1 change is the intended correction.

1. Stop the owning service and back up the database.
2. Inspect the actual schema and `flyway_schema_history`; do not repair an
   unrelated checksum mismatch.
3. Confirm that the current frozen V1 represents the intended physical schema.
4. Run `flyway repair` manually once, with the owning service's datasource and
   credentials.
5. Start the service normally so that only pending migrations are applied.
6. Verify Hibernate `ddl-auto=validate` and the resulting schema.

`flyway repair` is a manual, one-time migration recovery operation. It must never
be added to application startup, Docker entrypoint scripts, or a deployment's
automatic flow.

## V1 freeze

After this Flyway stabilization is merged, every `V1__baseline.sql` is immutable.
New schema work must add a new version instead:

- Insight's next schema change is `V5__...` because V2 through V4 already exist.
- Battle's first post-baseline schema change is `V2__...`.

Changing an already applied migration changes its checksum and is not an accepted
schema-evolution mechanism.
