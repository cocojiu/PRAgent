alter table github_comment_publication_batch_item
    add key idx_github_comment_item_tenant_task_batch_id (tenant_id, task_id, batch_id, id);
