-- Preview has separate short-lived state and never creates formal review records.
CREATE TABLE preflight_preview (
    tenant_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    request_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    credential_nonce CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    repository VARCHAR(141) NOT NULL,
    llm_requested BOOLEAN NOT NULL DEFAULT FALSE,
    status VARCHAR(16) NOT NULL,
    response_ciphertext MEDIUMTEXT NULL,
    created_at DATETIME(6) NOT NULL,
    response_expires_at DATETIME(6) NOT NULL,
    dedup_expires_at DATETIME(6) NOT NULL,
    PRIMARY KEY (tenant_id, user_id, request_sha256),
    UNIQUE KEY uk_preflight_credential (credential_nonce),
    KEY idx_preflight_admission (tenant_id, created_at, llm_requested),
    KEY idx_preflight_response_expiry (tenant_id, response_expires_at),
    KEY idx_preflight_dedup_expiry (tenant_id, dedup_expires_at),
    CONSTRAINT fk_preflight_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id),
    CONSTRAINT fk_preflight_user FOREIGN KEY (user_id) REFERENCES user_account (id),
    CONSTRAINT ck_preflight_status CHECK (status IN ('RUNNING', 'COMPLETED', 'FAILED'))
) ENGINE=InnoDB;
