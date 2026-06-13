alter table review_finding
    add column feedback_status varchar(32) not null default 'UNREVIEWED' after test_type,
    add column feedback_note varchar(1024) null after feedback_status,
    add column feedback_by varchar(128) null after feedback_note,
    add column feedback_at datetime null after feedback_by,
    add key idx_review_finding_feedback (feedback_status, feedback_at);
