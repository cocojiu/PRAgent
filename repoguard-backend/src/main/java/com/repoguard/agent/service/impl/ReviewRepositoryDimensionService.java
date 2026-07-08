package com.repoguard.agent.service.impl;

import com.repoguard.agent.mapper.ReviewRepositoryDimensionMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class ReviewRepositoryDimensionService {

    private final ReviewRepositoryDimensionMapper repositoryDimensionMapper;

    public ReviewRepositoryDimensionService(ReviewRepositoryDimensionMapper repositoryDimensionMapper) {
        this.repositoryDimensionMapper = Objects.requireNonNull(
            repositoryDimensionMapper,
            "repositoryDimensionMapper must not be null"
        );
    }

    public void recordRepository(String organization, String repository, LocalDateTime seenAt) {
        String normalizedOrganization = trimToNull(organization);
        String normalizedRepository = trimToNull(repository);
        if (normalizedOrganization == null || normalizedRepository == null) {
            return;
        }
        repositoryDimensionMapper.upsertRepository(
            normalizedOrganization,
            normalizedRepository,
            seenAt == null ? LocalDateTime.now() : seenAt
        );
    }

    public List<String> listRepositoryLabels() {
        List<String> labels = repositoryDimensionMapper.selectActiveRepositoryLabels();
        return labels == null ? List.of() : labels;
    }

    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
