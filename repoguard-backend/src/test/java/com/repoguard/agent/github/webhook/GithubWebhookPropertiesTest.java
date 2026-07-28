package com.repoguard.agent.github.webhook;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class GithubWebhookPropertiesTest {

    @Test
    void prodProfileRequiresSecretWhenSignatureVerificationIsEnabled() {
        GithubWebhookProperties properties = new GithubWebhookProperties();

        assertThatThrownBy(() -> properties.validateForProfiles(new String[] {"prod"}))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("app.github.webhook.secret");
    }

    @Test
    void stagingProfileRequiresSecretWhenSignatureVerificationIsEnabled() {
        GithubWebhookProperties properties = new GithubWebhookProperties();

        assertThatThrownBy(() -> properties.validateForProfiles(new String[] {"staging"}))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("app.github.webhook.secret");
    }

    @Test
    void prodProfileAllowsMissingSecretWhenWebhookIsDisabled() {
        GithubWebhookProperties properties = new GithubWebhookProperties();
        properties.setEnabled(false);

        assertThatCode(() -> properties.validateForProfiles(new String[] {"prod"}))
            .doesNotThrowAnyException();
    }

    @Test
    void prodProfileRejectsDisabledSignatureVerification() {
        GithubWebhookProperties properties = new GithubWebhookProperties();
        properties.setRequireSignature(false);

        assertThatThrownBy(() -> properties.validateForProfiles(new String[] {"prod"}))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("require-signature");
    }

    @Test
    void prodProfileRequiresRepositoryAllowList() {
        GithubWebhookProperties properties = new GithubWebhookProperties();
        properties.setSecret("secret");

        assertThatThrownBy(() -> properties.validateForProfiles(new String[] {"prod"}))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("allowed-repositories");
    }

    @Test
    void prodProfileRequiresHeadBranchAllowList() {
        GithubWebhookProperties properties = new GithubWebhookProperties();
        properties.setSecret("secret");
        properties.setAllowedRepositories(List.of("octocat/api"));

        assertThatThrownBy(() -> properties.validateForProfiles(new String[] {"prod"}))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("allowed-head-branches");
    }

    @Test
    void prodProfileAcceptsCompleteWebhookPolicy() {
        GithubWebhookProperties properties = new GithubWebhookProperties();
        properties.setSecret("secret");
        properties.setAllowedRepositories(List.of("octocat/api"));
        properties.setAllowedHeadBranches(List.of("main"));

        assertThatCode(() -> properties.validateForProfiles(new String[] {"prod"}))
            .doesNotThrowAnyException();
    }

    @Test
    void rejectsNonPositivePayloadLimit() {
        GithubWebhookProperties properties = new GithubWebhookProperties();
        properties.setMaxPayloadBytes(0);

        assertThatThrownBy(() -> properties.validateForProfiles(new String[] {"test"}))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("app.github.webhook.max-payload-bytes");
    }
}
