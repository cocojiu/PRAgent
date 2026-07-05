alter table user_account
    add column session_version int not null default 0 after locked_until;
