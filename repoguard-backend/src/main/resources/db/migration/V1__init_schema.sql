create table if not exists review_task (
    id bigint primary key auto_increment,
    pr_number int not null,
    title varchar(512) not null,
    repository varchar(128) not null,
    organization varchar(128) not null,
    commit_sha varchar(64) not null,
    branch_name varchar(128) not null,
    status varchar(32) not null,
    risk_level varchar(32) not null,
    mq_retries int not null default 0,
    llm_status varchar(32) not null,
    pr_url varchar(1024),
    created_at datetime not null,
    started_at datetime,
    finished_at datetime,
    duration_seconds int not null default 0,
    key idx_review_task_repository (repository),
    key idx_review_task_status_created (status, created_at),
    key idx_review_task_risk_created (risk_level, created_at),
    key idx_review_task_keyword (title, commit_sha)
);

create table if not exists changed_file (
    id bigint primary key auto_increment,
    task_id bigint not null,
    file_path varchar(1024) not null,
    change_type varchar(8) not null,
    additions int not null default 0,
    deletions int not null default 0,
    key idx_changed_file_task (task_id),
    key idx_changed_file_path (file_path(255)),
    constraint fk_changed_file_task foreign key (task_id) references review_task(id)
);

create table if not exists review_finding (
    id bigint primary key auto_increment,
    task_id bigint not null,
    category varchar(32) not null,
    severity varchar(32),
    source varchar(32),
    rule_id varchar(64),
    file_path varchar(1024) not null,
    line_number int,
    message text,
    recommendation text,
    method_name varchar(512),
    test_type varchar(128),
    key idx_review_finding_task (task_id),
    key idx_review_finding_category (category),
    key idx_review_finding_severity (severity),
    key idx_review_finding_rule (rule_id),
    constraint fk_review_finding_task foreign key (task_id) references review_task(id)
);

create table if not exists review_timeline (
    id bigint primary key auto_increment,
    task_id bigint not null,
    label varchar(128) not null,
    event_time datetime not null,
    status varchar(32) not null,
    sort_order int not null,
    key idx_review_timeline_task_order (task_id, sort_order),
    constraint fk_review_timeline_task foreign key (task_id) references review_task(id)
);
