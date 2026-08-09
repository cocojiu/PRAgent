create table if not exists dashboard_daily_snapshot_refresh_state (
    stat_date date primary key,
    review_version bigint unsigned not null default 0,
    review_refreshed_version bigint unsigned not null default 0,
    llm_quality_version bigint unsigned not null default 0,
    llm_quality_refreshed_version bigint unsigned not null default 0,
    updated_at datetime not null default current_timestamp on update current_timestamp,
    key idx_dashboard_snapshot_refresh_updated (updated_at)
);

alter table review_task
    add key idx_review_task_dashboard_stat_date (created_date);

insert into dashboard_daily_snapshot_refresh_state (
    stat_date,
    review_version,
    review_refreshed_version,
    llm_quality_version,
    llm_quality_refreshed_version
)
select
    created_date,
    1,
    0,
    1,
    0
from review_task
where created_at >= current_date - interval 89 day
group by created_date
on duplicate key update
    review_version = review_version + 1,
    llm_quality_version = llm_quality_version + 1;
