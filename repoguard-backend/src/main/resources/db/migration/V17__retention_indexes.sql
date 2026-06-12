alter table review_task
    add key idx_review_task_repo_status_created (repository, status, created_at),
    add key idx_review_task_repo_risk_created (repository, risk_level, created_at);

alter table github_comment_publication_batch
    add key idx_github_comment_publication_batch_task_created (task_id, created_at, id),
    add key idx_github_comment_publication_batch_task_status_created (task_id, status, created_at, id);

alter table github_comment_publication_batch_item
    add key idx_github_comment_publication_batch_item_task_created (task_id, created_at);

alter table review_timeline
    add key idx_review_timeline_task_created (task_id, event_time);
