package com.repoguard.agent.review;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.repoguard.agent.config.JacksonConfig;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Real Git fixtures exercise the CLI collector on Windows and Linux; no test script files. */
class LocalPreflightCollectionTest {
    private static final ObjectMapper JSON = new JacksonConfig().objectMapper();
    private static final String ADAPTER = """
        import importlib.util,json,pathlib,subprocess,sys
        source=pathlib.Path(sys.argv[1]);repo=pathlib.Path(sys.argv[2]);mode=sys.argv[3]
        sys.path.insert(0,str(source.parent));sys.dont_write_bytecode=True
        spec=importlib.util.spec_from_file_location('cli_fixture',source)
        cli=importlib.util.module_from_spec(spec);spec.loader.exec_module(cli)
        original=cli.git;calls=[];batches=0
        def git(cwd,*args,**kwargs):
            global batches
            calls.append(args);raw=original(cwd,*args,**kwargs)
            if '--raw' in args:
                batches+=1
                if mode=='patch-change' and batches==1:
                    (repo/'Main.java').write_text('class Main { int value=3; }\\n',encoding='utf8')
                if mode=='context-change' and batches==2:
                    (repo/'Main.java').write_text('class Main { int value=4; }\\n',encoding='utf8')
                if mode=='list-change' and batches==2:
                    (repo/'Delete.java').write_text('class Delete { int changed=1; }\\n',encoding='utf8')
                if mode=='head-change' and batches==2:
                    subprocess.run(['git','-c','core.hooksPath=/dev/null','-c','commit.gpgSign=false',
                        'commit','--quiet','-am','fixture update'],cwd=repo,check=True,stdout=subprocess.PIPE,stderr=subprocess.PIPE)
                if mode=='wrong-header' and batches==1:
                    raw=raw.replace(b'diff --git a/Main.java b/Main.java',b'diff --git a/Other.java b/Other.java')
            if mode=='untracked-list-change' and '--others' in args and sum('--others' in x for x in calls)==1:
                (repo/'NewAfterListing.java').write_text('class NewAfterListing {}\\n',encoding='utf8')
            return raw
        cli.git=git
        try:
            base,ancestor,head,files,gaps=cli.collect(repo,'HEAD',sys.argv[4]=='true',
                sys.argv[6]=='true',sys.argv[5]=='true')
            count=len(calls);oracle=True
            for file in files:
                if mode!='oracle' or any(x['path']==file['filename'] for x in gaps):continue
                paths=[file['previousFilename'],file['filename']] if 'previousFilename' in file else [file['filename']]
                args=['diff','--no-ext-diff','--no-textconv','--find-renames=50%','-l200','--no-color',
                    '--src-prefix=a/','--dst-prefix=b/','--unified=3']
                if sys.argv[4]=='true':args.append('--cached')
                expected=original(repo,*args,ancestor,'--',*paths,limit=256*1024).decode('utf8')
                oracle=oracle and expected==file['patch']
            print(json.dumps({'files':files,'gaps':gaps,'gitCalls':count,'oracleMatches':oracle},ensure_ascii=True))
        except (cli.PreflightError,cli.DiffError,cli.ContentError) as failure:
            print(json.dumps({'error':str(failure),'gitCalls':len(calls)}))
        """;
    @TempDir Path temporary;
    private Path repository;

    @BeforeEach void fixture() throws Exception {
        repository = temporary.resolve("repo"); Files.createDirectories(repository);
        git("init", "--quiet"); git("config", "user.name", "fixture");
        git("config", "user.email", "fixture@fixture.invalid");
        write("Main.java", "class Main { int value=1; }\n");
        write("Delete.java", "class Delete {}\n");
        write("Space name.java", "class Space {}\n");
        write("中文.java", "class Unicode {}\n");
        write("Rename.java", "class Rename {\n" + "  int value=1;\n".repeat(20) + "}\n");
        Files.write(repository.resolve("Binary.bin"), new byte[] {0, 1, 2});
        git("add", "--", "Main.java", "Delete.java", "Space name.java", "中文.java", "Rename.java", "Binary.bin");
        git("commit", "--quiet", "-m", "fixture");
    }

    @Test void fiftyChangedFilesUseTwoBatchReadsAndMatchIndependentGitPatches() throws Exception {
        var paths = new ArrayList<String>();
        for (int i = 0; i < 50; i++) { String path = "File" + i + ".java"; paths.add(path); write(path, "class File" + i + " {}\n"); }
        stage(paths); git("commit", "--quiet", "-m", "batch fixture");
        for (int i = 0; i < 50; i++) write(paths.get(i), "class File" + i + " { int value=2; }\n");
        var result = collect("oracle", false, false, false);
        assertThat(result.path("files").size()).isEqualTo(50);
        assertThat(result.path("gaps").size()).isZero();
        assertThat(result.path("gitCalls").asInt()).isEqualTo(8);
        assertThat(result.path("oracleMatches").asBoolean()).isTrue();
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void unicodeSpacesAddDeleteRenameAndPatchLookingContentRemainBoundToTheirPaths(boolean staged) throws Exception {
        write("Main.java", "class Main { int value=2; }\ndiff --git a/forged b/forged\n");
        write("Space name.java", "class Space { int value=2; }\n");
        write("中文.java", "class Unicode { int value=2; }\n");
        write("Added.java", "class Added {}\n");
        Files.delete(repository.resolve("Delete.java"));
        Files.move(repository.resolve("Rename.java"), repository.resolve("Renamed.java"));
        git("add", "--all", "--", "Main.java", "Space name.java", "中文.java", "Added.java", "Delete.java", "Rename.java", "Renamed.java");
        var result = collect("oracle", staged, false, false);
        assertThat(result.path("files").size()).isEqualTo(6);
        assertThat(result.path("oracleMatches").asBoolean()).isTrue();
        assertThat(result.path("gaps").size()).isZero();
        var renamed = file(result, "Renamed.java");
        assertThat(renamed.path("status").asText()).isEqualTo("renamed");
        assertThat(renamed.path("previousFilename").asText()).isEqualTo("Rename.java");
        assertThat(file(result, "Added.java").path("status").asText()).isEqualTo("added");
        assertThat(file(result, "Delete.java").path("status").asText()).isEqualTo("removed");
    }

    @Test void ambiguousIdenticalRenameHeadersFollowNulMetadataOrder() throws Exception {
        write("foo", "first unique content\n".repeat(20)); write("foo b/bar", "second different content\n".repeat(20));
        git("add", "--", "foo", "foo b/bar"); git("commit", "--quiet", "-m", "rename fixture");
        Files.createDirectories(repository.resolve("bar b"));
        Files.move(repository.resolve("foo"), repository.resolve("bar b/baz"));
        Files.move(repository.resolve("foo b/bar"), repository.resolve("baz"));
        git("add", "--all", "--", "foo", "foo b/bar", "bar b/baz", "baz");
        var result = collect("oracle", true, false, false);
        assertThat(result.path("files").size()).isEqualTo(2);
        assertThat(result.path("oracleMatches").asBoolean()).isTrue();
        assertThat(file(result, "bar b/baz").path("previousFilename").asText()).isEqualTo("foo");
        assertThat(file(result, "baz").path("previousFilename").asText()).isEqualTo("foo b/bar");
    }

    @Test void binaryTypeChangeAndSubmoduleKeepTheirScopeGaps() throws Exception {
        Files.write(repository.resolve("Binary.bin"), new byte[] {0, 4, 5}); git("add", "--", "Binary.bin");
        String blob = new String(run(List.of("git", "hash-object", "-w", "--stdin"), "Main.java"), StandardCharsets.UTF_8).strip();
        git("update-index", "--cacheinfo", "120000", blob, "Main.java");
        String head = new String(run(List.of("git", "rev-parse", "HEAD"), null), StandardCharsets.UTF_8).strip();
        git("update-index", "--add", "--cacheinfo", "160000", head, "Submodule");
        var result = collect("plain", true, true, false);
        assertThat(result.path("files").size()).isEqualTo(3);
        assertThat(result.path("gaps").toString()).contains("binary", "symlink_not_scanned", "submodule_not_scanned");
        assertThat(file(result, "Main.java").path("patch").asText()).isEmpty();
        assertThat(file(result, "Submodule").path("patch").asText()).isEmpty();
    }

    @Test void untrackedAndFullContextStillUseTheirSelectedSnapshots() throws Exception {
        write("Main.java", "class Main { int value=2; }\n"); write("Untracked.java", "class Untracked {}\n");
        var result = collect("plain", false, true, true);
        assertThat(result.path("files").size()).isEqualTo(2);
        assertThat(file(result, "Untracked.java").path("context").path("content").asText()).isEqualTo("class Untracked {}\n");
        assertThat(file(result, "Main.java").path("contentSha256").asText()).hasSize(64);
    }

    @ParameterizedTest @ValueSource(strings = {"patch-change", "context-change", "list-change", "head-change", "wrong-header", "untracked-list-change"})
    void collectionChangesAndMismatchedPatchIdentityFailClosed(String mode) throws Exception {
        write("Main.java", "class Main { int value=2; }\n");
        write("Untracked.java", "class Untracked {}\n");
        var result = collect(mode, false, mode.equals("context-change"), mode.equals("untracked-list-change"));
        String expected = mode.equals("head-change") ? "head_changed_during_collection"
            : mode.contains("list-change") ? "changed_file_list_changed_during_collection" : "diff_changed_during_collection";
        assertThat(result.path("error").asText()).isEqualTo(expected);
    }

    @Test void patchFileBudgetAndCombinedDiffBudgetRemainEnforced() throws Exception {
        write("Main.java", "x".repeat(270 * 1024) + "\n");
        assertThat(collect("plain", false, false, false).path("error").asText()).isEqualTo("file_content_budget_exceeded");
        write("Main.java", "class Main { int value=1; }\n");
        var paths = new ArrayList<String>();
        for (int i = 0; i < 9; i++) { String path = "Large" + i + ".java"; paths.add(path); write(path, "x".repeat(240 * 1024) + "\n"); }
        stage(paths);
        assertThat(collect("plain", true, false, false).path("error").asText()).isEqualTo("total_diff_budget_exceeded");
    }

    @Test void fileCountBudgetStopsBeforeReadingPatches() throws Exception {
        var paths = new ArrayList<String>();
        for (int i = 0; i < 201; i++) { String path = "Extra" + i + ".java"; paths.add(path); write(path, "class Extra {}\n"); }
        stage(paths);
        var result = collect("plain", true, false, false);
        assertThat(result.path("error").asText()).isEqualTo("changed_file_budget_exceeded");
        assertThat(result.path("gitCalls").asInt()).isEqualTo(4);
    }

    @Test void cleanOfflineScopeDoesNotRequireAnExistingJarOrJavaExecutable() throws Exception {
        Path source = Path.of("..", "scripts", "repoguard-review.py").toAbsolutePath().normalize();
        String python = System.getenv().getOrDefault("REPOGUARD_PREFLIGHT_PYTHON", windows() ? "python" : "python3");
        var result = JSON.readTree(run(List.of(python, "-B", "-X", "utf8", source.toString(), "--repo",
            repository.toString(), "--base", "HEAD", "--backend-jar", temporary.resolve("unused.jar").toString(),
            "--java", temporary.resolve("unused-java").toString(), "--format", "json", "--fail-on", "INFO"), null));
        assertThat(result.path("scope").asText()).isEqualTo("LOCAL_NO_CHANGES");
        assertThat(result.path("findings").size()).isZero();
        assertThat(result.path("executionSkipped").asBoolean()).isTrue();
        assertThat(result.path("productionPolicyVerified").asBoolean()).isFalse();
    }

    private JsonNode collect(String mode, boolean staged, boolean context, boolean untracked) throws Exception {
        Path source = Path.of("..", "scripts", "repoguard-review.py").toAbsolutePath().normalize();
        String python = System.getenv().getOrDefault("REPOGUARD_PREFLIGHT_PYTHON", windows() ? "python" : "python3");
        byte[] data = run(List.of(python, "-B", "-X", "utf8", "-c", ADAPTER, source.toString(), repository.toString(),
            mode, Boolean.toString(staged), Boolean.toString(context), Boolean.toString(untracked)), null);
        return JSON.readTree(data);
    }
    private void write(String path, String content) throws Exception {
        Path destination = repository.resolve(path); Files.createDirectories(destination.getParent()); Files.writeString(destination, content);
    }
    private void stage(List<String> paths) throws Exception {
        var args = new ArrayList<>(List.of("git", "add", "--")); args.addAll(paths); run(args, null);
    }
    private void git(String... args) throws Exception {
        var command = new ArrayList<>(List.of("git", "-c", "core.hooksPath=" + (windows() ? "NUL" : "/dev/null"), "-c", "commit.gpgSign=false"));
        command.addAll(List.of(args)); run(command, null);
    }
    private byte[] run(List<String> args, String input) throws Exception {
        Path out = temporary.resolve(UUID.randomUUID() + ".out"), error = temporary.resolve(UUID.randomUUID() + ".err");
        var builder = new ProcessBuilder(args).directory(repository.toFile()).redirectOutput(out.toFile()).redirectError(error.toFile());
        builder.environment().putAll(Map.of("GIT_CONFIG_GLOBAL", windows() ? "NUL" : "/dev/null", "GIT_CONFIG_NOSYSTEM", "1"));
        builder.environment().remove("GIT_DIR"); builder.environment().remove("GIT_WORK_TREE");
        var process = builder.start();
        try {
            try (var stream = process.getOutputStream()) { if (input != null) stream.write(input.getBytes(StandardCharsets.UTF_8)); }
            assertThat(process.waitFor(45, TimeUnit.SECONDS)).as("Actual fixture/collection deadline").isTrue();
            assertThat(process.exitValue()).as("Fixture/collector process success; arguments withheld").isZero();
            return Files.readAllBytes(out);
        } finally { if (process.isAlive()) process.destroyForcibly(); }
    }
    private static JsonNode file(JsonNode result, String path) {
        for (JsonNode node : result.path("files")) if (path.equals(node.path("filename").asText())) return node;
        throw new AssertionError("Expected fixture path missing");
    }
    private static boolean windows() { return System.getProperty("os.name").startsWith("Windows"); }
}
