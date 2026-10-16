package com.repoguard.agent.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

@EnabledOnOs(OS.LINUX)
@EnabledIfEnvironmentVariable(named = "REPOGUARD_LOKI_RECOVERY_REHEARSAL", matches = "true")
class LokiSnapshotLinuxIntegrationTest {
    @TempDir Path temporaryDirectory;

    @Test
    void encryptedVolumeRestorePreservesLokiLogsAndAlloyCursorAcrossUpgradeAndRollback() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.isRegularFile(root.resolve("scripts/loki_snapshot.py"))) root = root.getParent();
        assertThat(root).isNotNull();
        String python = System.getProperty("repoguard.python.executable", "python3");
        Process process = new ProcessBuilder("sudo", "-n", python, "-B", "-c", CONTRACT,
            root.toString(), temporaryDirectory.toString()).directory(root.toFile()).redirectErrorStream(true).start();
        boolean completed = process.waitFor(480, TimeUnit.SECONDS);
        if (!completed) process.destroyForcibly();
        assertThat(completed).as("bounded isolated Loki backup and recovery").isTrue();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.exitValue()).withFailMessage(output).isZero();
        assertThat(output).contains("LOKI_RECOVERY_FIXTURE_PASSED");
    }

    private static final String CONTRACT = """
        import collections, hashlib, importlib.util, json, os, secrets, shutil, subprocess, sys, time, urllib.parse, urllib.request
        from pathlib import Path
        root,temporary=Path(sys.argv[1]),Path(sys.argv[2])
        spec=importlib.util.spec_from_file_location('loki_snapshot',root/'scripts/loki_snapshot.py')
        m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
        assert os.geteuid()==0
        fixture=temporary/'owned-loki-fixture';fixture.mkdir(mode=0o755)
        assert fixture.resolve().parent==temporary.resolve()
        owner='loki-fixture-'+secrets.token_hex(6);label='repoguard.loki-fixture-owner='+owner
        network=owner+'-network';containers=[];volumes=[];network_created=False
        baseline='grafana/loki:3.7.8@sha256:1107dd5274e0ada47e42472b7a7e71f3b2a2fe878878108f3e2f9e51528f0193'
        candidate='ghcr.io/cocojiu/pragent-loki@sha256:870766ebe4760b30077fd47206b21cfd3722f6f5f4dc6fdadc83612a179ee491'
        collector='grafana/alloy:v1.18.0@sha256:491b0578c04983fd54fe99b587b6fab4404dc46d0dc16677bd6b00cc1140b308'
        def docker(*args):return subprocess.check_output(['docker',*args],stderr=subprocess.STDOUT,timeout=120).decode().strip()
        def inspect(name):return json.loads(docker('inspect',name))[0]
        def http(base,path,data=None):
            body=None if data is None else json.dumps(data).encode()
            request=urllib.request.Request(base+path,data=body,headers={'Content-Type':'application/json'})
            with urllib.request.urlopen(request,timeout=5) as response:return response.status,response.read()
        def wait(check,description):
            deadline=time.monotonic()+90
            while time.monotonic()<deadline:
                try:
                    if check():return
                except (OSError,ValueError,KeyError):pass
                time.sleep(2)
            raise RuntimeError(description+' timeout')
        def new_volume(suffix):
            name=owner+'-'+suffix;docker('volume','create','--label',label,name);volumes.append(name)
            path=Path(json.loads(docker('volume','inspect',name))[0]['Mountpoint'])
            assert path.is_dir() and path.resolve()==path and str(path).startswith('/var/lib/docker/volumes/')
            return name,path
        def stopped(names):
            return all(inspect(name)['State']['Status']=='exited' and inspect(name)['State']['ExitCode']==0
                and not inspect(name)['State']['OOMKilled'] for name in names)
        def stop_pair(pair):
            for name in reversed(pair):docker('stop','--time','45',name)
            assert stopped(pair)
        config=fixture/'collector.alloy'
        config.write_text('logging { level = "warn" }\\n'
            'loki.write "fixture" {\\n endpoint { url = "http://loki:3100/loki/api/v1/push" }\\n}\\n'
            'loki.source.file "fixture" {\\n targets = [{__path__ = "/fixture/events.log", job = "repoguard-recovery-fixture"}]\\n'
            ' forward_to = [loki.write.fixture.receiver]\\n}\\n')
        config.chmod(0o644)
        events=fixture/'events.log';events.write_text('');events.chmod(0o644)
        query_start=str(time.time_ns()-1_000_000_000)
        def start_pair(phase,image,data):
            loki=owner+'-'+phase+'-loki';alloy=owner+'-'+phase+'-alloy'
            docker('run','-d','--name',loki,'--label',label,'--network',network,'--network-alias','loki',
                '--read-only','--memory','384m','--cpus','0.50','--cap-drop','ALL','--security-opt','no-new-privileges',
                '--tmpfs','/tmp:rw,noexec,nosuid,size=32m','-v',data['loki'][0]+':/loki',
                '-v',str(root/'config/observability/loki-runtime-metrics.yml')+':/etc/loki/loki.yml:ro',
                '-v',str(root/'config/observability/runtime-rules')+':/etc/loki/runtime-rules:ro',
                image,'-config.file=/etc/loki/loki.yml','-ingester.wal-dir=/loki/wal')
            containers.append(loki)
            state=inspect(loki);assert state['HostConfig']['Memory']==402653184
            assert not any(binding for binding in (state['NetworkSettings']['Ports'] or {}).values())
            base='http://'+state['NetworkSettings']['Networks'][network]['IPAddress']+':3100'
            wait(lambda:http(base,'/ready')[0]==200,phase+' Loki readiness')
            wait(lambda:len(json.loads(http(base,'/prometheus/api/v1/rules')[1])['data']['groups'][0]['rules'])==6,phase+' runtime rules')
            docker('run','-d','--name',alloy,'--label',label,'--network',network,'--user','473:473',
                '--memory','256m','--cpus','0.25','--cap-drop','ALL','--security-opt','no-new-privileges',
                '-v',data['alloy'][0]+':/var/lib/alloy/data','-v',str(config)+':/etc/alloy/config.alloy:ro',
                '-v',str(events)+':/fixture/events.log:ro',collector,'run','--storage.path=/var/lib/alloy/data',
                '--server.http.listen-addr=0.0.0.0:12345','--disable-reporting','/etc/alloy/config.alloy')
            containers.append(alloy)
            return (loki,alloy),base
        def messages(base):
            query=urllib.parse.urlencode(dict(query='{job="repoguard-recovery-fixture"}',start=query_start,end=time.time_ns(),limit=100))
            result=json.loads(http(base,'/loki/api/v1/query_range?'+query)[1])
            return collections.Counter(value[1] for stream in result['data']['result'] for value in stream['values'])
        try:
            for image in (baseline,candidate,collector):docker('pull',image)
            docker('network','create','--internal','--label',label,network);network_created=True
            original={role:new_volume('original-'+role) for role in ('loki','alloy')}
            for role,uid in [('loki',10001),('alloy',473)]:os.chown(original[role][1],uid,uid)
            pair,base=start_pair('baseline',baseline,original)
            first='baseline-'+owner;second='candidate-'+owner
            with events.open('a') as output:output.write(first+'\\n')
            wait(lambda:messages(base)[first]==1,'baseline collector delivery')
            stop_pair(pair)
            assert any(path.is_file() for path in original['alloy'][1].rglob('*')),'Alloy did not persist its real cursor'
            key=fixture/'key';key.write_text(secrets.token_hex(32));key.chmod(0o600)
            sources={role:data[1] for role,data in original.items()}
            before=m.catalog(sources)
            snapshot=m.create_snapshot(sources,fixture/'snapshot.tar.gz.enc',key,lambda:stopped(pair))
            assert not snapshot['restoreVerified']
            cloned={role:new_volume('restored-'+role) for role in ('loki','alloy')}
            restored=m.restore_snapshot(snapshot,{role:data[1] for role,data in cloned.items()},key)
            assert restored['restoreVerified'] and restored['restoredFiles']>0
            pair2,base2=start_pair('candidate',candidate,cloned)
            wait(lambda:messages(base2)[first]==1,'restored historical log')
            with events.open('a') as output:output.write(second+'\\n')
            wait(lambda:messages(base2)==collections.Counter({first:1,second:1}),'restored Alloy cursor delivery')
            stop_pair(pair2)
            pair3,base3=start_pair('rollback',baseline,cloned)
            wait(lambda:messages(base3)==collections.Counter({first:1,second:1}),'rollback persisted logs and collector cursor')
            stop_pair(pair3)
            assert m.catalog(sources)==before,'original snapshot volumes changed'
            evidence=dict(state='verified',encryptedBackupRestored=True,realAlloyCursorRetained=True,
                persistedLogsAfterRollback=2,originalVolumesUnchanged=True,productionWrites=0,
                publishedPorts=0,archiveSha256=snapshot['archiveSha256'],restoredFiles=restored['restoredFiles'])
            (root/'repoguard-backend/target/loki-recovery-evidence.json').write_text(json.dumps(evidence,indent=2))
            print(json.dumps(evidence));print('LOKI_RECOVERY_FIXTURE_PASSED')
        finally:
            for name in reversed(containers):
                state=inspect(name);assert state['Config']['Labels'].get('repoguard.loki-fixture-owner')==owner
                docker('rm','-f',name)
            for name in volumes:
                metadata=json.loads(docker('volume','inspect',name))[0]
                assert metadata['Labels'].get('repoguard.loki-fixture-owner')==owner
                docker('volume','rm',name)
            if network_created:
                metadata=json.loads(docker('network','inspect',network))[0]
                assert metadata['Labels'].get('repoguard.loki-fixture-owner')==owner
                docker('network','rm',network)
            assert fixture.resolve().parent==temporary.resolve() and fixture.name=='owned-loki-fixture'
            shutil.rmtree(fixture)
        """;
}
