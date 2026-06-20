alter table review_task
    add column review_claimed_at datetime null after publish_claimed_by,
    add column review_claimed_by varchar(128) null after review_claimed_at,
    add key idx_review_task_execution_claim (status, review_claimed_at);

update review_task
set review_claimed_at = coalesce(started_at, created_at),
    review_claimed_by = concat('legacy-', id)
where status = 'REVIEWING'
  and review_claimed_at is null;
