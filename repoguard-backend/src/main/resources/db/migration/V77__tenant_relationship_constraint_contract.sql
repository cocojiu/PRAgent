-- Contract phase for tenant parent-child relationships.
-- Run during a write-quiesced maintenance window: MySQL DDL commits per ALTER TABLE.

drop table if exists flyway_v77_tenant_relation_violation;

create table flyway_v77_tenant_relation_violation (
    relationship_name varchar(128) not null,
    violation_count bigint unsigned not null,
    primary key (relationship_name)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;

insert into flyway_v77_tenant_relation_violation (relationship_name, violation_count)
select 'changed_file.task_id', count(*) from changed_file c left join review_task p on p.tenant_id = c.tenant_id and p.id = c.task_id where p.id is null
union all select 'review_finding.task_id', count(*) from review_finding c left join review_task p on p.tenant_id = c.tenant_id and p.id = c.task_id where p.id is null
union all select 'review_timeline.task_id', count(*) from review_timeline c left join review_task p on p.tenant_id = c.tenant_id and p.id = c.task_id where p.id is null
union all select 'review_execution_attempt.task_id', count(*) from review_execution_attempt c left join review_task p on p.tenant_id = c.tenant_id and p.id = c.task_id where p.id is null
union all select 'changed_file.attempt_id', count(*) from changed_file c left join review_execution_attempt p on p.tenant_id = c.tenant_id and p.id = c.attempt_id where c.attempt_id is not null and p.id is null
union all select 'review_finding.attempt_id', count(*) from review_finding c left join review_execution_attempt p on p.tenant_id = c.tenant_id and p.id = c.attempt_id where c.attempt_id is not null and p.id is null
union all select 'github_comment_publication.task_id', count(*) from github_comment_publication c left join review_task p on p.tenant_id = c.tenant_id and p.id = c.task_id where p.id is null
union all select 'github_comment_publication.finding_id', count(*) from github_comment_publication c left join review_finding p on p.tenant_id = c.tenant_id and p.id = c.finding_id where c.finding_id is not null and p.id is null
union all select 'github_comment_publication_batch.task_id', count(*) from github_comment_publication_batch c left join review_task p on p.tenant_id = c.tenant_id and p.id = c.task_id where p.id is null
union all select 'github_comment_publication_batch_item.batch_id', count(*) from github_comment_publication_batch_item c left join github_comment_publication_batch p on p.tenant_id = c.tenant_id and p.id = c.batch_id where p.id is null
union all select 'github_comment_publication_batch_item.task_id', count(*) from github_comment_publication_batch_item c left join review_task p on p.tenant_id = c.tenant_id and p.id = c.task_id where p.id is null
union all select 'github_comment_publication_batch_item.finding_id', count(*) from github_comment_publication_batch_item c left join review_finding p on p.tenant_id = c.tenant_id and p.id = c.finding_id where c.finding_id is not null and p.id is null
union all select 'notification_event.task_id', count(*) from notification_event c left join review_task p on p.tenant_id = c.tenant_id and p.id = c.task_id where p.id is null
union all select 'notification_event.batch_id', count(*) from notification_event c left join github_comment_publication_batch p on p.tenant_id = c.tenant_id and p.id = c.batch_id where c.batch_id is not null and p.id is null
union all select 'notification_delivery_log.event_id', count(*) from notification_delivery_log c left join notification_event p on p.tenant_id = c.tenant_id and p.id = c.event_id where p.id is null
union all select 'notification_delivery_log.binding_id', count(*) from notification_delivery_log c left join notification_channel_binding p on p.tenant_id = c.tenant_id and p.id = c.binding_id where c.binding_id is not null and p.id is null
union all select 'notification_delivery_log.task_id', count(*) from notification_delivery_log c left join review_task p on p.tenant_id = c.tenant_id and p.id = c.task_id where p.id is null
union all select 'review_policy_promotion_evidence.rule_policy_snapshot_id', count(*) from review_policy_promotion_evidence c left join review_rule_policy_snapshot p on p.tenant_id = c.tenant_id and p.id = c.rule_policy_snapshot_id where c.rule_policy_snapshot_id is not null and p.id is null
union all select 'review_policy_promotion_evidence.strategy_policy_snapshot_id', count(*) from review_policy_promotion_evidence c left join review_strategy_policy_snapshot p on p.tenant_id = c.tenant_id and p.id = c.strategy_policy_snapshot_id where c.strategy_policy_snapshot_id is not null and p.id is null
union all select 'review_strategy_policy_snapshot.source_snapshot_id', count(*) from review_strategy_policy_snapshot c left join review_strategy_policy_snapshot p on p.tenant_id = c.tenant_id and p.id = c.source_snapshot_id where c.source_snapshot_id is not null and p.id is null;

alter table flyway_v77_tenant_relation_violation
    add constraint chk_v77_tenant_relation_clean check (violation_count = 0);

drop table flyway_v77_tenant_relation_violation;

alter table notification_event
    add index idx_notification_event_tenant_task (tenant_id, task_id),
    add index idx_notification_event_tenant_batch (tenant_id, batch_id);

alter table notification_delivery_log
    add index idx_notification_delivery_tenant_task (tenant_id, task_id);

alter table changed_file
    drop foreign key fk_changed_file_task,
    drop foreign key fk_changed_file_attempt,
    add constraint fk_changed_file_tenant_task foreign key (tenant_id, task_id) references review_task (tenant_id, id),
    add constraint fk_changed_file_tenant_attempt foreign key (tenant_id, attempt_id) references review_execution_attempt (tenant_id, id) on delete cascade;

alter table review_finding
    drop foreign key fk_review_finding_task,
    drop foreign key fk_review_finding_attempt,
    add constraint fk_review_finding_tenant_task foreign key (tenant_id, task_id) references review_task (tenant_id, id),
    add constraint fk_review_finding_tenant_attempt foreign key (tenant_id, attempt_id) references review_execution_attempt (tenant_id, id) on delete cascade;

alter table review_timeline
    drop foreign key fk_review_timeline_task,
    add constraint fk_review_timeline_tenant_task foreign key (tenant_id, task_id) references review_task (tenant_id, id);

alter table review_execution_attempt
    drop foreign key fk_review_execution_attempt_task,
    add constraint fk_review_attempt_tenant_task foreign key (tenant_id, task_id) references review_task (tenant_id, id) on delete cascade;

alter table github_comment_publication
    drop foreign key fk_github_comment_publication_task,
    drop foreign key fk_github_comment_publication_finding,
    add constraint fk_github_comment_pub_tenant_task foreign key (tenant_id, task_id) references review_task (tenant_id, id),
    add constraint fk_github_comment_pub_tenant_finding foreign key (tenant_id, finding_id) references review_finding (tenant_id, id);

alter table github_comment_publication_batch
    drop foreign key fk_github_comment_publication_batch_task,
    add constraint fk_github_comment_batch_tenant_task foreign key (tenant_id, task_id) references review_task (tenant_id, id);

alter table github_comment_publication_batch_item
    drop foreign key fk_github_comment_publication_batch_item_batch,
    drop foreign key fk_github_comment_publication_batch_item_task,
    drop foreign key fk_github_comment_publication_batch_item_finding,
    add constraint fk_github_comment_item_tenant_batch foreign key (tenant_id, batch_id) references github_comment_publication_batch (tenant_id, id),
    add constraint fk_github_comment_item_tenant_task foreign key (tenant_id, task_id) references review_task (tenant_id, id),
    add constraint fk_github_comment_item_tenant_finding foreign key (tenant_id, finding_id) references review_finding (tenant_id, id);

alter table notification_event
    add constraint fk_notification_event_tenant_task foreign key (tenant_id, task_id) references review_task (tenant_id, id),
    add constraint fk_notification_event_tenant_batch foreign key (tenant_id, batch_id) references github_comment_publication_batch (tenant_id, id);

alter table notification_delivery_log
    drop foreign key fk_notification_delivery_event,
    drop foreign key fk_notification_delivery_binding,
    add constraint fk_notification_delivery_tenant_event foreign key (tenant_id, event_id) references notification_event (tenant_id, id),
    add constraint fk_notification_delivery_tenant_binding foreign key (tenant_id, binding_id) references notification_channel_binding (tenant_id, id),
    add constraint fk_notification_delivery_tenant_task foreign key (tenant_id, task_id) references review_task (tenant_id, id);

alter table review_policy_promotion_evidence
    drop foreign key fk_review_policy_evidence_rule_snapshot,
    drop foreign key fk_review_policy_evidence_strategy_snapshot,
    add constraint fk_policy_evidence_tenant_rule foreign key (tenant_id, rule_policy_snapshot_id) references review_rule_policy_snapshot (tenant_id, id) on delete restrict,
    add constraint fk_policy_evidence_tenant_strategy foreign key (tenant_id, strategy_policy_snapshot_id) references review_strategy_policy_snapshot (tenant_id, id) on delete restrict;

alter table review_strategy_policy_snapshot
    drop foreign key fk_review_strategy_policy_source,
    add constraint fk_strategy_policy_tenant_source foreign key (tenant_id, source_snapshot_id) references review_strategy_policy_snapshot (tenant_id, id);
