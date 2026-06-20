insert into review_rule_config
    (id, rule_name, scope, applicable_languages, file_patterns, severity, status, confidence, description,
     positive_example, false_positive_guidance, sort_order, created_at, updated_at)
values
    ('RG-DB-002', '破坏性数据库迁移检测', 'Flyway Migration', 'SQL', '*.sql', 'HIGH', 'ENABLED', 94,
     '检测 DROP TABLE、DROP COLUMN、TRUNCATE TABLE 等破坏性 DDL，提示补充兼容迁移、备份和回滚方案。',
     'Use expand-and-contract: add new column/table, backfill, switch reads/writes, then remove legacy objects in a later release.',
     '当迁移仅作用于 demo/test seed、临时表或已完成独立备份和停机窗口审批时可标记为误报。',
     140, now(), now()),
    ('RG-DB-003', '新增非空字段兼容性检测', 'Flyway Migration', 'SQL', '*.sql', 'HIGH', 'ENABLED', 90,
     '检测新增 NOT NULL 字段但缺少 DEFAULT 的迁移，提示历史数据和灰度发布兼容风险。',
     'alter table review_task add column source varchar(32) not null default ''manual'';',
     '当表为空、迁移前已有可靠回填步骤，或字段由数据库生成且具备兼容窗口时可标记为误报。',
     150, now(), now()),
    ('RG-GH-001', 'GitHub 评论回写幂等检测', 'GitHub Writeback', 'Java', '*.java', 'HIGH', 'ENABLED', 93,
     '检测新增 GitHub 评论直接发布调用，提示确认经过 preview/publication 幂等检查并记录批次明细。',
     'Build a publish plan from preview, skip already published findings, then record publication and batch items.',
     '当调用位于低层 GitHub client，且上层服务已经强制经过幂等发布计划和批次记录时可标记为误报。',
     160, now(), now())
on duplicate key update
    rule_name = values(rule_name),
    scope = values(scope),
    applicable_languages = values(applicable_languages),
    file_patterns = values(file_patterns),
    severity = values(severity),
    status = values(status),
    confidence = values(confidence),
    description = values(description),
    positive_example = values(positive_example),
    false_positive_guidance = values(false_positive_guidance),
    sort_order = values(sort_order),
    updated_at = values(updated_at);
