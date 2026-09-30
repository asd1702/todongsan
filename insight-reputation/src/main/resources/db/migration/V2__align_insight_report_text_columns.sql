-- Repairs databases initialized during the pre-freeze V1 transition, where these
-- columns were accidentally narrowed to TINYTEXT. TEXT is also safe for a legacy
-- database that was baselined with the original TEXT schema.
ALTER TABLE insight_report
    MODIFY COLUMN summary TEXT,
    MODIFY COLUMN raw_prompt TEXT,
    MODIFY COLUMN failed_reason TEXT,
    MODIFY COLUMN report_content TEXT;
