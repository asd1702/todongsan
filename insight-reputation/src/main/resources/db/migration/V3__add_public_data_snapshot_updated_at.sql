-- Align a legacy schema that predates BaseEntity's update timestamp with the
-- current entity mapping. The conditional statement also supports existing
-- baselined databases that already have the column.
SET @add_updated_at_sql = (
    SELECT IF(
        COUNT(*) = 0,
        'ALTER TABLE public_data_snapshot ADD COLUMN updated_at DATETIME(6) NULL AFTER created_at',
        'SELECT 1'
    )
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'public_data_snapshot'
      AND column_name = 'updated_at'
);
PREPARE add_updated_at_statement FROM @add_updated_at_sql;
EXECUTE add_updated_at_statement;
DEALLOCATE PREPARE add_updated_at_statement;
