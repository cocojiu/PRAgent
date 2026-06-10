alter table review_task
    add column publish_claimed_at datetime null after last_publish_error,
    add column publish_claimed_by varchar(128) null after publish_claimed_at,
    add key idx_review_task_publish_claim (status, publish_claimed_at);
