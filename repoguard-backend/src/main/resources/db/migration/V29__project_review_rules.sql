insert into review_rule_config
    (id, rule_name, scope, applicable_languages, file_patterns, severity, status, confidence, description,
     positive_example, false_positive_guidance, sort_order, created_at, updated_at)
values
    ('RG-AUTH-001', '写接口权限门禁', 'Controller / REST API', 'Java', '*Controller.java,*Resource.java', 'HIGH', 'ENABLED', 92,
     '检测新增 POST/PUT/PATCH/DELETE Controller 映射，提示补充角色门禁或等效网关权限控制。',
     '@RequireRole("ADMIN")\n@PostMapping("/config")',
     '当权限由统一网关、类级注解或全局拦截器覆盖时可标记为误报，但需要在评审中说明证据。',
     90, now(), now()),
    ('RG-STATE-001', '任务状态机绕过检测', 'Review Task State', 'Java', '*.java', 'MEDIUM', 'ENABLED', 90,
     '检测直接写入 QUEUED、REVIEWING、COMPLETED、FAILED 等状态字符串的代码，提示通过状态机或状态应用边界流转。',
     'reviewTaskStateMachine.ensureTransition(currentStatus, nextStatus);',
     '当代码位于专门状态机、状态应用器或测试 fixture 中时可标记为误报。',
     100, now(), now()),
    ('RG-MQ-001', 'MQ 发布补偿语义检测', 'RabbitMQ Publisher', 'Java', '*.java', 'HIGH', 'ENABLED', 91,
     '检测新增 RabbitMQ 直接发布调用，提示确认发送失败进入可补偿状态并记录重试信息。',
     'publisher.publish(message); // failure state and retry metadata are persisted by the publisher boundary',
     '当发布调用已经封装在具备 confirm、重试、补偿和指标记录的 publisher 中时可标记为误报。',
     110, now(), now()),
    ('RG-EXT-001', '外部调用治理检测', 'External Call', 'Java', '*.java', 'MEDIUM', 'ENABLED', 88,
     '检测新增裸 RestClient/WebClient/HttpClient 调用，提示补充超时、错误分类、限流熔断和指标记录。',
     'externalCallResilience.execute("github", "fetch_pr", () -> client.fetch());',
     '当调用已被配置级超时、Resilience4j 注解或统一 client 边界保护时可标记为误报。',
     120, now(), now()),
    ('RG-LOG-001', '敏感日志检测', 'Logging / Security', 'Java', '*.java', 'HIGH', 'ENABLED', 94,
     '检测日志中输出 token、secret、password、webhook 等敏感字段的风险。',
     'log.info("webhook configured bindingId={}", bindingId);',
     '当字段名只是非敏感枚举或已经经过脱敏函数处理时可标记为误报。',
     130, now(), now())
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

update review_rule_config
set status = 'ENABLED',
    severity = 'HIGH',
    confidence = 96,
    description = '检测 password、secret、accessKey、token 等疑似敏感信息硬编码或配置明文。',
    updated_at = now()
where id = 'RG-SECRET-001';
