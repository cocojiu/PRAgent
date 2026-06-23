delete from github_comment_publication_batch_item where task_id in (9001, 9002, 9003, 9004);
delete from github_comment_publication_batch where task_id in (9001, 9002, 9003, 9004);
delete from github_comment_publication where task_id in (9001, 9002, 9003, 9004);
delete from review_timeline where task_id in (9001, 9002, 9003, 9004);
delete from review_finding where task_id in (9001, 9002, 9003, 9004);
delete from changed_file where task_id in (9001, 9002, 9003, 9004);
delete from review_task where id in (9001, 9002, 9003, 9004);

insert into review_task
    (id, pr_number, title, repository, organization, commit_sha, branch_name, status, risk_level, mq_retries, llm_status,
     llm_provider, llm_model, llm_duration_ms, llm_parse_status, llm_fallback_reason, llm_prompt_summary,
     llm_prompt_tokens, llm_completion_tokens, llm_total_tokens, llm_estimated_cost,
     pr_url, source, trigger_source, human_review_required, human_review_status, human_review_note, human_review_by, human_reviewed_at,
     created_at, started_at, finished_at, duration_seconds)
values
    (9001, 901, '大 PR：订单结算链路与发布配置调整', 'order-service', 'repo-guard-demo', 'demo901a', 'feature/settlement-risk',
     'PENDING_HUMAN_REVIEW', 'HIGH', 0, 'COMPLETED', 'openai', 'gpt-4.1-mini', 68420, 'PARTIAL_FALLBACK',
     null,
     'PR repo-guard-demo/order-service#901; chunked=true; chunks=4; files=14; additions=860; deletions=120; aggregateRisk=HIGH; aggregateFindings=6; failedChunks=1; chunkReasons=database,config,large_pr; rulesApplied=true; ruleFindings=2; mergedFindings=8',
     18420, 3720, 22140, 0.012846,
     'https://github.com/repo-guard-demo/order-service/pull/901', 'GITHUB_PR_PICKER', 'GITHUB_PR_PICKER',
     1, 'PENDING', null, null, null,
     '2026-06-14 09:12:10', '2026-06-14 09:12:16', '2026-06-14 09:14:03', 107),
    (9002, 902, '补充支付回调幂等测试', 'payment-service', 'repo-guard-demo', 'demo902b', 'feature/payment-idempotency',
     'COMPLETED', 'LOW', 0, 'COMPLETED', 'dashscope', 'qwen-plus', 15860, 'PARSED',
     null,
     'PR repo-guard-demo/payment-service#902; files=3; additions=96; deletions=18; sampleFiles=PaymentCallbackService.java, PaymentCallbackServiceTest.java; rulesApplied=true; ruleFindings=0; mergedFindings=1',
     5240, 940, 6180, 0.002312,
     'https://github.com/repo-guard-demo/payment-service/pull/902', 'GITHUB_PR_PICKER', 'GITHUB_PR_PICKER',
     0, 'NOT_REQUIRED', null, null, null,
     '2026-06-13 16:22:45', '2026-06-13 16:22:48', '2026-06-13 16:23:11', 23),
    (9003, 903, '库存缓存刷新策略调整', 'inventory-service', 'repo-guard-demo', 'demo903c', 'feature/cache-refresh',
     'COMPLETED', 'MEDIUM', 1, 'FALLBACK', 'openai', 'gpt-4.1-mini', 30000, 'FALLBACK',
     'category=llm_timeout operation=chat_completions',
     'PR repo-guard-demo/inventory-service#903; files=5; additions=188; deletions=42; sampleFiles=InventoryCacheRefresher.java, application-prod.yml',
     null, null, null, null,
     'https://github.com/repo-guard-demo/inventory-service/pull/903', 'GITHUB_PR_PICKER', 'RETRY_COMPENSATION',
     1, 'APPROVED', '规则兜底结果已人工确认，可按建议修复后合并。', 'architect', '2026-06-12 11:04:21',
     '2026-06-12 10:58:18', '2026-06-12 10:58:26', '2026-06-12 11:01:44', 198),
    (9004, 904, '用户资料接口字段脱敏', 'user-service', 'repo-guard-demo', 'demo904d', 'feature/profile-mask',
     'COMPLETED', 'INFO', 0, 'COMPLETED', 'dashscope', 'qwen-plus', 10320, 'PARSED',
     null,
     'PR repo-guard-demo/user-service#904; files=2; additions=44; deletions=12; sampleFiles=UserProfileDto.java, UserProfileController.java; rulesApplied=true; ruleFindings=0; mergedFindings=0',
     3120, 410, 3530, 0.001128,
     'https://github.com/repo-guard-demo/user-service/pull/904', 'MANUAL_INPUT', 'MANUAL_INPUT',
     0, 'NOT_REQUIRED', null, null, null,
     '2026-06-10 15:40:02', '2026-06-10 15:40:04', '2026-06-10 15:40:18', 14);

insert into changed_file
    (task_id, file_path, change_type, additions, deletions)
values
    (9001, 'src/main/resources/db/migration/V32__settlement_snapshot.sql', 'ADD', 220, 0),
    (9001, 'src/main/java/com/demo/order/SettlementService.java', 'MODIFY', 180, 42),
    (9001, 'src/main/resources/application-prod.yml', 'MODIFY', 18, 6),
    (9001, '.github/workflows/release.yml', 'MODIFY', 36, 12),
    (9002, 'src/main/java/com/demo/payment/PaymentCallbackService.java', 'MODIFY', 42, 12),
    (9002, 'src/test/java/com/demo/payment/PaymentCallbackServiceTest.java', 'ADD', 54, 0),
    (9003, 'src/main/java/com/demo/inventory/InventoryCacheRefresher.java', 'MODIFY', 120, 24),
    (9003, 'src/main/resources/application-prod.yml', 'MODIFY', 16, 8),
    (9004, 'src/main/java/com/demo/user/UserProfileDto.java', 'MODIFY', 22, 8),
    (9004, 'src/main/java/com/demo/user/UserProfileController.java', 'MODIFY', 22, 4);

insert into review_finding
    (id, task_id, category, severity, source, rule_id, file_path, line_number, message, recommendation, method_name, test_type,
     feedback_status, feedback_note, feedback_by, feedback_at)
values
    (91001, 9001, 'FINDING', 'HIGH', 'LLM+RULE', 'LLM / RG-DB-001', 'src/main/resources/db/migration/V32__settlement_snapshot.sql', 18,
     '结算快照表缺少唯一约束，重复消费时可能写入重复结算记录。',
     '为 order_id、settlement_period 增加唯一索引，并补充重复消息回放测试。', null, null,
     'VALID', '数据库迁移风险真实存在，必须修复。', 'reviewer-a', '2026-06-14 09:25:00'),
    (91002, 9001, 'FINDING', 'MEDIUM', 'LLM', null, 'src/main/java/com/demo/order/SettlementService.java', 146,
     '结算失败后只记录日志，没有将失败订单投递到补偿队列。',
     '将失败订单写入补偿任务表或发送到可靠补偿队列，避免静默丢单。', null, null,
     'VALID', '建议作为合并前阻塞项处理。', 'reviewer-a', '2026-06-14 09:26:00'),
    (91003, 9001, 'FINDING', 'MEDIUM', 'RULE', 'RG-CONFIG-001', 'src/main/resources/application-prod.yml', 34,
     '生产配置调整了结算开关，但缺少灰度和回滚说明。',
     '补充灰度比例、回滚开关和发布检查项。', null, null,
     'UNREVIEWED', null, null, null),
    (91004, 9001, 'MISSING_TEST', null, null, null, 'src/main/java/com/demo/order/SettlementService.java', null,
     null, '补充重复消息、下游超时和补偿失败场景的集成测试。', 'SettlementService#settle', '集成测试',
     'UNREVIEWED', null, null, null),
    (91005, 9002, 'FINDING', 'LOW', 'LLM', null, 'src/main/java/com/demo/payment/PaymentCallbackService.java', 72,
     '幂等冲突时返回值语义不够明确，调用方可能误判为失败。',
     '将重复回调返回明确的 IDEMPOTENT_ACCEPTED 状态，并在接口文档中说明。', null, null,
     'FALSE_POSITIVE', '业务方确认网关已统一映射，当前无需修改。', 'reviewer-b', '2026-06-13 17:05:00'),
    (91006, 9003, 'FINDING', 'MEDIUM', 'RULE', 'RG-JAVA-003', 'src/main/java/com/demo/inventory/InventoryCacheRefresher.java', 88,
     '缓存刷新逻辑使用固定 Thread.sleep，容易放大批量刷新耗时。',
     '改为基于调度器或退避策略的可测试等待机制。', null, null,
     'VALID', '兜底规则命中准确。', 'architect', '2026-06-12 11:02:00'),
    (91007, 9003, 'FINDING', 'LOW', 'RULE', 'RG-CONFIG-001', 'src/main/resources/application-prod.yml', 21,
     '缓存 TTL 从 5 分钟调整为 30 秒，可能增加数据库压力。',
     '补充容量评估或灰度观察指标。', null, null,
     'VALID', '需要压测确认。', 'architect', '2026-06-12 11:03:00');

insert into review_timeline
    (task_id, label, event_time, status, sort_order)
values
    (9001, 'Task queued', '2026-06-14 09:12:10', 'DONE', 1),
    (9001, 'GitHub diff fetched', '2026-06-14 09:12:18', 'DONE', 2),
    (9001, 'Chunked LLM review generated: partial fallback 1/4', '2026-06-14 09:13:52', 'DONE', 3),
    (9001, 'Human review required', '2026-06-14 09:14:03', 'CURRENT', 4),
    (9002, 'Task queued', '2026-06-13 16:22:45', 'DONE', 1),
    (9002, 'Code review generated', '2026-06-13 16:23:07', 'DONE', 2),
    (9002, 'Review completed', '2026-06-13 16:23:11', 'DONE', 3),
    (9003, 'Task queued by retry compensation', '2026-06-12 10:58:18', 'DONE', 1),
    (9003, 'Code review generated by rule fallback: llm_timeout', '2026-06-12 11:01:40', 'DONE', 2),
    (9003, 'Human review approved', '2026-06-12 11:04:21', 'DONE', 3),
    (9004, 'Task queued', '2026-06-10 15:40:02', 'DONE', 1),
    (9004, 'Review completed', '2026-06-10 15:40:18', 'DONE', 2);

insert into github_comment_publication
    (id, task_id, finding_id, target_type, status, success, github_comment_id, github_url, message, published_at, created_at, updated_at)
values
    (92001, 9001, null, 'pull_request', 'PUBLISHED', 1, 100901,
     'https://github.com/repo-guard-demo/order-service/pull/901#issuecomment-100901',
     'PR 总评已发布，包含部分分片规则补位说明。', '2026-06-14 09:28:30', '2026-06-14 09:28:30', '2026-06-14 09:28:30'),
    (92002, 9001, 91001, 'line', 'PUBLISHED', 1, 100902,
     'https://github.com/repo-guard-demo/order-service/pull/901#discussion_r100902',
     '高风险数据库迁移评论已发布。', '2026-06-14 09:28:31', '2026-06-14 09:28:31', '2026-06-14 09:28:31'),
    (92003, 9002, null, 'pull_request', 'PUBLISHED', 1, 100903,
     'https://github.com/repo-guard-demo/payment-service/pull/902#issuecomment-100903',
     '低风险 PR 总评已发布。', '2026-06-13 17:08:00', '2026-06-13 17:08:00', '2026-06-13 17:08:00');
