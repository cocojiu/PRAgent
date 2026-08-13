package com.repoguard.agent.security;

enum SecretReEncryptionJobTable {
    INTEGRATION_CONFIG("integration_config"),
    REVIEW_POLICY_CONFIG("review_policy_config"),
    NOTIFICATION_CHANNEL_BINDING("notification_channel_binding"),
    DONE("done");

    private final String value;

    SecretReEncryptionJobTable(String value) {
        this.value = value;
    }

    String value() {
        return value;
    }

    static SecretReEncryptionJobTable fromValue(String value) {
        for (SecretReEncryptionJobTable table : values()) {
            if (table.value.equalsIgnoreCase(value)) {
                return table;
            }
        }
        throw new IllegalStateException("Unknown secret re-encryption table: " + value);
    }

    SecretReEncryptionJobTable next() {
        return switch (this) {
            case INTEGRATION_CONFIG -> REVIEW_POLICY_CONFIG;
            case REVIEW_POLICY_CONFIG -> NOTIFICATION_CHANNEL_BINDING;
            case NOTIFICATION_CHANNEL_BINDING, DONE -> DONE;
        };
    }
}
