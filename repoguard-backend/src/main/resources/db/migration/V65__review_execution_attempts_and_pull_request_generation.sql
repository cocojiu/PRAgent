create table if not exists review_pull_request_head (
    organization varchar(128) not null,
    repository varchar(128) not null,
    pr_number int not null,
    latest_commit_sha varchar(64) not null,
    generation bigint unsigned not null default 1,
    updated_at datetime not null,
    primary key (organization, repository, pr_number),
    key idx_review_pr_head_updated (updated_at)
);

insert into review_pull_request_head (
    organization, repository, pr_number, latest_commit_sha, generation, updated_at
)
select
    ranked.organization,
    ranked.repository,
    ranked.pr_number,
    ranked.commit_sha,
    1,
    ranked.created_at
from (
    select
        task.organization,
        task.repository,
        task.pr_number,
        task.commit_sha,
        task.created_at,
        row_number() over (
            partition by task.organization, task.repository, task.pr_number
            order by task.created_at desc, task.id desc
        ) as head_rank
    from review_task task
    where upper(coalesce(nullif(task.trigger_source, ''), task.source, '')) = 'GITHUB_WEBHOOK'
) ranked
where ranked.head_rank = 1
on duplicate key update
    latest_commit_sha = values(latest_commit_sha),
    updated_at = values(updated_at);

alter table review_task
    add column generation bigint unsigned not null default 1 after commit_sha,
    add column current_attempt_id bigint null after review_claimed_by,
    add key idx_review_task_pr_generation (organization, repository, pr_number, generation),
    add key idx_review_task_current_attempt (current_attempt_id);

update review_task task
join review_pull_request_head head
  on head.organization = task.organization
 and head.repository = task.repository
 and head.pr_number = task.pr_number
set task.status = 'SUPERSEDED',
    task.risk_level = 'INFO',
    task.assessment_status = 'SUPERSEDED',
    task.llm_status = 'pending',
    task.llm_fallback_reason = 'Superseded while initializing pull request generation tracking',
    task.finished_at = coalesce(task.finished_at, head.updated_at),
    task.duration_seconds = greatest(
        timestampdiff(second, task.created_at, coalesce(task.finished_at, head.updated_at)),
        0
    ),
    task.publish_claimed_at = null,
    task.publish_claimed_by = null,
    task.review_claimed_at = null,
    task.review_claimed_by = null
where upper(coalesce(nullif(task.trigger_source, ''), task.source, '')) = 'GITHUB_WEBHOOK'
  and task.commit_sha <> head.latest_commit_sha
  and task.status in ('QUEUED', 'PUBLISH_FAILED', 'REQUEUE_PENDING');

create table if not exists review_execution_attempt (
    id bigint primary key auto_increment,
    task_id bigint not null,
    attempt_no int not null,
    generation bigint unsigned not null,
    commit_sha varchar(64) not null,
    input_fingerprint char(64) not null,
    claim_id varchar(64),
    worker_id varchar(128),
    status varchar(32) not null,
    failure_category varchar(64),
    budget_exhausted_stage varchar(64),
    policy_version bigint,
    prompt_version varchar(64),
    context_version varchar(64),
    schema_version varchar(64),
    verifier_version varchar(64),
    aggregation_version varchar(64),
    diff_fetch_ms bigint not null default 0,
    review_ms bigint not null default 0,
    persist_ms bigint not null default 0,
    total_ms bigint not null default 0,
    prompt_tokens int,
    completion_tokens int,
    total_tokens int,
    estimated_cost decimal(19, 8),
    queued_at datetime not null,
    started_at datetime not null,
    finished_at datetime,
    created_at datetime not null,
    unique key uk_review_execution_attempt_no (task_id, attempt_no),
    key idx_review_execution_attempt_status_started (status, started_at),
    key idx_review_execution_attempt_task_finished (task_id, finished_at),
    constraint fk_review_execution_attempt_task
        foreign key (task_id) references review_task(id) on delete cascade
);

insert into review_execution_attempt (
    task_id,
    attempt_no,
    generation,
    commit_sha,
    input_fingerprint,
    claim_id,
    worker_id,
    status,
    failure_category,
    budget_exhausted_stage,
    policy_version,
    prompt_version,
    context_version,
    schema_version,
    verifier_version,
    aggregation_version,
    diff_fetch_ms,
    review_ms,
    persist_ms,
    total_ms,
    prompt_tokens,
    completion_tokens,
    total_tokens,
    estimated_cost,
    queued_at,
    started_at,
    finished_at,
    created_at
)
select
    task.id,
    1,
    task.generation,
    task.commit_sha,
    sha2(concat_ws('|', task.organization, task.repository, task.pr_number, task.commit_sha, task.generation), 256),
    task.review_claimed_by,
    task.review_claimed_by,
    case
        when task.status_norm = 'REVIEWING' then 'RUNNING'
        when task.status_norm = 'SUPERSEDED' then 'SUPERSEDED'
        when task.status_norm = 'FAILED' then 'FAILED'
        when task.assessment_status = 'PARTIAL' then 'PARTIAL'
        when task.finished_at is not null then 'COMPLETED'
        else 'QUEUED'
    end,
    case when task.status_norm = 'FAILED' then 'LEGACY_FAILURE' else null end,
    null,
    null,
    null,
    null,
    null,
    null,
    null,
    0,
    coalesce(task.llm_duration_ms, 0),
    0,
    greatest(coalesce(task.duration_seconds, 0), 0) * 1000,
    task.llm_prompt_tokens,
    task.llm_completion_tokens,
    task.llm_total_tokens,
    task.llm_estimated_cost,
    task.created_at,
    coalesce(task.started_at, task.created_at),
    task.finished_at,
    task.created_at
from review_task task
where (
    task.started_at is not null
    or task.finished_at is not null
    or exists (select 1 from changed_file file where file.task_id = task.id)
    or exists (select 1 from review_finding finding where finding.task_id = task.id)
)
and not exists (
    select 1
    from review_execution_attempt existing
    where existing.task_id = task.id
);

update review_task task
join review_execution_attempt attempt
  on attempt.task_id = task.id
 and attempt.attempt_no = 1
set task.current_attempt_id = attempt.id
where task.current_attempt_id is null;

alter table changed_file
    add column attempt_id bigint null after task_id,
    add column current_attempt tinyint(1) not null default 1 after attempt_id;

alter table review_finding
    add column attempt_id bigint null after task_id,
    add column current_attempt tinyint(1) not null default 1 after attempt_id;

update changed_file file
join review_task task on task.id = file.task_id
set file.attempt_id = task.current_attempt_id
where file.attempt_id is null;

update review_finding finding
join review_task task on task.id = finding.task_id
set finding.attempt_id = task.current_attempt_id
where finding.attempt_id is null;

alter table changed_file
    modify column attempt_id bigint not null,
    add key idx_changed_file_current_attempt (task_id, current_attempt, id),
    add key idx_changed_file_attempt (attempt_id, id),
    add constraint fk_changed_file_attempt
        foreign key (attempt_id) references review_execution_attempt(id) on delete cascade;

alter table review_finding
    modify column attempt_id bigint not null,
    add key idx_review_finding_current_category_rule (current_attempt, category, rule_id),
    add key idx_review_finding_current_category_feedback_norm (current_attempt, category, feedback_status_norm),
    add key idx_review_finding_task_current_category_severity_norm (task_id, current_attempt, category, severity_norm),
    add key idx_review_finding_current_category_id (task_id, current_attempt, category, id),
    add key idx_review_finding_current_category_file (task_id, current_attempt, category, file_path(255)),
    add key idx_review_finding_attempt_id (attempt_id, id),
    add constraint fk_review_finding_attempt
        foreign key (attempt_id) references review_execution_attempt(id) on delete cascade;
