set @ddl = if(
    (
        select count(*)
        from information_schema.columns
        where table_schema = database()
          and table_name = 'github_comment_publication'
          and column_name = 'published_success'
    ) = 0,
    'alter table github_comment_publication add column published_success tinyint(1) generated always as (case when success = 1 and github_url is not null and trim(github_url) <> '''' then 1 else 0 end) stored after github_url',
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
          and table_name = 'github_comment_publication'
          and index_name = 'idx_github_comment_publication_task_finding_published'
    ) = 0,
    'alter table github_comment_publication add key idx_github_comment_publication_task_finding_published (task_id, finding_id, published_success)',
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
          and index_name = 'idx_review_finding_task_category_id'
    ) = 0,
    'alter table review_finding add key idx_review_finding_task_category_id (task_id, category, id)',
    'select 1'
);
prepare stmt from @ddl;
execute stmt;
deallocate prepare stmt;
