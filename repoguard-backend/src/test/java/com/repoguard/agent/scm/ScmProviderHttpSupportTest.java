package com.repoguard.agent.scm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.repoguard.agent.external.ExternalCallResilience;
import com.repoguard.agent.external.ExternalHttpJsonResponseReader;
import com.repoguard.agent.external.ExternalHttpResponseProfile;
import com.repoguard.agent.external.OutboundEndpointType;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class ScmProviderHttpSupportTest {

    private final ScmIntegrationConfigProvider configProvider = mock(ScmIntegrationConfigProvider.class);
    private final ScmProviderHttpSupport support = new ScmProviderHttpSupport(
        "GITEE", "https://gitee.com", ExternalHttpResponseProfile.GITEE, OutboundEndpointType.GITEE,
        configProvider, RestClient.builder(),
        new ExternalHttpJsonResponseReader(new ObjectMapper(), new com.repoguard.agent.external.ExternalHttpResponseReader()),
        mock(ExternalCallResilience.class), null
    );

    @Test
    void normalizesApiAndPathUrlsAndRepositoryValues() {
        ScmIntegrationSettings settings = new ScmIntegrationSettings(
            "GITEE", "CONFIGURED", "https://gitee.example/api/v5/", "token", null, "acme", "widgets", 1L
        );

        assertThat(support.settings()).isNull();
        assertThat(support.apiBase(settings, "/api/v5")).isEqualTo("https://gitee.example/api/v5");
        assertThat(support.apiBase(new ScmIntegrationSettings(
            "GITEE", "CONFIGURED", "https://gitee.example/", "token", null, null, null, 1L
        ), "/api/v5")).isEqualTo("https://gitee.example/api/v5");
        assertThat(support.pathUrl(settings, "/api/v5", "repos", "acme/widgets"))
            .contains("/repos/acme%2Fwidgets");
        assertThat(support.projectUrl(settings, new ScmRepositoryRef("acme", "widgets"), "/api/v5", "/pulls"))
            .endsWith("/acme/widgets/pulls");
        assertThat(support.repository(" acme ", " widgets ", false))
            .isEqualTo(new ScmRepositoryRef("acme", "widgets"));
        assertThat(support.repository(null, "", false)).isNull();
        assertThatThrownBy(() -> new ScmRepositoryRef(" ", "widgets"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("namespace");
    }

    @Test
    void requiresTokenAndRepositoryWhenOperationsNeedConfiguration() {
        when(configProvider.settings("GITEE"))
            .thenReturn(new ScmIntegrationSettings("GITEE", "NOT_CONFIGURED", null, null, null, null, null, null));

        assertThat(support.configuredRepository()).isNull();
        assertThatThrownBy(support::requireSettings)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("token");
        assertThatThrownBy(() -> support.repository(null, null, true))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("namespace or repository");
    }
}
