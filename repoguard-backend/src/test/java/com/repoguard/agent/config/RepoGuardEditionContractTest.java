package com.repoguard.agent.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class RepoGuardEditionContractTest {

    @Test
    void defaultsToPersonalEdition() {
        RepoGuardEditionContract contract = RepoGuardEditionContract.resolve(new MockEnvironment());

        assertThat(contract.edition()).isEqualTo(RepoGuardEditionContract.Edition.PERSONAL);
        assertThat(contract.personal()).isTrue();
        assertThat(contract.enterpriseEnabled()).isFalse();
    }

    @Test
    void resolvesEnvironmentAliasAndNormalizesEdition() {
        RepoGuardEditionContract contract = RepoGuardEditionContract.resolve(
            new MockEnvironment().withProperty("REPOGUARD_EDITION", " Enterprise-Experimental ")
        );

        assertThat(contract.edition()).isEqualTo(RepoGuardEditionContract.Edition.ENTERPRISE_EXPERIMENTAL);
        assertThat(contract.personal()).isFalse();
        assertThat(contract.enterpriseEnabled()).isTrue();
    }

    @Test
    void explicitApplicationPropertyTakesPrecedenceOverEnvironmentAlias() {
        RepoGuardEditionContract contract = RepoGuardEditionContract.resolve(
            new MockEnvironment()
                .withProperty("app.edition", "personal")
                .withProperty("REPOGUARD_EDITION", "enterprise-experimental")
        );

        assertThat(contract.edition()).isEqualTo(RepoGuardEditionContract.Edition.PERSONAL);
    }

    @Test
    void invalidEditionFailsClosed() {
        assertThatThrownBy(() -> RepoGuardEditionContract.resolve(
            new MockEnvironment().withProperty("app.edition", "enterprise")
        ))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("app.edition must be personal or enterprise-experimental");
    }
}
