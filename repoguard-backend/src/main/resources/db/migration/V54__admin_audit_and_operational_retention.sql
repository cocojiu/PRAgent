create table if not exists admin_operation_audit (
    id bigint primary key auto_increment,
    actor_user_id bigint,
    actor_username varchar(255),
    action varchar(128) not null,
    target_type varchar(64) not null,
    target_id varchar(255),
    diff_json json,
    trace_id varchar(128),
    client_ip varchar(64),
    user_agent varchar(512),
    result varchar(32) not null,
    failure_category varchar(128),
    created_at datetime not null,
    key idx_admin_operation_created_at (created_at),
    key idx_admin_operation_actor_time (actor_user_id, created_at),
    key idx_admin_operation_action_time (action, created_at),
    key idx_admin_operation_target_time (target_type, target_id, created_at)
);

create table if not exists operational_data_cleanup_audit (
    id bigint primary key auto_increment,
    table_name varchar(128) not null,
    cutoff_at datetime not null,
    deleted_rows int not null,
    status varchar(32) not null,
    failure_category varchar(128),
    created_at datetime not null,
    key idx_operational_cleanup_created_at (created_at),
    key idx_operational_cleanup_table_time (table_name, created_at),
    key idx_operational_cleanup_status_time (status, created_at)
);

alter table notification_delivery_log
    add key idx_notification_delivery_created_at (created_at);

alter table notification_event
    add key idx_notification_event_created_at (created_at);

alter table user_operation_audit
    add key idx_user_operation_audit_created_at (created_at);

alter table user_login_audit
    add key idx_user_login_audit_created_at (created_at);

alter table integration_config
    modify column updated_at datetime(6) not null;

alter table notification_channel_binding
    modify column updated_at datetime(6) not null;
