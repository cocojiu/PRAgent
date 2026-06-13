alter table review_rule_config
    add column applicable_languages varchar(255) not null default '' after scope,
    add column file_patterns varchar(512) not null default '' after applicable_languages,
    add column positive_example varchar(1024) not null default '' after description,
    add column false_positive_guidance varchar(1024) not null default '' after positive_example;

update review_rule_config
set applicable_languages = 'Java',
    file_patterns = '*.java',
    positive_example = 'catch (IOException ex) { log.warn("read failed", ex); }',
    false_positive_guidance = 'Mark as false positive when a framework boundary intentionally catches broad exceptions and preserves context.'
where id = 'RG-JAVA-001';

update review_rule_config
set applicable_languages = 'Java',
    file_patterns = '*.java',
    positive_example = 'log.info("processed request {}", requestId);',
    false_positive_guidance = 'Mark as false positive for command-line utilities, tests, or intentionally user-facing console output.'
where id = 'RG-JAVA-002';

update review_rule_config
set applicable_languages = 'Java',
    file_patterns = '*.java',
    positive_example = 'await().atMost(Duration.ofSeconds(5)).until(this::ready);',
    false_positive_guidance = 'Mark as false positive when a deterministic sleep is required by an external protocol or test fixture.'
where id = 'RG-JAVA-003';

update review_rule_config
set applicable_languages = 'Any',
    file_patterns = '*',
    positive_example = 'Link temporary work to an issue and remove stale TODO comments before merge.',
    false_positive_guidance = 'Mark as false positive when the TODO is part of documentation or intentionally preserved sample code.'
where id = 'RG-GEN-001';

update review_rule_config
set applicable_languages = 'Java,YAML,Properties',
    file_patterns = '*.java,*.yml,*.yaml,*.properties',
    positive_example = 'Read credentials from environment variables or a managed secret store.',
    false_positive_guidance = 'Mark as false positive when the value is a documented placeholder, test fixture, or non-secret token name.'
where id = 'RG-SECRET-001';

update review_rule_config
set applicable_languages = 'Java',
    file_patterns = '*Controller.java,*Resource.java',
    positive_example = 'Add controller tests for new request validation, success response, and error response contracts.',
    false_positive_guidance = 'Mark as false positive when coverage is supplied by an existing contract or integration test.'
where id = 'RG-API-001';

update review_rule_config
set applicable_languages = 'Java,SQL',
    file_patterns = '*Entity.java,*.sql',
    positive_example = 'Pair entity field changes with a Flyway migration or compatible changelog.',
    false_positive_guidance = 'Mark as false positive when the field is computed, transient, or backed by an existing column.'
where id = 'RG-DB-001';

update review_rule_config
set applicable_languages = 'YAML,Properties',
    file_patterns = 'application*.yml,application*.yaml,bootstrap*.yml,bootstrap*.yaml,*.properties',
    positive_example = 'Document production config impact and rollback values when changing runtime settings.',
    false_positive_guidance = 'Mark as false positive for local-only examples or documentation snippets.'
where id = 'RG-CONFIG-001';
