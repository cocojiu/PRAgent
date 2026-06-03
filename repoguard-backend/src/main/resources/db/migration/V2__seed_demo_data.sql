insert into review_task
    (id, pr_number, title, repository, organization, commit_sha, branch_name, status, risk_level, mq_retries, llm_status, pr_url, created_at, started_at, finished_at, duration_seconds)
values
    (512, 512, '新增用户导出接口', 'spring-boot-demo', 'repo-guard-demo', 'a1b2c3d', 'main', 'COMPLETED', 'HIGH', 0, 'COMPLETED', 'https://github.com/repo-guard-demo/spring-boot-demo/pull/512', '2025-05-31 14:32:21', '2025-05-31 14:32:22', '2025-05-31 14:35:10', 168),
    (511, 511, '修复登录校验问题', 'auth-service', 'monorepo', 'd4e5f6q', 'main', 'REVIEWING', 'MEDIUM', 1, 'REVIEWING', 'https://github.com/monorepo/auth-service/pull/511', '2025-05-30 14:21:07', '2025-05-30 14:21:08', null, 72),
    (510, 510, '优化缓存策略', 'common-lib', 'monorepo', 'h7i8j9k', 'main', 'COMPLETED', 'LOW', 0, 'COMPLETED', 'https://github.com/monorepo/common-lib/pull/510', '2025-05-30 13:58:42', '2025-05-30 13:58:43', '2025-05-30 14:00:45', 123),
    (509, 509, '更新依赖版本', 'gateway', 'monorepo', '11m2n3o', 'main', 'COMPLETED', 'MEDIUM', 0, 'COMPLETED', 'https://github.com/monorepo/gateway/pull/509', '2025-05-30 13:37:11', '2025-05-30 13:37:12', '2025-05-30 13:39:02', 110),
    (508, 508, '移除调试日志', 'order-service', 'monorepo', 'p4q5r6s', 'main', 'FAILED', 'HIGH', 3, 'FAILED', 'https://github.com/monorepo/order-service/pull/508', '2025-05-30 12:54:33', '2025-05-30 12:54:34', '2025-05-30 12:57:59', 206),
    (507, 507, '新增订单导出功能', 'order-service', 'monorepo', 't7u8v9w', 'main', 'COMPLETED', 'LOW', 0, 'COMPLETED', 'https://github.com/monorepo/order-service/pull/507', '2025-05-30 11:22:09', '2025-05-30 11:22:10', '2025-05-30 11:24:20', 131),
    (506, 506, '完善异常处理', 'payment-service', 'monorepo', 'x1y2z3a', 'main', 'REVIEWING', 'MEDIUM', 1, 'REVIEWING', 'https://github.com/monorepo/payment-service/pull/506', '2025-05-30 10:15:55', '2025-05-30 10:15:56', null, 98),
    (505, 505, '修复并发问题', 'inventory-service', 'monorepo', 'b4c5d6e', 'main', 'FAILED', 'HIGH', 2, 'FAILED', 'https://github.com/monorepo/inventory-service/pull/505', '2025-05-30 09:45:31', '2025-05-30 09:45:32', '2025-05-30 09:49:33', 242);

insert into changed_file
    (task_id, file_path, change_type, additions, deletions)
values
    (512, 'src/main/java/com/demo/controller/ExportController.java', 'M', 32, 6),
    (512, 'src/main/java/com/demo/service/ExportService.java', 'M', 87, 12),
    (512, 'src/main/java/com/demo/util/ExportUtil.java', 'A', 45, 0),
    (512, 'src/main/resources/application.yml', 'M', 4, 1);

insert into review_finding
    (task_id, category, severity, source, rule_id, file_path, line_number, message, recommendation, method_name, test_type)
values
    (512, 'FINDING', 'HIGH', 'RULE', 'RG-SECRET-001', 'src/main/java/com/demo/controller/ExportController.java', 45, '硬编码的 AccessKey 可能导致密钥泄露', '将 AccessKey 和 SecretKey 提取到配置文件或使用密钥管理服务', null, null),
    (512, 'FINDING', 'HIGH', 'RULE', 'RG-SECRET-001', 'src/main/java/com/demo/service/ExportService.java', 78, '直接打印敏感信息到日志', '使用脱敏处理或避免打印敏感信息', null, null),
    (512, 'FINDING', 'MEDIUM', 'RULE', 'RG-API-001', 'src/main/java/com/demo/controller/ExportController.java', 61, '缺少权限校验', '建议在接口中增加权限校验逻辑', null, null),
    (512, 'FINDING', 'MEDIUM', 'LLM', null, 'src/main/java/com/demo/service/ExportService.java', 132, '捕获泛型异常 Exception', '捕获更具体的异常类型并做针对性处理', null, null),
    (512, 'FINDING', 'LOW', 'RULE', 'RG-CLEAN-001', 'src/main/java/com/demo/util/ExportUtil.java', 22, '使用了 System.out.println', '使用日志框架替代 System.out', null, null),
    (512, 'MISSING_TEST', null, null, null, 'src/main/java/com/demo/controller/ExportController.java', null, null, '为接口方法添加单元测试，覆盖正常和异常场景', 'ExportController#export', '单元测试'),
    (512, 'MISSING_TEST', null, null, null, 'src/main/java/com/demo/service/ExportService.java', null, null, '建议对核心业务逻辑进行单元测试覆盖', 'ExportService#exportUsers', '单元测试');

insert into review_timeline
    (task_id, label, event_time, status, sort_order)
values
    (512, '待处理', '2025-05-31 14:32:22', 'DONE', 1),
    (512, '已入队', '2025-05-31 14:32:23', 'DONE', 2),
    (512, '拉取 Diff', '2025-05-31 14:32:28', 'DONE', 3),
    (512, '规则分析', '2025-05-31 14:32:45', 'DONE', 4),
    (512, 'LLM 审查', '2025-05-31 14:33:32', 'DONE', 5),
    (512, '评论回写', '2025-05-31 14:34:58', 'DONE', 6),
    (512, '已完成', '2025-05-31 14:35:10', 'DONE', 7);
