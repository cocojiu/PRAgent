alter table review_task
    add column source varchar(32) not null default 'MANUAL_INPUT' after pr_url,
    add column trigger_source varchar(32) not null default 'MANUAL_INPUT' after source,
    add key idx_review_task_source (source),
    add key idx_review_task_trigger_source (trigger_source);
