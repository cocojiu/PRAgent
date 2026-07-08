alter table github_comment_publication_batch
    modify completed_at datetime null;

set @ddl = if(
    (
        select count(*)
        from information_schema.columns
        where table_schema = database()
          and table_name = 'github_comment_publication_batch'
          and column_name = 'claimed_at'
    ) = 0,
    'alter table github_comment_publication_batch add column claimed_at datetime null after completed_at',
    'select 1'
);
prepare stmt from @ddl;
execute stmt;
deallocate prepare stmt;

set @ddl = if(
    (
        select count(*)
        from information_schema.columns
        where table_schema = database()
          and table_name = 'github_comment_publication_batch'
          and column_name = 'claimed_by'
    ) = 0,
    'alter table github_comment_publication_batch add column claimed_by varchar(128) null after claimed_at',
    'select 1'
);
prepare stmt from @ddl;
execute stmt;
deallocate prepare stmt;

set @ddl = if(
    (
        select count(*)
        from information_schema.columns
        where table_schema = database()
          and table_name = 'github_comment_publication_batch'
          and column_name = 'next_retry_at'
    ) = 0,
    'alter table github_comment_publication_batch add column next_retry_at datetime null after claimed_by',
    'select 1'
);
prepare stmt from @ddl;
execute stmt;
deallocate prepare stmt;

set @ddl = if(
    (
        select count(*)
        from information_schema.columns
        where table_schema = database()
          and table_name = 'github_comment_publication_batch'
          and column_name = 'last_error'
    ) = 0,
    'alter table github_comment_publication_batch add column last_error varchar(1024) null after next_retry_at',
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
          and table_name = 'github_comment_publication_batch'
          and index_name = 'idx_github_comment_publication_batch_recovery'
    ) = 0,
    'alter table github_comment_publication_batch add key idx_github_comment_publication_batch_recovery (status, next_retry_at, claimed_at, created_at, id)',
    'select 1'
);
prepare stmt from @ddl;
execute stmt;
deallocate prepare stmt;
