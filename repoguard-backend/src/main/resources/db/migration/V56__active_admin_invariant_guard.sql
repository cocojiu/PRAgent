create table if not exists user_management_guard (
    lock_name varchar(64) primary key,
    updated_at datetime not null
);

insert ignore into user_management_guard (lock_name, updated_at)
values ('active_admin', current_timestamp);
