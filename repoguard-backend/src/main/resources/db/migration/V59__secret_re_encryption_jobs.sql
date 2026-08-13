create table if not exists secret_re_encryption_job (
    id bigint primary key auto_increment,
    mode varchar(16) not null,
    status varchar(32) not null,
    source_key_id varchar(64) not null,
    target_key_id varchar(64) not null,
    source_key_ciphertext text,
    target_key_ciphertext text,
    current_table varchar(64) not null,
    checkpoint_id bigint not null default 0,
    batch_size int not null,
    scanned_count bigint not null default 0,
    re_encrypted_count bigint not null default 0,
    skipped_count bigint not null default 0,
    failed_count bigint not null default 0,
    retry_count int not null default 0,
    next_retry_at datetime(6),
    claimed_by varchar(128),
    claimed_at datetime(6),
    lease_until datetime(6),
    last_failure_reason varchar(128),
    last_failure_message varchar(512),
    created_by_user_id bigint,
    created_by_username varchar(255),
    created_at datetime(6) not null,
    updated_at datetime(6) not null,
    completed_at datetime(6),
    active_slot tinyint generated always as (
        case
            when status in ('PENDING', 'RUNNING', 'RETRY_WAIT', 'PAUSED') then 1
            else null
        end
    ) stored,
    unique key uk_secret_re_encryption_active_slot (active_slot),
    key idx_secret_re_encryption_due (status, next_retry_at, lease_until, id),
    key idx_secret_re_encryption_created (created_at, id)
);

create table if not exists secret_re_encryption_job_item (
    id bigint primary key auto_increment,
    job_id bigint not null,
    table_name varchar(64) not null,
    record_id bigint not null,
    field_name varchar(64) not null,
    provider varchar(128),
    source_format varchar(32),
    source_key_id varchar(64),
    target_key_id varchar(64) not null,
    status varchar(32) not null,
    failure_reason varchar(128),
    message varchar(512),
    created_at datetime(6) not null,
    unique key uk_secret_re_encryption_job_field (job_id, table_name, record_id, field_name),
    key idx_secret_re_encryption_job_item_page (job_id, id),
    constraint fk_secret_re_encryption_job_item_job
        foreign key (job_id) references secret_re_encryption_job(id) on delete cascade
);
