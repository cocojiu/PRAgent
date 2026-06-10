create table if not exists user_account (
    id bigint primary key auto_increment,
    username varchar(64) not null,
    email varchar(255) not null,
    password_hash varchar(512) not null,
    role varchar(32) not null default 'ADMIN',
    status varchar(32) not null default 'ACTIVE',
    last_login_at datetime,
    created_at datetime not null,
    updated_at datetime not null,
    unique key uk_user_account_username (username),
    unique key uk_user_account_email (email),
    key idx_user_account_status (status),
    key idx_user_account_created_at (created_at)
);
