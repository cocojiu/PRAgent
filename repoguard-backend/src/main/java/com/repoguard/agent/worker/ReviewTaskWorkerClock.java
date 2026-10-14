package com.repoguard.agent.worker;

import org.springframework.stereotype.Component;

@Component
class ReviewTaskWorkerClock {
    java.time.Instant utcNow() { return java.time.Instant.now(); }

    long nanoTime() {
        return System.nanoTime();
    }
}
