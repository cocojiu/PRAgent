create table if not exists github_comment_publication (
    id bigint primary key auto_increment,
    task_id bigint not null,
    finding_id bigint not null,
    target_type varchar(32) not null,
    status varchar(32) not null,
    success tinyint(1) not null default 0,
    github_comment_id bigint,
    github_url varchar(1024),
    message varchar(1024),
    published_at datetime,
    created_at datetime not null,
    updated_at datetime not null,
    unique key uk_github_comment_publication_finding (finding_id),
    key idx_github_comment_publication_task (task_id),
    key idx_github_comment_publication_status (status),
    constraint fk_github_comment_publication_task foreign key (task_id) references review_task(id),
    constraint fk_github_comment_publication_finding foreign key (finding_id) references review_finding(id)
);
