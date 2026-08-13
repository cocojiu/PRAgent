create table if not exists review_quality_baseline_snapshot (
    snapshot_key varchar(32) primary key,
    source_version bigint unsigned not null default 1,
    refreshed_version bigint unsigned not null default 0,
    baseline_payload json null,
    calculated_at datetime(6) null,
    updated_at datetime(6) not null default current_timestamp(6) on update current_timestamp(6),
    key idx_review_quality_baseline_snapshot_updated (updated_at)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;

insert into review_quality_baseline_snapshot (
    snapshot_key,
    source_version,
    refreshed_version,
    baseline_payload,
    calculated_at
) values ('GLOBAL', 1, 0, null, null)
on duplicate key update
    source_version = greatest(source_version, 1);
