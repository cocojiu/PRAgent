package com.repoguard.agent.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DashboardRuleDisplayMapperTest {

    private final DashboardRuleDisplayMapper mapper = new DashboardRuleDisplayMapper();

    @Test
    void mapsKnownRulesToDisplayNamesAndColors() {
        assertThat(mapper.ruleName("RG-SECRET-001")).isEqualTo("\u786c\u7f16\u7801\u5bc6\u94a5\u68c0\u6d4b");
        assertThat(mapper.ruleColor("RG-SECRET-001")).isEqualTo("#ef4444");
        assertThat(mapper.ruleName("RG-API-001")).isEqualTo("Controller \u65e0\u6d4b\u8bd5");
        assertThat(mapper.ruleColor("RG-API-001")).isEqualTo("#f59e0b");
        assertThat(mapper.ruleName("RG-CLEAN-001")).isEqualTo("TODO/FIXME/System.out");
        assertThat(mapper.ruleColor("RG-CLEAN-001")).isEqualTo("#6366f1");
    }

    @Test
    void mapsMissingRulesToLlmDisplay() {
        assertThat(mapper.normalizedRuleId(null)).isEqualTo("LLM");
        assertThat(mapper.normalizedRuleId("  ")).isEqualTo("LLM");
        assertThat(mapper.ruleName(null)).isEqualTo("LLM \u5ba1\u67e5");
        assertThat(mapper.ruleName("  ")).isEqualTo("LLM \u5ba1\u67e5");
        assertThat(mapper.ruleColor(null)).isEqualTo("#14b8a6");
        assertThat(mapper.ruleColor("  ")).isEqualTo("#14b8a6");
    }

    @Test
    void keepsUnknownRuleIdAsDisplayNameWithDefaultColor() {
        assertThat(mapper.normalizedRuleId(" CUSTOM-RULE ")).isEqualTo("CUSTOM-RULE");
        assertThat(mapper.ruleName(" CUSTOM-RULE ")).isEqualTo("CUSTOM-RULE");
        assertThat(mapper.ruleColor(" CUSTOM-RULE ")).isEqualTo("#14b8a6");
    }
}
