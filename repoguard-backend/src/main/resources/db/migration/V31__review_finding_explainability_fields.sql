alter table review_finding
    add column confidence varchar(16) not null default 'LOW' after recommendation,
    add column evidence varchar(1024) not null default '' after confidence,
    add column impact varchar(1024) not null default '' after evidence,
    add column fix_example varchar(1024) not null default '' after impact,
    add column is_blocking tinyint(1) not null default 0 after fix_example,
    add column review_dimension varchar(64) not null default '' after is_blocking;

create index idx_review_finding_blocking on review_finding (is_blocking);
create index idx_review_finding_dimension on review_finding (review_dimension);
