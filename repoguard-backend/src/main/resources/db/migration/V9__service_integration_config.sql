insert into integration_config
    (provider, status, base_url, token_value, default_owner, default_repo, last_checked_at, last_error, created_at, updated_at)
values
    ('MYSQL', 'NOT_CONFIGURED', 'jdbc:mysql://localhost:3306/repoguard?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true', null, 'root', 'repoguard', null, null, now(), now()),
    ('RABBITMQ', 'NOT_CONFIGURED', 'amqp://localhost:5672', null, 'repoguard', '/', null, null, now(), now())
on duplicate key update
    provider = provider;
