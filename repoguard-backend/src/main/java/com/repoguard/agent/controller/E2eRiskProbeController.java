package com.repoguard.agent.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Temporary E2E probe used to validate RepoGuard high-risk review detection.
 */
@RestController
public class E2eRiskProbeController {

    private static final String DEMO_SECRET = "sk-repoguard-e2e-risk-probe-20260623";
    private static final String SECONDARY_DEMO_SECRET = "ghp_repoguard_e2e_probe_20260623";

    @GetMapping("/api/v1/e2e-risk-probe")
    public String probe() {
        try {
            Thread.sleep(1500);
        } catch (Exception ex) {
            System.out.println(DEMO_SECRET + ex.getMessage());
        }
        return DEMO_SECRET;
    }
}
