package com.repoguard.agent.review.codeowners;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.repoguard.agent.config.JacksonConfig;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class CodeownersMappingCacheTest {
    private byte[] document(long tenant, String repository, int user) {
        return ("[{\"tenantId\":" + tenant + ",\"repository\":\"" + repository
            + "\",\"owners\":{\"@owner\":[" + user + "]}}]").getBytes(StandardCharsets.UTF_8);
    }
    @Test void reusesOnlyTheSameContentAndCanonicalScope() {
        var json = spy(new JacksonConfig().objectMapper()); var cache = new CodeownersMappingCache(json);
        var first = cache.resolve(document(7, "owner/repo", 11), 7, "owner/repo");
        assertThat(cache.resolve(document(7, "owner/repo", 11), 7, "OWNER/REPO")).isSameAs(first);
        verify(json, times(1)).reader();
        assertThat(cache.resolve(document(7, "owner/repo", 12), 7, "owner/repo").owners().get("@owner")).containsExactly(12L);
        assertThat(cache.resolve(document(7, "owner/repo", 11), 8, "owner/repo").status()).isEqualTo("SCOPE_NOT_CONFIGURED");
        assertThat(cache.resolve(document(7, "owner/repo", 11), 7, "owner/renamed").status()).isEqualTo("SCOPE_NOT_CONFIGURED");
    }
    @Test void expiresAfterSixtyMonotonicSecondsWithoutSlidingOnReads() {
        AtomicLong time = new AtomicLong(); var cache = new CodeownersMappingCache(new JacksonConfig().objectMapper(), time::get);
        byte[] bytes = document(7, "owner/repo", 11); var first = cache.resolve(bytes, 7, "owner/repo");
        time.set(TimeUnit.SECONDS.toNanos(59)); assertThat(cache.resolve(bytes, 7, "owner/repo")).isSameAs(first);
        time.set(TimeUnit.SECONDS.toNanos(60)); assertThat(cache.resolve(bytes, 7, "owner/repo")).isNotSameAs(first);
    }
    @Test void boundsCacheToEightScopesAndEvictsTheLeastRecentlyUsed() {
        var json = spy(new JacksonConfig().objectMapper()); var cache = new CodeownersMappingCache(json);
        var first = cache.resolve(document(1, "owner/repo", 11), 1, "owner/repo");
        for (int tenant = 2; tenant <= 9; tenant++) cache.resolve(document(tenant, "owner/repo", 11), tenant, "owner/repo");
        assertThat(cache.resolve(document(1, "owner/repo", 11), 1, "owner/repo")).isNotSameAs(first);
        verify(json, times(10)).reader();
    }
    @Test void neverCachesInvalidDocumentsOrBypassesTheDocumentBudget() {
        var json = spy(new JacksonConfig().objectMapper()); var cache = new CodeownersMappingCache(json);
        for (int i = 0; i < 2; i++) assertThat(cache.resolve("bad".getBytes(StandardCharsets.UTF_8), 7, "owner/repo").status()).isEqualTo("INVALID_MAPPING");
        verify(json, times(2)).reader();
        assertThat(cache.resolve(new byte[262145], 7, "owner/repo").status()).isEqualTo("BUDGET_EXCEEDED");
        assertThat(cache.resolve(document(7, "owner/repo", 11), 0, "owner/repo").status()).isEqualTo("INVALID_SCOPE");
    }
}
