package com.repoguard.agent.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import com.repoguard.agent.dto.DashboardRuleHitCount;
import com.repoguard.agent.dto.DashboardRulesResponse;
import java.util.List;
import org.junit.jupiter.api.Test;

class DashboardRuleAssemblerTest {

    private final DashboardRuleAssembler assembler = new DashboardRuleAssembler(new DashboardRuleDisplayMapper());

    @Test
    void assemblesRuleHitsAndFailedRulesInDescendingCountOrder() {
        DashboardRulesResponse response = assembler.assemble(List.of(
            ruleHitCount("RG-API-001", 2L),
            ruleHitCount(null, 1L),
            ruleHitCount("RG-SECRET-001", 3L)
        ));

        assertThat(response.ruleHits()).extracting("name")
            .containsExactly("\u786c\u7f16\u7801\u5bc6\u94a5\u68c0\u6d4b", "Controller \u65e0\u6d4b\u8bd5", "LLM \u5ba1\u67e5");
        assertThat(response.ruleHits()).extracting("value").containsExactly(3L, 2L, 1L);
        assertThat(response.ruleHits()).extracting("percent").containsExactly("50.0%", "33.3%", "16.7%");
        assertThat(response.ruleHits()).extracting("color").containsExactly("#ef4444", "#f59e0b", "#14b8a6");
        assertThat(response.failedRules()).extracting("name")
            .containsExactly("\u786c\u7f16\u7801\u5bc6\u94a5\u68c0\u6d4b", "Controller \u65e0\u6d4b\u8bd5", "LLM \u5ba1\u67e5");
        assertThat(response.failedRules()).extracting("count").containsExactly(3L, 2L, 1L);
        assertThat(response.failedRules()).extracting("trend").containsExactly("0.0%", "0.0%", "0.0%");
        assertThat(response.failedRules()).extracting("direction").containsExactly("down", "down", "down");
    }

    @Test
    void treatsNullTotalsAndSourceAsZeroOrEmpty() {
        DashboardRulesResponse response = assembler.assemble(List.of(ruleHitCount("RG-API-001", null)));

        assertThat(response.ruleHits()).extracting("value").containsExactly(0L);
        assertThat(response.ruleHits()).extracting("percent").containsExactly("0.0%");
        assertThat(assembler.assemble(null).ruleHits()).isEmpty();
        assertThat(assembler.assemble(null).failedRules()).isEmpty();
    }

    private DashboardRuleHitCount ruleHitCount(String ruleId, Long total) {
        DashboardRuleHitCount count = new DashboardRuleHitCount();
        count.setRuleId(ruleId);
        count.setTotal(total);
        return count;
    }
}
