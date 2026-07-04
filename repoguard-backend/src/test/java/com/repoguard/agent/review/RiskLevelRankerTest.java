package com.repoguard.agent.review;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RiskLevelRankerTest {

    private final RiskLevelRanker ranker = new RiskLevelRanker();

    @Test
    void ranksKnownRiskLevelsInAscendingSeverity() {
        assertThat(ranker.rank("INFO")).isLessThan(ranker.rank("LOW"));
        assertThat(ranker.rank("LOW")).isLessThan(ranker.rank("MEDIUM"));
        assertThat(ranker.rank("MEDIUM")).isLessThan(ranker.rank("HIGH"));
        assertThat(ranker.rank("HIGH")).isLessThan(ranker.rank("CRITICAL"));
    }

    @Test
    void treatsNullBlankAndUnknownRiskAsLowestRank() {
        assertThat(ranker.rank(null)).isZero();
        assertThat(ranker.rank("unknown")).isZero();
        assertThat(ranker.rank(" ")).isZero();
    }

    @Test
    void comparesRiskLevelsBySharedOrdering() {
        assertThat(ranker.higher("LOW", " high ")).isEqualTo(" high ");
        assertThat(ranker.higher("MEDIUM", "unknown")).isEqualTo("MEDIUM");
        assertThat(ranker.atLeast("MEDIUM", "MEDIUM")).isTrue();
        assertThat(ranker.atLeast("LOW", "MEDIUM")).isFalse();
    }
}
