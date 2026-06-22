set @ddl = if(
    (
        select count(*)
        from information_schema.statistics
        where table_schema = database()
          and table_name = 'review_task'
          and index_name = 'idx_review_task_dashboard_created_risk'
    ) = 0,
    'alter table review_task add key idx_review_task_dashboard_created_risk (created_at, risk_level)',
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
          and index_name = 'idx_review_task_dashboard_created_llm_model'
    ) = 0,
    'alter table review_task add key idx_review_task_dashboard_created_llm_model (created_at, llm_status, llm_provider, llm_model)',
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
          and index_name = 'idx_review_task_dashboard_created_llm_repo'
    ) = 0,
    'alter table review_task add key idx_review_task_dashboard_created_llm_repo (created_at, llm_status, organization, repository)',
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
          and index_name = 'idx_review_finding_task_category_rule'
    ) = 0,
    'alter table review_finding add key idx_review_finding_task_category_rule (task_id, category, rule_id)',
    'select 1'
);
prepare stmt from @ddl;
execute stmt;
deallocate prepare stmt;
