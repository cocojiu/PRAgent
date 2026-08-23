package com.repoguard.agent.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class ReviewRepositoryDimensionTest {

    @Test
    void exposesPersistedRepositoryDimensionFields() {
        LocalDateTime firstSeen = LocalDateTime.parse("2026-08-20T10:00:00");
        LocalDateTime lastSeen = firstSeen.plusDays(1);
        LocalDateTime createdAt = firstSeen.minusDays(1);
        LocalDateTime updatedAt = lastSeen.plusHours(1);
        ReviewRepositoryDimension dimension = new ReviewRepositoryDimension();

        dimension.setId(7L);
        dimension.setOrganization("octocat");
        dimension.setRepository("Hello-World");
        dimension.setRepositoryLabel("octocat/Hello-World");
        dimension.setFirstSeenAt(firstSeen);
        dimension.setLastSeenAt(lastSeen);
        dimension.setTaskCount(9L);
        dimension.setActive(true);
        dimension.setCreatedAt(createdAt);
        dimension.setUpdatedAt(updatedAt);

        assertThat(dimension.getId()).isEqualTo(7L);
        assertThat(dimension.getOrganization()).isEqualTo("octocat");
        assertThat(dimension.getRepository()).isEqualTo("Hello-World");
        assertThat(dimension.getRepositoryLabel()).isEqualTo("octocat/Hello-World");
        assertThat(dimension.getFirstSeenAt()).isEqualTo(firstSeen);
        assertThat(dimension.getLastSeenAt()).isEqualTo(lastSeen);
        assertThat(dimension.getTaskCount()).isEqualTo(9L);
        assertThat(dimension.getActive()).isTrue();
        assertThat(dimension.getCreatedAt()).isEqualTo(createdAt);
        assertThat(dimension.getUpdatedAt()).isEqualTo(updatedAt);
    }
}
