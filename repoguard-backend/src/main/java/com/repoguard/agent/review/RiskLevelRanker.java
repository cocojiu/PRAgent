package com.repoguard.agent.review;

import java.util.Locale;
import org.springframework.stereotype.Component;

@Component
public class RiskLevelRanker {

    public boolean atLeast(String riskLevel, String threshold) {
        return rank(riskLevel) >= rank(threshold);
    }

    public String higher(String current, String candidate) {
        return rank(candidate) > rank(current) ? candidate : current;
    }

    public int rank(String riskLevel) {
        if (riskLevel == null) {
            return 0;
        }
        return switch (riskLevel.trim().toUpperCase(Locale.ROOT)) {
            case "CRITICAL" -> 5;
            case "HIGH" -> 4;
            case "MEDIUM" -> 3;
            case "LOW" -> 2;
            case "INFO" -> 1;
            default -> 0;
        };
    }
}
