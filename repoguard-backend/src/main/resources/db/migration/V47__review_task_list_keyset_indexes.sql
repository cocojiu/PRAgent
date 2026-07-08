set @ddl = if(
    (
        select count(*)
        from information_schema.statistics
        where table_schema = database()
          and table_name = 'review_task'
          and index_name = 'idx_review_task_list_created_id'
    ) = 0,
    'alter table review_task add key idx_review_task_list_created_id (created_at, id)',
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
          and table_name = 'review_task'
          and index_name = 'idx_review_task_list_status_created_id'
    ) = 0,
    'alter table review_task add key idx_review_task_list_status_created_id (status, created_at, id)',
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
          and table_name = 'review_task'
          and index_name = 'idx_review_task_list_repo_status_created_id'
    ) = 0,
    'alter table review_task add key idx_review_task_list_repo_status_created_id (repository, status, created_at, id)',
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
          and table_name = 'review_task'
          and index_name = 'idx_review_task_list_trigger_created_id'
    ) = 0,
    'alter table review_task add key idx_review_task_list_trigger_created_id (trigger_source, created_at, id)',
    'select 1'
);
prepare stmt from @ddl;
execute stmt;
deallocate prepare stmt;
