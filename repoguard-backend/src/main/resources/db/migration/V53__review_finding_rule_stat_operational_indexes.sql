set @idx_review_finding_category_rule_exists := (
    select count(1)
    from information_schema.statistics
    where table_schema = database()
      and table_name = 'review_finding'
      and index_name = 'idx_review_finding_category_rule'
);
set @ddl := if(
    @idx_review_finding_category_rule_exists = 0,
    'alter table review_finding add key idx_review_finding_category_rule (category, rule_id)',
    'select 1'
);
prepare stmt from @ddl;
execute stmt;
deallocate prepare stmt;

set @idx_review_finding_category_feedback_norm_exists := (
    select count(1)
    from information_schema.statistics
    where table_schema = database()
      and table_name = 'review_finding'
      and index_name = 'idx_review_finding_category_feedback_norm'
);
set @ddl := if(
    @idx_review_finding_category_feedback_norm_exists = 0,
    'alter table review_finding add key idx_review_finding_category_feedback_norm (category, feedback_status_norm)',
    'select 1'
);
prepare stmt from @ddl;
execute stmt;
deallocate prepare stmt;
