-- Preserve the exact binary legacy-label contract for old and current writers.
-- Virtual classification avoids an application-maintained flag or historical data rewrite.
ALTER TABLE review_timeline
    ADD COLUMN legacy_failure_event TINYINT GENERATED ALWAYS AS (
        CAST(label AS BINARY) = 'Review failed'
        OR CAST(label AS BINARY) LIKE 'Review failed:%'
    ) VIRTUAL,
    ALGORITHM=INPLACE, LOCK=NONE;

-- Task-first also supports personal mode, where the tenant interceptor is disabled.
ALTER TABLE review_timeline
    ADD INDEX idx_review_timeline_legacy_failure
        (task_id, legacy_failure_event, tenant_id, sort_order, id),
    ALGORITHM=INPLACE, LOCK=NONE;
