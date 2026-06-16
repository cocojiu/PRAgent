package com.repoguard.agent.dto;

public class DashboardRuleHitCount {

    private String ruleId;
    private Long total;

    public String getRuleId() {
        return ruleId;
    }

    public void setRuleId(String ruleId) {
        this.ruleId = ruleId;
    }

    public Long getTotal() {
        return total;
    }

    public void setTotal(Long total) {
        this.total = total;
    }
}
