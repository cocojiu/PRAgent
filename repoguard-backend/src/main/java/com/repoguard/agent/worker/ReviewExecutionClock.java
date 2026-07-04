package com.repoguard.agent.worker;

import java.time.LocalDateTime;
import org.springframework.stereotype.Component;

@Component
class ReviewExecutionClock {

    LocalDateTime now() {
        return LocalDateTime.now();
    }
}
