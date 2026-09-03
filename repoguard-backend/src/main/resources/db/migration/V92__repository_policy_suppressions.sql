-- Versioned repository policy suppression proposals and lifecycle audit.
-- Suppressions are tenant/repository scoped and remain inactive until an administrator approves them.
create table review_repository_suppression (
    id bigint not null auto_increment,
    tenant_id bigint not null,
    organization varchar(128) not null,
    repository varchar(255) not null,
    rule_id varchar(96) not null,
    file_glob varchar(256) null,
    symbol varchar(256) null,
    reason varchar(512) not null,
    status varchar(16) not null default 'PROPOSED',
    operator varchar(128) not null,
    expires_at datetime(6) not null,
    preview_hit_count int unsigned not null default 0,
    hit_count bigint unsigned not null default 0,
    last_hit_at datetime(6) null,
    created_at datetime(6) not null default current_timestamp(6),
    updated_at datetime(6) not null default current_timestamp(6),
    primary key (id),
    unique key uk_review_repository_suppression_tenant_id (tenant_id, id),
    key idx_review_repository_suppression_scope (
        tenant_id, organization, repository, status, expires_at, id
    ),
    key idx_review_repository_suppression_rule (
        tenant_id, organization, repository, rule_id, status
    ),
    constraint fk_review_repository_suppression_tenant
        foreign key (tenant_id) references tenant(id),
    constraint chk_review_repository_suppression_status
        check (status in ('PROPOSED', 'ACTIVE', 'REVOKED', 'EXPIRED')),
    constraint chk_review_repository_suppression_scope
        check (file_glob is not null or symbol is not null),
    constraint chk_review_repository_suppression_hit_count
        check (hit_count >= 0 and preview_hit_count >= 0)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;

create table review_repository_suppression_audit (
    id bigint not null auto_increment,
    tenant_id bigint not null,
    suppression_id bigint not null,
    action varchar(32) not null,
    operator varchar(128) not null,
    reason varchar(512) null,
    created_at datetime(6) not null default current_timestamp(6),
    primary key (id),
    unique key uk_review_repository_suppression_audit_tenant_id (tenant_id, id),
    key idx_review_repository_suppression_audit_scope (tenant_id, suppression_id, created_at, id),
    constraint fk_review_repository_suppression_audit_tenant
        foreign key (tenant_id) references tenant(id),
    constraint fk_review_repository_suppression_audit_suppression
        foreign key (tenant_id, suppression_id)
        references review_repository_suppression(tenant_id, id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;
