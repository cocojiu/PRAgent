package com.repoguard.agent.quality;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class LokiSnapshotSafetyTest {
    @TempDir Path temporaryDirectory;

    @ParameterizedTest
    @ValueSource(strings = {"names", "catalog", "writer-gate", "size-limit", "read-limit", "existing-destination"})
    void snapshotBoundariesRejectUnsafeInputsBeforeTouchingExistingData(String scenario) throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.isRegularFile(root.resolve("scripts/loki_snapshot.py"))) root = root.getParent();
        assertThat(root).isNotNull();
        String python = System.getProperty("repoguard.python.executable",
            System.getProperty("os.name").startsWith("Windows") ? "python" : "python3");
        Process process = new ProcessBuilder(python, "-B", "-c", CONTRACT,
            root.resolve("scripts/loki_snapshot.py").toString(), scenario, temporaryDirectory.toString())
            .directory(root.toFile()).redirectErrorStream(true).start();
        boolean completed = process.waitFor(25, TimeUnit.SECONDS);
        if (!completed) process.destroyForcibly();
        assertThat(completed).isTrue();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.exitValue()).withFailMessage(output).isZero();
        assertThat(output).contains("CASE PASS: " + scenario);
    }

    private static final String CONTRACT = """
        import hashlib, importlib.util, io, os, sys
        from pathlib import Path
        from unittest.mock import patch
        spec=importlib.util.spec_from_file_location('loki_snapshot',sys.argv[1])
        m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
        scenario,temporary=sys.argv[2],Path(sys.argv[3])
        def rejected(call,reason):
            try:call()
            except ValueError as error:assert reason in str(error),str(error)
            else:raise AssertionError('unexpected acceptance: '+reason)
        roots={role:temporary/role for role in ('loki','alloy')}
        for root in roots.values():root.mkdir()
        if scenario=='names':
            assert m.safe_name('loki/chunks/one')==['loki','chunks','one']
            for name in ('../loki','/loki/data','loki/../outside','loki//data','loki/./data','database/file','loki\\\\file','alloy/'+chr(0)):
                rejected(lambda:m.safe_name(name),'unsafe_snapshot_name')
        elif scenario=='catalog':
            (roots['loki']/'wal').mkdir();(roots['loki']/'wal'/'0001').write_bytes(b'persisted')
            value=m.catalog(roots)
            assert value['totalBytes']==9 and len(value['entries'])==4
            file=next(item for item in value['entries'] if item['name']=='loki/wal/0001')
            assert file['sha256']==hashlib.sha256(b'persisted').hexdigest()
            if os.name=='posix':
                (roots['alloy']/'escape').symlink_to(temporary/'outside')
                rejected(lambda:m.catalog(roots),'snapshot_entry_limit_or_symlink')
        elif scenario=='writer-gate':
            with patch.object(m,'protected_key',return_value=temporary/'key'),patch.object(m.subprocess,'Popen') as process:
                rejected(lambda:m.create_snapshot(roots,temporary/'snapshot.enc',temporary/'key',lambda:False),'snapshot_writers_still_running')
                process.assert_not_called();assert not (temporary/'snapshot.enc.partial').exists()
        elif scenario=='size-limit':
            (roots['loki']/'large').write_bytes(b'123')
            with patch.object(m,'MAX_DATA',2):rejected(lambda:m.catalog(roots),'snapshot_data_budget_exceeded')
            with patch.object(m,'MAX_ENTRIES',1):rejected(lambda:m.catalog(roots),'snapshot_entry_limit_or_symlink')
        elif scenario=='read-limit':
            reader=m.BoundedReader(io.BytesIO(b'1234'))
            for size in (-1,17*1024*1024):rejected(lambda:reader.read(size),'unbounded_snapshot_read')
            assert reader.count==0
            with patch.object(m,'MAX_ARCHIVE',3):rejected(lambda:reader.read(4),'snapshot_expansion_budget_exceeded')
        else:
            archive=temporary/'fixture.enc';archive.write_bytes(b'not-decrypted')
            snapshot=dict(archive=str(archive),archiveSha256=hashlib.sha256(archive.read_bytes()).hexdigest())
            (roots['loki']/'user-asset').write_text('preserve')
            with patch.object(m,'protected_key',return_value=temporary/'key'),patch.object(m.subprocess,'Popen') as process:
                rejected(lambda:m.restore_snapshot(snapshot,roots,temporary/'key'),'restore_destination_not_empty_or_unsafe')
                assert (roots['loki']/'user-asset').read_text()=='preserve';process.assert_not_called()
                snapshot['archiveSha256']='a'*64
                rejected(lambda:m.restore_snapshot(snapshot,roots,temporary/'key'),'snapshot_checksum_mismatch')
                assert (roots['loki']/'user-asset').read_text()=='preserve';process.assert_not_called()
        print('CASE PASS: '+scenario)
        """;
}
