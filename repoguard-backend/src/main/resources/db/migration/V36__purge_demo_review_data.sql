create temporary table if not exists demo_review_task_ids (
    id bigint primary key
) engine = memory;

delete from demo_review_task_ids;

insert ignore into demo_review_task_ids (id)
select id
from review_task
where
    (
        organization in ('repo-guard-demo', 'monorepo')
        and (
            pr_url like 'https://github.com/repo-guard-demo/%'
            or pr_url like 'https://github.com/monorepo/%'
            or commit_sha in (
                'a1b2c3d', 'd4e5f6q', 'h7i8j9k', '11m2n3o',
                'p4q5r6s', 't7u8v9w', 'x1y2z3a', 'b4c5d6e',
                'demo901a', 'demo902b', 'demo903c', 'demo904d'
            )
        )
    )
    or id in (505, 506, 507, 508, 509, 510, 511, 512, 9001, 9002, 9003, 9004);

delete delivery
from notification_delivery_log delivery
join demo_review_task_ids demo_task on demo_task.id = delivery.task_id;

delete event
from notification_event event
join demo_review_task_ids demo_task on demo_task.id = event.task_id;

delete item
from github_comment_publication_batch_item item
join demo_review_task_ids demo_task on demo_task.id = item.task_id;

delete batch
from github_comment_publication_batch batch
join demo_review_task_ids demo_task on demo_task.id = batch.task_id;

delete publication
from github_comment_publication publication
join demo_review_task_ids demo_task on demo_task.id = publication.task_id;

delete timeline
from review_timeline timeline
join demo_review_task_ids demo_task on demo_task.id = timeline.task_id;

delete finding
from review_finding finding
join demo_review_task_ids demo_task on demo_task.id = finding.task_id;

delete changed_file_row
from changed_file changed_file_row
join demo_review_task_ids demo_task on demo_task.id = changed_file_row.task_id;

delete task
from review_task task
join demo_review_task_ids demo_task on demo_task.id = task.id;

drop temporary table if exists demo_review_task_ids;
