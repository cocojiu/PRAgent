set @ddl = if(
    (
        select count(*)
        from information_schema.statistics
        where table_schema = database()
          and table_name = 'review_task'
          and index_name = 'idx_review_task_status_norm_created'
    ) = 0,
    'alter table review_task add key idx_review_task_status_norm_created (status_norm, created_at)',
    'select 1'
);
prepare stmt from @ddl;
execute stmt;
deallocate prepare stmt;
