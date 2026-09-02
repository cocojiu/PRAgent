-- Collaboration and notification workflow state. All rows are tenant scoped.
alter table review_task
    add column review_assignee varchar(128) null after human_reviewed_at,
    add column review_assigned_at datetime null after review_assignee,
    add column review_sla_deadline datetime null after review_assigned_at,
    add column review_escalation_level int not null default 0 after review_sla_deadline,
    add column review_last_escalated_at datetime null after review_escalation_level,
    add key idx_review_task_tenant_review_sla (tenant_id, human_review_status, review_sla_deadline),
    add key idx_review_task_tenant_assignee (tenant_id, review_assignee, human_review_status);

create table notification_read_state (
    id bigint primary key auto_increment,
    tenant_id bigint not null,
    reader_key varchar(128) not null,
    notification_key varchar(191) not null,
    read_at datetime not null,
    created_at datetime not null default current_timestamp,
    updated_at datetime not null default current_timestamp,
    unique key uk_notification_read_state (tenant_id, reader_key, notification_key),
    key idx_notification_read_state_reader (tenant_id, reader_key, read_at),
    constraint fk_notification_read_state_tenant foreign key (tenant_id) references tenant(id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;

create table review_bot_command_audit (
    id bigint primary key auto_increment,
    tenant_id bigint not null,
    provider varchar(32) not null,
    external_command_id varchar(128) not null,
    command_text varchar(512) not null,
    actor_key varchar(128) not null,
    task_id bigint null,
    status varchar(32) not null,
    response_message varchar(1024) not null,
    created_at datetime not null default current_timestamp,
    unique key uk_review_bot_command (tenant_id, provider, external_command_id),
    key idx_review_bot_command_task (tenant_id, task_id, created_at),
    constraint fk_review_bot_command_tenant foreign key (tenant_id) references tenant(id),
    constraint fk_review_bot_command_task foreign key (tenant_id, task_id) references review_task(tenant_id, id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;
