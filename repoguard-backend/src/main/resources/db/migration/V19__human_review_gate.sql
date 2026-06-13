alter table review_task
    add column human_review_required tinyint(1) not null default 0 after trigger_source,
    add column human_review_status varchar(32) not null default 'NOT_REQUIRED' after human_review_required,
    add column human_review_note varchar(1024) null after human_review_status,
    add column human_review_by varchar(128) null after human_review_note,
    add column human_reviewed_at datetime null after human_review_by,
    add key idx_review_task_human_review (human_review_status, human_reviewed_at);
