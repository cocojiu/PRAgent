set @ddl = if(
    (
        select count(*)
        from information_schema.columns
        where table_schema = database()
          and table_name = 'review_task'
          and column_name = 'status_norm'
    ) = 0,
    'alter table review_task add column status_norm varchar(32) generated always as (upper(coalesce(nullif(trim(status), ''''), ''UNKNOWN''))) stored after status',
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
          and table_name = 'review_task'
          and column_name = 'risk_level_norm'
    ) = 0,
    'alter table review_task add column risk_level_norm varchar(32) generated always as (case when upper(coalesce(nullif(trim(risk_level), ''''), ''INFO'')) in (''CRITICAL'', ''HIGH'', ''MEDIUM'', ''LOW'') then upper(coalesce(nullif(trim(risk_level), ''''), ''INFO'')) else ''INFO'' end) stored after risk_level',
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
          and table_name = 'review_task'
          and column_name = 'risk_bucket_norm'
    ) = 0,
    'alter table review_task add column risk_bucket_norm varchar(32) generated always as (case when upper(coalesce(nullif(trim(risk_level), ''''), ''INFO'')) in (''CRITICAL'', ''HIGH'') then ''HIGH'' when upper(coalesce(nullif(trim(risk_level), ''''), ''INFO'')) in (''MEDIUM'', ''LOW'') then upper(coalesce(nullif(trim(risk_level), ''''), ''INFO'')) else ''INFO'' end) stored after risk_level_norm',
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
          and table_name = 'review_task'
          and column_name = 'llm_status_norm'
    ) = 0,
    'alter table review_task add column llm_status_norm varchar(32) generated always as (lower(coalesce(nullif(trim(llm_status), ''''), ''''))) stored after llm_status',
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
          and table_name = 'review_task'
          and column_name = 'llm_parse_status_norm'
    ) = 0,
    'alter table review_task add column llm_parse_status_norm varchar(32) generated always as (lower(coalesce(nullif(trim(llm_parse_status), ''''), ''''))) stored after llm_parse_status',
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
          and table_name = 'review_task'
          and column_name = 'llm_model_label'
    ) = 0,
    'alter table review_task add column llm_model_label varchar(260) generated always as (concat(coalesce(nullif(trim(llm_provider), ''''), ''unknown''), '' / '', coalesce(nullif(trim(llm_model), ''''), ''unknown''))) stored after llm_model',
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
          and table_name = 'review_task'
          and column_name = 'repository_label'
    ) = 0,
    'alter table review_task add column repository_label varchar(260) generated always as (case when organization is null or trim(organization) = '''' then coalesce(nullif(trim(repository), ''''), ''unknown'') else concat(trim(organization), ''/'', coalesce(nullif(trim(repository), ''''), ''unknown'')) end) stored after repository',
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
          and table_name = 'review_task'
          and column_name = 'created_date'
    ) = 0,
    'alter table review_task add column created_date date generated always as (date(created_at)) stored after created_at',
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
          and table_name = 'review_finding'
          and column_name = 'feedback_status_norm'
    ) = 0,
    'alter table review_finding add column feedback_status_norm varchar(32) generated always as (upper(coalesce(nullif(trim(feedback_status), ''''), ''UNREVIEWED''))) stored after feedback_status',
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
          and index_name = 'idx_review_task_dashboard_created_risk_norm'
    ) = 0,
    'alter table review_task add key idx_review_task_dashboard_created_risk_norm (created_at, risk_bucket_norm, risk_level_norm, status_norm)',
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
          and index_name = 'idx_review_task_dashboard_created_day'
    ) = 0,
    'alter table review_task add key idx_review_task_dashboard_created_day (created_at, created_date)',
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
          and index_name = 'idx_review_task_dashboard_created_llm_model_norm'
    ) = 0,
    'alter table review_task add key idx_review_task_dashboard_created_llm_model_norm (created_at, llm_status_norm, llm_parse_status_norm, llm_model_label)',
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
          and index_name = 'idx_review_task_dashboard_created_llm_repo_norm'
    ) = 0,
    'alter table review_task add key idx_review_task_dashboard_created_llm_repo_norm (created_at, llm_status_norm, llm_parse_status_norm, repository_label)',
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
          and index_name = 'idx_review_finding_task_category_feedback_norm'
    ) = 0,
    'alter table review_finding add key idx_review_finding_task_category_feedback_norm (task_id, category, feedback_status_norm)',
    'select 1'
);
prepare stmt from @ddl;
execute stmt;
deallocate prepare stmt;
