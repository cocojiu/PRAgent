create table if not exists review_repository_dimension (
    id bigint auto_increment primary key,
    organization varchar(128) not null,
    repository varchar(128) not null,
    repository_label varchar(257) not null,
    first_seen_at datetime not null,
    last_seen_at datetime not null,
    task_count bigint not null default 0,
    active tinyint(1) not null default 1,
    created_at datetime not null default current_timestamp,
    updated_at datetime not null default current_timestamp on update current_timestamp,
    unique key uk_review_repository_dimension_org_repo (organization, repository),
    key idx_review_repository_dimension_label (repository_label),
    key idx_review_repository_dimension_repo (repository)
);

insert into review_repository_dimension (
    organization,
    repository,
    repository_label,
    first_seen_at,
    last_seen_at,
    task_count,
    active,
    created_at,
    updated_at
)
select
    trim(organization) as organization,
    trim(repository) as repository,
    concat(trim(organization), '/', trim(repository)) as repository_label,
    min(coalesce(created_at, now())) as first_seen_at,
    max(coalesce(created_at, now())) as last_seen_at,
    count(*) as task_count,
    1 as active,
    now() as created_at,
    now() as updated_at
from review_task
where organization is not null
  and trim(organization) <> ''
  and repository is not null
  and trim(repository) <> ''
group by trim(organization), trim(repository), concat(trim(organization), '/', trim(repository))
on duplicate key update
    repository_label = values(repository_label),
    first_seen_at = least(first_seen_at, values(first_seen_at)),
    last_seen_at = greatest(last_seen_at, values(last_seen_at)),
    task_count = values(task_count),
    active = 1,
    updated_at = values(updated_at);
