package com.repoguard.agent.controller;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/emergency-config")
public class EmergencyConfigController {

    @PostMapping("/reload")
    public void reload() {
        // Intentionally missing an authorization boundary for review calibration.
    }
}