package com.repoguard.agent.external;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.external-call.resilience")
public class ExternalCallResilienceProperties {

    private Instance github = new Instance();
    private Instance llm = new Instance();

    public Instance getGithub() {
        return github;
    }

    public void setGithub(Instance github) {
        this.github = github;
    }

    public Instance getLlm() {
        return llm;
    }

    public void setLlm(Instance llm) {
        this.llm = llm;
    }

    public static class Instance {
        private int circuitBreakerFailureRateThreshold = 50;
        private int circuitBreakerSlidingWindowSize = 20;
        private int circuitBreakerMinimumCalls = 5;
        private int circuitBreakerWaitOpenSeconds = 30;
        private int retryMaxAttempts = 2;
        private int retryWaitMillis = 200;
        private int rateLimitForPeriod = 30;
        private int rateLimitRefreshMillis = 1000;
        private int rateLimitTimeoutMillis = 0;
        private int bulkheadMaxConcurrentCalls = 4;
        private int bulkheadMaxWaitMillis = 0;

        public int getCircuitBreakerFailureRateThreshold() {
            return circuitBreakerFailureRateThreshold;
        }

        public void setCircuitBreakerFailureRateThreshold(int circuitBreakerFailureRateThreshold) {
            this.circuitBreakerFailureRateThreshold = circuitBreakerFailureRateThreshold;
        }

        public int getCircuitBreakerSlidingWindowSize() {
            return circuitBreakerSlidingWindowSize;
        }

        public void setCircuitBreakerSlidingWindowSize(int circuitBreakerSlidingWindowSize) {
            this.circuitBreakerSlidingWindowSize = circuitBreakerSlidingWindowSize;
        }

        public int getCircuitBreakerMinimumCalls() {
            return circuitBreakerMinimumCalls;
        }

        public void setCircuitBreakerMinimumCalls(int circuitBreakerMinimumCalls) {
            this.circuitBreakerMinimumCalls = circuitBreakerMinimumCalls;
        }

        public int getCircuitBreakerWaitOpenSeconds() {
            return circuitBreakerWaitOpenSeconds;
        }

        public void setCircuitBreakerWaitOpenSeconds(int circuitBreakerWaitOpenSeconds) {
            this.circuitBreakerWaitOpenSeconds = circuitBreakerWaitOpenSeconds;
        }

        public int getRetryMaxAttempts() {
            return retryMaxAttempts;
        }

        public void setRetryMaxAttempts(int retryMaxAttempts) {
            this.retryMaxAttempts = retryMaxAttempts;
        }

        public int getRetryWaitMillis() {
            return retryWaitMillis;
        }

        public void setRetryWaitMillis(int retryWaitMillis) {
            this.retryWaitMillis = retryWaitMillis;
        }

        public int getRateLimitForPeriod() {
            return rateLimitForPeriod;
        }

        public void setRateLimitForPeriod(int rateLimitForPeriod) {
            this.rateLimitForPeriod = rateLimitForPeriod;
        }

        public int getRateLimitRefreshMillis() {
            return rateLimitRefreshMillis;
        }

        public void setRateLimitRefreshMillis(int rateLimitRefreshMillis) {
            this.rateLimitRefreshMillis = rateLimitRefreshMillis;
        }

        public int getRateLimitTimeoutMillis() {
            return rateLimitTimeoutMillis;
        }

        public void setRateLimitTimeoutMillis(int rateLimitTimeoutMillis) {
            this.rateLimitTimeoutMillis = rateLimitTimeoutMillis;
        }

        public int getBulkheadMaxConcurrentCalls() {
            return bulkheadMaxConcurrentCalls;
        }

        public void setBulkheadMaxConcurrentCalls(int bulkheadMaxConcurrentCalls) {
            this.bulkheadMaxConcurrentCalls = bulkheadMaxConcurrentCalls;
        }

        public int getBulkheadMaxWaitMillis() {
            return bulkheadMaxWaitMillis;
        }

        public void setBulkheadMaxWaitMillis(int bulkheadMaxWaitMillis) {
            this.bulkheadMaxWaitMillis = bulkheadMaxWaitMillis;
        }
    }
}
