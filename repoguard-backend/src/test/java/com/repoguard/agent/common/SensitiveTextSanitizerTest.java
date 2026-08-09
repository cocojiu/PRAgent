package com.repoguard.agent.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SensitiveTextSanitizerTest {

    @Test
    void masksCommonSecretForms() {
        String sanitized = SensitiveTextSanitizer.sanitize(
            "amqp://user:raw-pass@rabbit:5672 failed token=raw-token password:raw-password "
                + "url=https://broker.example/publish?access_token=raw-access&sign=raw-sign "
                + "Authorization: Bearer raw.bearer-token"
        );

        assertThat(sanitized).contains("amqp://user:****@rabbit:5672");
        assertThat(sanitized).contains("token=****");
        assertThat(sanitized).contains("password:****");
        assertThat(sanitized).contains("access_token=****");
        assertThat(sanitized).contains("sign=****");
        assertThat(sanitized).contains("Bearer ****");
        assertThat(sanitized)
            .doesNotContain("raw-pass", "raw-token", "raw-password", "raw-access", "raw-sign", "raw.bearer-token");
    }

    @Test
    void returnsNullWhenInputIsNull() {
        assertThat(SensitiveTextSanitizer.sanitize(null)).isNull();
    }

    @Test
    void preservesQuotedSecretShape() {
        String sanitized = SensitiveTextSanitizer.sanitize(
            "{\"refreshToken\":\"raw-refresh\",\"clientSecret\": \"raw-secret\",\"apiKey\": 'raw-key'}"
        );

        assertThat(sanitized)
            .contains("\"refreshToken\":\"****\"")
            .contains("\"clientSecret\": \"****\"")
            .contains("\"apiKey\": '****'")
            .doesNotContain("raw-refresh", "raw-secret", "raw-key");
    }

    @Test
    void masksOpenAiStyleApiKeysWithoutExplicitFieldName() {
        String sanitized = SensitiveTextSanitizer.sanitize(
            "LLM returned invalid api key sk-secret123456789 in response body"
        );

        assertThat(sanitized)
            .contains("sk-****")
            .doesNotContain("sk-secret123456789");
    }

    @Test
    void masksJdbcUrlsAndCanPreserveStackTraceLineBreaks() {
        String sanitized = SensitiveTextSanitizer.sanitizePreservingWhitespace(
            "outer jdbc:mysql://internal-db:3306/repoguard?password=raw-db\n"
                + "Caused by: token=raw-token\n"
        );

        assertThat(sanitized)
            .isEqualTo("outer jdbc:****\nCaused by: token=****\n")
            .doesNotContain("internal-db", "raw-db", "raw-token");
    }

    @Test
    void masksStandaloneCredentialsAndPrivateKeyBlocks() {
        String githubToken = "gh" + "p_" + "1234567890abcdefghijklmnopqrstuvwxyz";
        String jwt = "eyJabcdefghijk.abcdefghijklmnop.abcdefghijk";
        String privateKey = "-----BEGIN PRIVATE KEY-----\nraw-private-key-material\n-----END PRIVATE KEY-----";

        String sanitized = SensitiveTextSanitizer.sanitizePreservingWhitespace(
            githubToken + "\n" + jwt + "\n" + privateKey
        );

        assertThat(sanitized)
            .contains("[REDACTED CREDENTIAL]", "[REDACTED JWT]", "[REDACTED PRIVATE KEY]")
            .doesNotContain(githubToken, jwt, "raw-private-key-material");
    }

    @Test
    void sourceCodeModePreservesSecretLookupExpressionsButMasksHardcodedLiterals() {
        String sanitized = SensitiveTextSanitizer.sanitizeSourceCodePreservingWhitespace(
            "String apiKey = System.getenv(\"API_KEY\");\n"
                + "String password = \"CorrectHorseBatteryStaple42\";"
        );

        assertThat(sanitized)
            .contains("apiKey = System.getenv(\"API_KEY\")")
            .contains("password = \"****\"")
            .doesNotContain("CorrectHorseBatteryStaple42");
    }

    @Test
    void sourceCodeModeKeepsNonCredentialAssignmentsVisible() {
        String source = "String signatureAlgorithm = \"MD5withRSA\";\n"
            + "String assignment = \"review-task\";\n"
            + "int maxTokens = 4096;";

        assertThat(SensitiveTextSanitizer.sanitizeSourceCodePreservingWhitespace(source))
            .isEqualTo(source);
    }

    @Test
    void privateKeyMaskingPreservesDiffLineStructure() {
        String patch = "@@ -1,0 +1,5 @@\r\n"
            + "+-----BEGIN PRIVATE KEY-----\r\n"
            + "+raw-line-one\r\n"
            + "+raw-line-two\r\n"
            + "+-----END PRIVATE KEY-----\r\n"
            + "+dangerousCall();";

        String sanitized = SensitiveTextSanitizer.sanitizeSourceCodePreservingWhitespace(patch);

        assertThat(sanitized.lines().count()).isEqualTo(patch.lines().count());
        assertThat(sanitized).contains(
            "+[REDACTED PRIVATE KEY]\r\n+[REDACTED PRIVATE KEY]\r\n",
            "+dangerousCall();"
        );
        assertThat(sanitized)
            .doesNotContain("+-[REDACTED PRIVATE KEY]", "raw-line-one", "raw-line-two");
    }
}
