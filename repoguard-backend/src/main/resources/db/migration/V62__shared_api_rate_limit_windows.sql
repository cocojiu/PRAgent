CREATE TABLE api_rate_limit_window (
    rate_limit_scope VARCHAR(64) NOT NULL,
    bucket_key BINARY(32) NOT NULL,
    window_epoch_minute BIGINT UNSIGNED NOT NULL,
    request_count BIGINT UNSIGNED NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (rate_limit_scope, bucket_key, window_epoch_minute),
    KEY idx_api_rate_limit_window_expiry (window_epoch_minute)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
