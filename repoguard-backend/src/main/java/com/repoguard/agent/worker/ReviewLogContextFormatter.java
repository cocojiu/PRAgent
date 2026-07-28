package com.repoguard.agent.worker;

import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.review.task.ReviewTaskMessage;
import org.springframework.stereotype.Component;

@Component
class ReviewLogContextFormatter {

    String repositorySlug(ReviewTask task) {
        return safePart(task.getOrganization()) + "/" + safePart(task.getRepository());
    }

    String repositorySlug(ReviewTaskMessage message) {
        return safePart(message.organization()) + "/" + safePart(message.repository());
    }

    String safePart(String value) {
        return value == null || value.isBlank() ? "<unknown>" : value.trim();
    }
}
