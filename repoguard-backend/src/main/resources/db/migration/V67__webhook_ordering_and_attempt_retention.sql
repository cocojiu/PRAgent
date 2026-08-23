alter table review_pull_request_head
    add column head_updated_at datetime(6) null after updated_at;

alter table review_execution_attempt
    add column payload_purged_at datetime(6) null after finished_at,
    add key idx_review_execution_attempt_payload_retention (payload_purged_at, finished_at);

-- The review queue changes from v2 FIFO to v3 priority semantics. Mark every
-- not-yet-claimed delivery for database-backed republish so messages remaining
-- in the old durable queue cannot strand work during the topology cutover.
update review_task
set status = 'PUBLISH_FAILED',
    next_publish_retry_at = current_timestamp,
    last_publish_error = 'RabbitMQ review queue v3 migration requires republish',
    publish_claimed_at = null,
    publish_claimed_by = null
where status = 'QUEUED';
