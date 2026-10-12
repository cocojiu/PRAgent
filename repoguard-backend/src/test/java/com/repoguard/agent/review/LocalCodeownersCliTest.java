package com.repoguard.agent.review;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.repoguard.agent.config.JacksonConfig;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Calls the actual Python CLI ownership module; fixtures never represent real tenant members. */
class LocalCodeownersCliTest {
    private static final ObjectMapper JSON = new JacksonConfig().objectMapper();
    private static final String ADAPTER = """
        import json,pathlib,sys
        sys.dont_write_bytecode=True;sys.path.insert(0,sys.argv[1])
        import local_codeowners as module
        request=json.load(sys.stdin);prepared=module.prepare_pattern;matcher=module.match_prepared
        counts={'prepared':0,'matches':0}
        def prepare(pattern):counts['prepared']+=1;return prepared(pattern)
        def match(pattern,parts):counts['matches']+=1;return matcher(pattern,parts)
        module.prepare_pattern=prepare;module.match_prepared=match
        if request['operation']=='matches':result=module.matches(request['pattern'],request['path'])
        else:result=module.recommend(request['content'],request['paths'],{'commit':'fixture','path':'CODEOWNERS'})
        print(json.dumps({'result':result,'counts':counts},ensure_ascii=True))
        """;
    @TempDir Path temporary;

    @ParameterizedTest @CsvSource({
        "*.java,src/Main.java,true", "*.java,src/Main.JAVA,false", "/src/**,src/Main.java,true",
        "/src/**,src,false", "docs/,docs/a.txt,true", "docs/,docs,false", "/docs,docs,true",
        "/docs,docs/nested/a.txt,true", "/src/*,src/nested/a.txt,false", "a/**/b,a/b,true",
        "a/**/b,a/x/y/b,true", "a/**/b,x/a/b,false", "src/**/App?.java,src/App1.java,true",
        "src/**/App?.java,src/x/App2.java,true", "中文/**,中文/文件.java,true", "foo**bar,x/foobar,true"
    })
    void preservesGlobRootDirectoryCaseAndDescendantSemantics(String pattern, String path, boolean expected) throws Exception {
        var result = call(Map.of("operation", "matches", "pattern", pattern, "path", path));
        assertThat(result.path("result").asBoolean()).isEqualTo(expected);
    }

    @Test void lastRuleOverridesAndEmptyOwnersClearEarlierRecommendations() throws Exception {
        var result = recommend("* @default\nsrc/** @source @source\nsrc/private/**\n",
            List.of("src/Main.java", "src/private/Secret.java", "other.txt", "src/Main.java"));
        assertThat(result.path("paths").size()).isEqualTo(3);
        assertThat(result.path("paths").get(0).path("owners").toString()).isEqualTo("[\"@source\"]");
        assertThat(result.path("paths").get(1).path("owners").size()).isZero();
        assertThat(result.path("uncovered").toString()).isEqualTo("[\"src/private/Secret.java\"]");
        assertThat(result.path("membershipVerified").asBoolean()).isFalse();
        assertThat(result.path("automaticallyAssigned").asBoolean()).isFalse();
        assertThat(result.path("source").path("commit").asText()).isEqualTo("fixture");
    }

    @Test void invalidLinesRemainVisibleEvenWhenALaterRuleMatchesEveryPath() throws Exception {
        var result = recommend("!src/** @unsafe\n[ab]/** @unsafe\n* @last\n", List.of("src/Main.java"));
        assertThat(result.path("status").asText()).isEqualTo("PARTIAL_SYNTAX");
        assertThat(result.path("warnings").size()).isEqualTo(2);
        assertThat(result.path("warnings").get(0).path("line").asInt()).isEqualTo(1);
        assertThat(result.path("paths").get(0).path("owners").toString()).isEqualTo("[\"@last\"]");
    }

    @Test void preparesEachRuleOnceAndStopsAtTheLastMatchingRule() throws Exception {
        var content = new StringBuilder();
        for (int index = 0; index < 1999; index++) content.append("src/component").append(index).append("/** @earlier\n");
        content.append("* @last\n");
        var paths = java.util.stream.IntStream.range(0, 400).mapToObj(index -> "src/component" + index + "/File.java").toList();
        var response = call(Map.of("operation", "recommend", "content", content.toString(), "paths", paths));
        assertThat(response.path("counts").path("prepared").asInt()).isEqualTo(2000);
        assertThat(response.path("counts").path("matches").asInt()).isEqualTo(400);
        assertThat(response.path("result").path("paths").size()).isEqualTo(400);
        assertThat(response.path("result").path("paths").get(0).path("line").asInt()).isEqualTo(2000);
    }

    @Test void originalByteAndLineBudgetsStillRejectOversizedRules() throws Exception {
        assertThat(recommend("x".repeat(128 * 1024 + 1), List.of("a.java")).path("status").asText()).isEqualTo("BUDGET_EXCEEDED");
        assertThat(recommend("* @a\n".repeat(2001), List.of("a.java")).path("status").asText()).isEqualTo("BUDGET_EXCEEDED");
    }

    @Test void candidateRankingRemainsDeterministicAndLimitedToThree() throws Exception {
        var result = recommend("a/** @a\nb/** @b\nc/** @c\nd/** @d\n", List.of("d/F.java", "c/F.java", "b/F.java", "a/F.java", "a/G.java"));
        var candidates = result.path("recommendations");
        assertThat(candidates.size()).isEqualTo(3);
        assertThat(candidates.get(0).path("externalIdentity").asText()).isEqualTo("@a");
        assertThat(candidates.get(0).path("coveredFiles").asInt()).isEqualTo(2);
        assertThat(candidates.get(1).path("externalIdentity").asText()).isEqualTo("@b");
        assertThat(candidates.get(2).path("externalIdentity").asText()).isEqualTo("@c");
    }

    private JsonNode recommend(String content, List<String> paths) throws Exception {
        return call(Map.of("operation", "recommend", "content", content, "paths", paths)).path("result");
    }
    private JsonNode call(Map<String, Object> request) throws Exception {
        Path scripts = Path.of("..", "scripts").toAbsolutePath().normalize();
        String python = System.getenv().getOrDefault("REPOGUARD_PREFLIGHT_PYTHON", System.getProperty("os.name").startsWith("Windows") ? "python" : "python3");
        Path out = temporary.resolve("out.json"), error = temporary.resolve("error.txt");
        var process = new ProcessBuilder(python, "-B", "-X", "utf8", "-c", ADAPTER, scripts.toString())
            .redirectOutput(out.toFile()).redirectError(error.toFile()).start();
        try {
            try (var input = process.getOutputStream()) { input.write(JSON.writeValueAsBytes(request)); }
            assertThat(process.waitFor(20, TimeUnit.SECONDS)).as("Actual ownership module deadline").isTrue();
            assertThat(process.exitValue()).as("Ownership fixture process exit").isZero();
            assertThat(Files.readString(error, StandardCharsets.UTF_8)).isEmpty();
            return JSON.readTree(Files.readAllBytes(out));
        } finally { if (process.isAlive()) process.destroyForcibly(); }
    }
}
