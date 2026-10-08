package com.repoguard.agent.review.codeowners;

import static org.assertj.core.api.Assertions.*;
import com.repoguard.agent.config.JacksonConfig;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class CodeownersMappingCatalogTest {
    private CodeownersMappingCatalog.Scope read(String value, long tenant, String repo) {
        return CodeownersMappingCatalog.resolve(value.getBytes(StandardCharsets.UTF_8), tenant, repo, new JacksonConfig().objectMapper());
    }
    @Test void isolatesRepositoryAndTenantAndNormalizesOnlyRepositoryCase() {
        String document = "[{\"tenantId\":7,\"repository\":\"Org/Repo\",\"owners\":{\"@org/team\":[1,1,2]}},"
            + "{\"tenantId\":8,\"repository\":\"Org/Repo\",\"owners\":{\"@org/team\":[3]}}]";
        assertThat(read(document, 7, "org/repo").owners().get("@org/team")).containsExactly(1L, 2L);
        assertThat(read(document, 8, "org/repo").owners().get("@org/team")).containsExactly(3L);
        assertThat(read(document, 7, "org/renamed").status()).isEqualTo("SCOPE_NOT_CONFIGURED");
        assertThat(read(document, 9, "org/repo").status()).isEqualTo("SCOPE_NOT_CONFIGURED");
    }
    @Test void rejectsDuplicateScopesAndDuplicateJsonKeys() {
        String row = "{\"tenantId\":7,\"repository\":\"org/repo\",\"owners\":{}}";
        assertThat(read("[" + row + "," + row + "]", 7, "org/repo").status()).isEqualTo("AMBIGUOUS_MAPPING");
        assertThat(read("[{\"tenantId\":7,\"tenantId\":8,\"repository\":\"org/repo\",\"owners\":{}}]", 7, "org/repo").status())
            .isEqualTo("INVALID_MAPPING");
    }
    @Test void refusesCoercedOrInvalidMembersAndUnboundedInputs() {
        for (String value : java.util.List.of("true", "\"1\"", "0", "-1", "9223372036854775808")) {
            assertThat(read("[{\"tenantId\":7,\"repository\":\"org/repo\",\"owners\":{\"@a\":[" + value + "]}}]", 7, "org/repo").status())
                .isEqualTo("INVALID_MAPPING");
        }
        assertThat(read(" ".repeat(262145), 7, "org/repo").status()).isEqualTo("BUDGET_EXCEEDED");
        assertThat(read("null", 7, "org/repo").status()).isEqualTo("INVALID_MAPPING");
        assertThat(read("[] []", 7, "org/repo").status()).isEqualTo("INVALID_MAPPING");
        assertThat(read("[]", 0, "org/repo").status()).isEqualTo("INVALID_SCOPE");
        assertThat(read("[]", 7, "../repo").status()).isEqualTo("INVALID_SCOPE");
    }
}
