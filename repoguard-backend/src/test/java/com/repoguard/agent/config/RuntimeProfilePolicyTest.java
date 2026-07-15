package com.repoguard.agent.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class RuntimeProfilePolicyTest {

    @Test
    void requiresAtLeastOneExplicitProfile() {
        assertThatThrownBy(() -> RuntimeProfilePolicy.requireExplicitProfiles(null))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("explicit Spring profile");
        assertThatThrownBy(() -> RuntimeProfilePolicy.requireExplicitProfiles(new String[] {" ", ""}))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("explicit Spring profile");
    }

    @Test
    void acceptsLocalTestAndProductionLikeProfileGroups() {
        assertThatCode(() -> RuntimeProfilePolicy.requireExplicitProfiles(new String[] {"dev", "local"}))
            .doesNotThrowAnyException();
        assertThatCode(() -> RuntimeProfilePolicy.requireExplicitProfiles(new String[] {"test"}))
            .doesNotThrowAnyException();
        assertThatCode(() -> RuntimeProfilePolicy.requireExplicitProfiles(new String[] {"prod"}))
            .doesNotThrowAnyException();
        assertThatCode(() -> RuntimeProfilePolicy.requireExplicitProfiles(new String[] {"staging"}))
            .doesNotThrowAnyException();
    }

    @Test
    void rejectsMixedLocalAndProductionLikeProfiles() {
        assertThatThrownBy(() -> RuntimeProfilePolicy.requireExplicitProfiles(new String[] {"prod", "local"}))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("cannot be combined");
    }

    @Test
    void treatsEveryUnknownProfileAsProductionLike() {
        assertThat(RuntimeProfilePolicy.isProductionLike(new String[] {"prod"})).isTrue();
        assertThat(RuntimeProfilePolicy.isProductionLike(new String[] {"staging"})).isTrue();
        assertThat(RuntimeProfilePolicy.isProductionLike(new String[] {"dev", "local"})).isFalse();
        assertThat(RuntimeProfilePolicy.isProductionLike(new String[] {"test"})).isFalse();
        assertThat(RuntimeProfilePolicy.isProductionLike(new String[0])).isFalse();
    }
}
