package com.repoguard.agent.review;

interface ReviewPipelineStage {

    ReviewPipelineState apply(ReviewPipelineState state);
}
