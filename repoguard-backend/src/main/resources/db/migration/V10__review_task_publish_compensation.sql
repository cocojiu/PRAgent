alter table review_task
    add column publish_attempts int not null default 0 after mq_retries,
    add column next_publish_retry_at datetime null after publish_attempts,
    add column last_publish_error varchar(512) null after next_publish_retry_at,
    add key idx_review_task_publish_retry (status, next_publish_retry_at);
