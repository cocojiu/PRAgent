alter table review_task
    add key idx_review_task_pr_created (pr_number, created_at),
    add key idx_review_task_commit_created (commit_sha, created_at),
    add key idx_review_task_org_created (organization, created_at),
    add key idx_review_task_title_created (title, created_at),
    add key idx_review_task_mq_health (status, publish_claimed_at, created_at);
