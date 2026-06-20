insert into review_rule_config
    (id, rule_name, scope, applicable_languages, file_patterns, severity, status, confidence, description,
     positive_example, false_positive_guidance, sort_order, created_at, updated_at)
values
    ('RG-API-001', 'Controller API test gap', 'Controller / REST API', 'Java', '*Controller.java', 'MEDIUM', 'ENABLED', 88,
     'Detects Controller/API mapping changes when the pull request does not include src/test, ControllerTest, ApiContractTest, or IntegrationTest changes.',
     'When adding @GetMapping, @PostMapping, or another mapping in a Controller, include request validation, permission, status code, and key response field tests in the same pull request.',
     'Can be marked as false positive when coverage is intentionally handled by generated contract tests, downstream black-box suites, or a separate linked test-only pull request.',
     170, now(), now())
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
