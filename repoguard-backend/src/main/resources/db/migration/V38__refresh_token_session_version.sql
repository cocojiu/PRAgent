alter table user_refresh_token
    add column session_version int not null default 0 after token_hash;
