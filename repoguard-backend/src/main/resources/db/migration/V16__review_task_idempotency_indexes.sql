alter table review_task
    add unique key uk_review_task_pr_commit (organization, repository, pr_number, commit_sha),
    add key idx_review_task_org_repo_pr (organization, repository, pr_number),
    add key idx_review_task_created_at (created_at);
