package com.repoguard.agent.review;

import java.util.Objects;

final class SyntheticCredentialFixtures {

    private SyntheticCredentialFixtures() {
    }

    static String expandPlaceholders(String source) {
        return Objects.requireNonNull(source, "source")
            .replace("{{GITHUB_TOKEN_FIXTURE}}", githubToken())
            .replace("{{PASSWORD_FIXTURE}}", password())
            .replace("{{API_KEY_FIXTURE}}", apiKey())
            .replace("{{WEBHOOK_SECRET_FIXTURE}}", webhookSecret())
            .replace("{{AWS_ACCESS_KEY_FIXTURE}}", awsAccessKey())
            .replace("{{SLACK_TOKEN_FIXTURE}}", slackBotToken())
            .replace("{{PRODUCTION_PASSWORD_FIXTURE}}", productionPassword())
            .replace("{{CLIENT_SECRET_FIXTURE}}", clientSecret())
            .replace("{{REFRESH_TOKEN_FIXTURE}}", refreshToken())
            .replace("{{GITHUB_DEPLOY_TOKEN_FIXTURE}}", githubDeployToken())
            .replace("{{JWT_CREDENTIAL_FIXTURE}}", jwtCredential());
    }

    static String githubToken() {
        return "gh" + "p_" + "1234567890" + "abcdefghijklmnopqrstuvwxyz";
    }

    static String password() {
        return "CorrectHorse" + "BatteryStaple42";
    }

    static String apiKey() {
        return "sk-" + "live-" + "1234567890" + "abcdef";
    }

    static String webhookSecret() {
        return "wh" + "sec_" + "1234567890" + "abcdef";
    }

    static String awsAccessKey() {
        return "AK" + "IA" + "IOSFODNN7EXAMPLE";
    }

    static String slackBotToken() {
        return "xo" + "xb-" + "1234567890" + "-abcdef";
    }

    static String productionPassword() {
        return "prod-" + "password-" + "123";
    }

    static String clientSecret() {
        return "client-" + "secret-value-" + "123456";
    }

    static String refreshToken() {
        return "refresh-" + "token-secret-" + "value";
    }

    static String githubDeployToken() {
        return "gh" + "p_" + "abcdefghijklmnopqrstuvwxyz" + "123456";
    }

    static String jwtCredential() {
        return "eyJabcdefghijk" + ".abcdefghijklmnop" + ".abcdefghijk";
    }
}
