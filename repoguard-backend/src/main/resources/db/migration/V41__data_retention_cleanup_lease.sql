create table if not exists data_retention_cleanup_lease (
    lock_name varchar(64) primary key,
    owner_id varchar(64) null,
    locked_until datetime not null,
    created_at datetime not null,
    updated_at datetime not null,
    key idx_data_retention_cleanup_lease_locked_until (locked_until)
);

insert into data_retention_cleanup_lease (lock_name, owner_id, locked_until, created_at, updated_at)
values ('data_retention_cleanup', null, '1970-01-01 00:00:00', now(), now())
on duplicate key update lock_name = lock_name;
