package com.repoguard.agent.config;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Component
@Validated
@ConfigurationProperties(prefix = "repoguard.schema")
public class SchemaVersionProperties {

    @Min(1)
    private int expectedVersion = 57;

    public int getExpectedVersion() {
        return expectedVersion;
    }

    public void setExpectedVersion(int expectedVersion) {
        this.expectedVersion = expectedVersion;
    }
}
