delete item
from github_comment_publication_batch_item item
join review_task task on task.id = item.task_id
where task.id in (9001, 9002, 9003, 9004)
  and task.organization = 'repo-guard-demo'
  and task.commit_sha in ('demo901a', 'demo902b', 'demo903c', 'demo904d');

delete batch
from github_comment_publication_batch batch
join review_task task on task.id = batch.task_id
where task.id in (9001, 9002, 9003, 9004)
  and task.organization = 'repo-guard-demo'
  and task.commit_sha in ('demo901a', 'demo902b', 'demo903c', 'demo904d');

delete publication
from github_comment_publication publication
join review_task task on task.id = publication.task_id
where task.id in (9001, 9002, 9003, 9004)
  and task.organization = 'repo-guard-demo'
  and task.commit_sha in ('demo901a', 'demo902b', 'demo903c', 'demo904d');

delete timeline
from review_timeline timeline
join review_task task on task.id = timeline.task_id
where task.id in (9001, 9002, 9003, 9004)
  and task.organization = 'repo-guard-demo'
  and task.commit_sha in ('demo901a', 'demo902b', 'demo903c', 'demo904d');

delete finding
from review_finding finding
join review_task task on task.id = finding.task_id
where task.id in (9001, 9002, 9003, 9004)
  and task.organization = 'repo-guard-demo'
  and task.commit_sha in ('demo901a', 'demo902b', 'demo903c', 'demo904d');

delete changed_file_row
from changed_file changed_file_row
join review_task task on task.id = changed_file_row.task_id
where task.id in (9001, 9002, 9003, 9004)
  and task.organization = 'repo-guard-demo'
  and task.commit_sha in ('demo901a', 'demo902b', 'demo903c', 'demo904d');

delete from review_task
where id in (9001, 9002, 9003, 9004)
  and organization = 'repo-guard-demo'
  and commit_sha in ('demo901a', 'demo902b', 'demo903c', 'demo904d');
