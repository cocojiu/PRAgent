CREATE TABLE IF NOT EXISTS user_refresh_token (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    token_hash VARCHAR(128) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    expires_at DATETIME NOT NULL,
    revoked_at DATETIME NULL,
    last_used_at DATETIME NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    UNIQUE KEY uk_user_refresh_token_hash (token_hash),
    KEY idx_user_refresh_token_user_status (user_id, status),
    KEY idx_user_refresh_token_expires_at (expires_at)
);
