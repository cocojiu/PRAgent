set @ddl = if(
    (
        select count(*)
        from information_schema.columns
        where table_schema = database()
          and table_name = 'review_finding'
          and column_name = 'severity_norm'
    ) = 0,
    'alter table review_finding add column severity_norm varchar(32) generated always as (lower(coalesce(nullif(trim(severity), ''''), ''info''))) stored after severity',
    'select 1'
);
prepare stmt from @ddl;
execute stmt;
deallocate prepare stmt;

set @ddl = if(
    (
        select count(*)
        from information_schema.statistics
        where table_schema = database()
          and table_name = 'review_finding'
          and index_name = 'idx_review_finding_task_category_severity_norm'
    ) = 0,
    'alter table review_finding add key idx_review_finding_task_category_severity_norm (task_id, category, severity_norm)',
    'select 1'
);
prepare stmt from @ddl;
execute stmt;
deallocate prepare stmt;
