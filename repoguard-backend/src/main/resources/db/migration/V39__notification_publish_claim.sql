alter table notification_event
    add column publish_claimed_at datetime null after last_error,
    add column publish_claimed_by varchar(128) null after publish_claimed_at,
    add key idx_notification_event_publish_claim (status, next_retry_at, publish_claimed_at);
