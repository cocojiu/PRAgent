package com.repoguard.agent.worker;

import org.springframework.stereotype.Component;

@Component
class ReviewTaskWorkerClock {

    long nanoTime() {
        return System.nanoTime();
    }
}
