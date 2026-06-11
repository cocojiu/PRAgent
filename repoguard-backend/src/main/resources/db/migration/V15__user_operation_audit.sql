CREATE TABLE IF NOT EXISTS user_operation_audit (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    operator_user_id BIGINT NULL,
    operator_username VARCHAR(255) NULL,
    target_user_id BIGINT NOT NULL,
    target_username VARCHAR(255) NOT NULL,
    action VARCHAR(64) NOT NULL,
    before_value VARCHAR(255) NULL,
    after_value VARCHAR(255) NULL,
    client_ip VARCHAR(64) NULL,
    user_agent VARCHAR(512) NULL,
    created_at DATETIME NOT NULL,
    KEY idx_user_operation_audit_operator_time (operator_user_id, created_at),
    KEY idx_user_operation_audit_target_time (target_user_id, created_at),
    KEY idx_user_operation_audit_action_time (action, created_at)
);
