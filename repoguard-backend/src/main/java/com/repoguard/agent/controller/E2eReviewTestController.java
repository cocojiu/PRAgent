package com.repoguard.agent.controller;

import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/e2e-review-test")
public class E2eReviewTestController {

    @PostMapping("/config")
    public Map<String, Object> updateConfig(@RequestBody Map<String, Object> request) {
        return Map.of("accepted", true, "size", request.size());
    }
}
