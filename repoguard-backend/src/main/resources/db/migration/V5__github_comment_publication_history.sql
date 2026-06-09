create table if not exists github_comment_publication_batch (
    id bigint primary key auto_increment,
    task_id bigint not null,
    status varchar(32) not null,
    total_findings int not null default 0,
    attempted_count int not null default 0,
    succeeded_count int not null default 0,
    failed_count int not null default 0,
    skipped_count int not null default 0,
    created_at datetime not null,
    completed_at datetime not null,
    key idx_github_comment_publication_batch_task (task_id),
    key idx_github_comment_publication_batch_status (status),
    constraint fk_github_comment_publication_batch_task foreign key (task_id) references review_task(id)
);

create table if not exists github_comment_publication_batch_item (
    id bigint primary key auto_increment,
    batch_id bigint not null,
    task_id bigint not null,
    finding_id bigint not null,
    file_path varchar(512),
    line_number int,
    target_type varchar(32) not null,
    status varchar(64) not null,
    success tinyint(1) not null default 0,
    github_comment_id bigint,
    github_url varchar(1024),
    message varchar(1024),
    published_at datetime,
    created_at datetime not null,
    key idx_github_comment_publication_batch_item_batch (batch_id),
    key idx_github_comment_publication_batch_item_task (task_id),
    key idx_github_comment_publication_batch_item_finding (finding_id),
    key idx_github_comment_publication_batch_item_status (status),
    constraint fk_github_comment_publication_batch_item_batch foreign key (batch_id) references github_comment_publication_batch(id),
    constraint fk_github_comment_publication_batch_item_task foreign key (task_id) references review_task(id),
    constraint fk_github_comment_publication_batch_item_finding foreign key (finding_id) references review_finding(id)
);
