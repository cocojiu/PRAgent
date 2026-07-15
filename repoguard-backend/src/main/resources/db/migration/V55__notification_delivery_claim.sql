alter table notification_event
    add column delivery_claimed_at datetime null after publish_claimed_by,
    add column delivery_claimed_by varchar(128) null after delivery_claimed_at,
    add key idx_notification_event_delivery_claim (status, delivery_claimed_at);
