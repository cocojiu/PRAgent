package com.repoguard.agent.service.impl;

import com.repoguard.agent.service.ReviewService;
import org.springframework.stereotype.Service;

@Service
public class ReviewServiceImpl implements ReviewService {

    @Override
    public String ping() {
        return "review-service-ready";
    }
}
