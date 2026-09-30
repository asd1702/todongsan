# Market schema migration baseline

Market schema history is now owned exclusively by [V1__baseline.sql](../../market-service/src/main/resources/db/migration/V1__baseline.sql). The former `docs/market/sql/001` through `010` scripts were manual, volume-dependent changes and are no longer executable migration sources.

The V1 baseline includes the final state of those changes: prediction and price-history tables, `attempt_no`, settlement, void/refund, price-history v4 columns and indexes, reputation-update, comments, and region scope columns. Future Market schema changes must be added only as new Flyway migrations under `market-service/src/main/resources/db/migration`.
