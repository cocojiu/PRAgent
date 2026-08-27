-- Monotonic fencing tokens prevent an expired owner from renewing or
-- releasing a lease after another replica has taken over the same scope.
alter table scheduled_job_lease
    add column fencing_token bigint unsigned not null default 0 after owner_id;

update scheduled_job_lease
set fencing_token = 1
where owner_id is not null;
