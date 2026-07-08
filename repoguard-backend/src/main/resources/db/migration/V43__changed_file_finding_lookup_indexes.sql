set @ddl = if(
    (
        select count(*)
        from information_schema.statistics
        where table_schema = database()
          and table_name = 'review_finding'
          and index_name = 'idx_review_finding_task_category_file'
    ) = 0,
    'alter table review_finding add key idx_review_finding_task_category_file (task_id, category, file_path(255))',
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
          and table_name = 'changed_file'
          and index_name = 'idx_changed_file_task_file'
    ) = 0,
    'alter table changed_file add key idx_changed_file_task_file (task_id, file_path(255))',
    'select 1'
);
prepare stmt from @ddl;
execute stmt;
deallocate prepare stmt;
