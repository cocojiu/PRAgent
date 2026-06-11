ALTER TABLE user_account
    ADD COLUMN failed_login_count INT NOT NULL DEFAULT 0 AFTER status,
    ADD COLUMN locked_until DATETIME NULL AFTER failed_login_count,
    ADD KEY idx_user_account_locked_until (locked_until);

CREATE TABLE IF NOT EXISTS user_login_audit (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NULL,
    account VARCHAR(255) NULL,
    event_type VARCHAR(64) NOT NULL,
    result VARCHAR(32) NOT NULL,
    failure_reason VARCHAR(255) NULL,
    client_ip VARCHAR(64) NULL,
    user_agent VARCHAR(512) NULL,
    created_at DATETIME NOT NULL,
    KEY idx_user_login_audit_user_time (user_id, created_at),
    KEY idx_user_login_audit_account_time (account, created_at),
    KEY idx_user_login_audit_event_time (event_type, created_at)
);
