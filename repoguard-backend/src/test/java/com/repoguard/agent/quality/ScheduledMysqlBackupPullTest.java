package com.repoguard.agent.quality;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Exercises the backup client on local files with a bounded remote transport double. */
class ScheduledMysqlBackupPullTest {
    @TempDir Path temporaryDirectory;

    @ParameterizedTest
    @ValueSource(strings = {"native", "windows-path", "posix-path", "integrity", "unsafe-catalog"})
    void downloadsOnlyVerifiedArchivesWithoutOverwritingExistingCopies(String scenario) throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.isRegularFile(root.resolve("scripts/scheduled-mysql-backup.py"))) {
            root = root.getParent();
        }
        assertThat(root).as("repository root").isNotNull();
        String python = System.getProperty("repoguard.python.executable",
            System.getProperty("os.name").startsWith("Windows") ? "python" : "python3");
        Process process = new ProcessBuilder(python, "-B", "-c", CONTRACT,
            root.resolve("scripts/scheduled-mysql-backup.py").toString(), scenario,
            temporaryDirectory.toString()).directory(root.toFile()).redirectErrorStream(true).start();
        boolean completed = process.waitFor(25, TimeUnit.SECONDS);
        if (!completed) process.destroyForcibly();
        assertThat(completed).as("bounded native backup client contract").isTrue();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.exitValue()).withFailMessage(output).isZero();
        assertThat(output).contains("CASE PASS: " + scenario);
    }

    private static final String CONTRACT = """
        import hashlib, importlib.util, io, json, sys
        from contextlib import redirect_stdout
        from pathlib import Path, PurePosixPath, PureWindowsPath
        from unittest.mock import patch
        spec = importlib.util.spec_from_file_location('scheduled_backup', sys.argv[1])
        m = importlib.util.module_from_spec(spec); spec.loader.exec_module(m)
        scenario, destination = sys.argv[2], Path(sys.argv[3]) / 'encrypted-copies'
        name = 'repoguard-20300101T000000Z.sql.gz.enc'
        body = bytes(range(256)) * 4
        entry = {'name': name, 'bytes': len(body), 'sha256': hashlib.sha256(body).hexdigest()}
        entries, commands = [entry], []
        download_body = body
        expected_remote = 'cat -- /opt/repoguard/backups/scheduled-mysql/' + name
        def transport(args, output, limit, timeout):
            assert args[:3] == ['ssh', '-o', 'BatchMode=yes']
            assert 'StrictHostKeyChecking=yes' in args
            assert args[-2] == 'configured-production-host'
            assert 0 < timeout <= 300
            commands.append(args[-1])
            if args[-1] == 'python3 /opt/repoguard/scripts/scheduled-mysql-backup.py catalog':
                content = json.dumps(entries).encode()
                assert limit == 64 * 1024
            else:
                assert args[-1] == expected_remote, args[-1]
                assert limit == len(body)
                content = download_body
            output.write(content)
            return len(content)
        def pull():
            with patch.object(m, 'bounded_command', side_effect=transport), redirect_stdout(io.StringIO()):
                m.pull('configured-production-host', destination)
        def rejected(reason):
            try: pull()
            except ValueError as error: assert str(error) == reason, str(error)
            else: raise AssertionError('invalid archive unexpectedly accepted')
        if scenario == 'windows-path':
            m.ROOT = PureWindowsPath('/opt/repoguard/backups/scheduled-mysql')
        elif scenario == 'posix-path':
            m.ROOT = PurePosixPath('/opt/repoguard/backups/scheduled-mysql')
        if scenario in ('native', 'windows-path', 'posix-path'):
            pull()
            assert (destination / name).read_bytes() == body
            assert json.loads((destination / (name + '.json')).read_text()) == entry
            assert not (destination / (name + '.partial')).exists()
            assert commands == ['python3 /opt/repoguard/scripts/scheduled-mysql-backup.py catalog', expected_remote]
            commands.clear(); pull()
            assert commands == ['python3 /opt/repoguard/scripts/scheduled-mysql-backup.py catalog']
            assert (destination / name).read_bytes() == body
        elif scenario == 'integrity':
            download_body = b'x' * len(body)
            rejected('download_checksum_mismatch')
            assert not (destination / name).exists()
            assert not (destination / (name + '.partial')).exists()
            assert not (destination / (name + '.json')).exists()
            download_body = body; pull()
            (destination / name).write_bytes(b'existing copy')
            commands.clear(); rejected('local_archive_mismatch')
            assert (destination / name).read_bytes() == b'existing copy'
            assert commands == ['python3 /opt/repoguard/scripts/scheduled-mysql-backup.py catalog']
        elif scenario == 'unsafe-catalog':
            for invalid in ('../' + name, '/tmp/' + name, 'nested/' + name):
                entries = [{**entry, 'name': invalid}]
                commands.clear(); rejected('invalid_catalog_entry')
                assert commands == ['python3 /opt/repoguard/scripts/scheduled-mysql-backup.py catalog']
            assert not list(destination.iterdir())
        print('CASE PASS: ' + scenario)
        """;
}
