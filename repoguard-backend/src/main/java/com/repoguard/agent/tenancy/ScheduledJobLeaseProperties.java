package com.repoguard.agent.tenancy;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.AssertTrue;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Component
@Validated
@ConfigurationProperties(prefix = "repoguard.scheduling")
public class ScheduledJobLeaseProperties {

    @Min(30)
    private long leaseSeconds = 900;

    @Min(1)
    private long heartbeatSeconds = 60;

    @Min(1)
    private int heartbeatThreads = 2;

    public long getLeaseSeconds() {
        return leaseSeconds;
    }

    public void setLeaseSeconds(long leaseSeconds) {
        this.leaseSeconds = leaseSeconds;
    }

    public long getHeartbeatSeconds() {
        return heartbeatSeconds;
    }

    public void setHeartbeatSeconds(long heartbeatSeconds) {
        this.heartbeatSeconds = heartbeatSeconds;
    }

    public int getHeartbeatThreads() {
        return heartbeatThreads;
    }

    public void setHeartbeatThreads(int heartbeatThreads) {
        this.heartbeatThreads = heartbeatThreads;
    }

    @AssertTrue(message = "heartbeatSeconds must be less than leaseSeconds")
    public boolean isHeartbeatWithinLease() {
        return heartbeatSeconds < leaseSeconds;
    }
}
