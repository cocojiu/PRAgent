create table if not exists review_rule_config (
    id varchar(64) primary key,
    rule_name varchar(128) not null,
    scope varchar(255) not null,
    severity varchar(32) not null,
    status varchar(32) not null,
    confidence int not null default 90,
    description varchar(1024) not null,
    sort_order int not null default 0,
    created_at datetime not null,
    updated_at datetime not null,
    key idx_review_rule_config_status (status),
    key idx_review_rule_config_severity (severity)
);

insert into review_rule_config
    (id, rule_name, scope, severity, status, confidence, description, sort_order, created_at, updated_at)
values
    ('RG-JAVA-001', '异常捕获过宽', 'Java Patch', 'MEDIUM', 'ENABLED', 88, '检测 catch Exception、catch Throwable 等过宽异常捕获。', 10, now(), now()),
    ('RG-JAVA-002', '标准输出日志', 'Java Patch', 'LOW', 'ENABLED', 97, '检测 System.out.print 等生产不可控的标准输出。', 20, now(), now()),
    ('RG-JAVA-003', '固定休眠检测', 'Java Patch', 'MEDIUM', 'ENABLED', 89, '检测 Thread.sleep 固定休眠，提示使用可测试的等待或调度机制。', 30, now(), now()),
    ('RG-GEN-001', 'TODO/FIXME 检测', 'Java / Text Patch', 'LOW', 'ENABLED', 95, '识别新增代码中的 TODO、FIXME 等未收敛临时代码。', 40, now(), now()),
    ('RG-SECRET-001', '硬编码密钥检测', 'Java / YAML / Properties', 'HIGH', 'DISABLED', 96, '检测 password、secret、accessKey、token 等疑似敏感信息。', 50, now(), now()),
    ('RG-API-001', 'Controller 无测试', 'Controller / REST API', 'MEDIUM', 'DISABLED', 88, 'Controller 改动但 PR 未包含测试文件时提示测试缺口。', 60, now(), now()),
    ('RG-DB-001', 'Entity 无迁移', 'Entity / Model / SQL', 'HIGH', 'DISABLED', 93, 'Entity 字段改动但无 migration SQL 或 changelog 时标记高风险。', 70, now(), now()),
    ('RG-CONFIG-001', '配置文件变更风险', 'application.yml / bootstrap.yml', 'HIGH', 'DISABLED', 91, '生产配置、启动配置和基础设施配置变更默认标记为高风险。', 80, now(), now())
on duplicate key update
    rule_name = values(rule_name),
    scope = values(scope),
    description = values(description);
