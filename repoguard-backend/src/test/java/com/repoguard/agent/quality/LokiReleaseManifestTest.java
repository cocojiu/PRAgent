package com.repoguard.agent.quality;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Functional trust-boundary contracts for the production Loki release consumer. */
class LokiReleaseManifestTest {
    @TempDir Path temporaryDirectory;

    @ParameterizedTest
    @ValueSource(strings = {"identity", "checks", "runtime", "types", "attestation", "untrusted-run", "existing-output", "output-race"})
    void rejectsUntrustedSourcesAndPreservesExistingAssets(String scenario) throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.isRegularFile(root.resolve("scripts/loki-release.py"))) root = root.getParent();
        assertThat(root).isNotNull();
        String python = System.getProperty("repoguard.python.executable",
            System.getProperty("os.name").startsWith("Windows") ? "python" : "python3");
        Process process = new ProcessBuilder(python, "-B", "-c", CONTRACT,
            root.resolve("scripts/loki-release.py").toString(), scenario, temporaryDirectory.toString())
            .directory(root.toFile()).redirectErrorStream(true).start();
        boolean completed = process.waitFor(25, TimeUnit.SECONDS);
        if (!completed) process.destroyForcibly();
        assertThat(completed).as("bounded release contract").isTrue();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.exitValue()).withFailMessage(output).isZero();
        assertThat(output).contains("CASE PASS: " + scenario);
    }

    private static final String CONTRACT = """
        import argparse, copy, importlib.util, json, sys
        from pathlib import Path
        from unittest.mock import patch
        spec=importlib.util.spec_from_file_location('loki_release',sys.argv[1])
        m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
        scenario,temporary=sys.argv[2],Path(sys.argv[3])
        repository='cocojiu/PRAgent';sha='1'*40;run_id=42;attempt=1
        invocation=f'https://github.com/{repository}/actions/runs/{run_id}/attempts/{attempt}'
        def manifest():
            return dict(formatVersion=1,version=m.VERSION,upstreamGitSha=m.UPSTREAM,
                source=dict(repository=repository,workflow=m.SOURCE_WORKFLOW,gitSha=sha,ref='refs/heads/main',runId=run_id,runAttempt=attempt),
                image=dict(repository='ghcr.io/cocojiu/pragent-loki',digest='sha256:'+'a'*64,imageId='sha256:'+'b'*64,platform='linux/amd64'),
                checks=dict(highCriticalCount=0,configurationValidation='passed',persistenceUpgradeRollback='passed',
                    independentImageTransfer='passed',imageProvenance='attested',sbom='attested'),
                runtime=dict(configSha256=m.RUNTIME_CONFIG_SHA,memoryLimitBytes=402653184,cpuLimit=0.5,expectedRules=sorted(m.RUNTIME_RULES)),
                archiveSha256='c'*64,evidenceSha256={name:'d'*64 for name in ('trivy.json','sbom.cdx.json','persistence-result.json','transfer-verification.json')})
        def validate(value):return m.validate_source(value,repository,sha,run_id,attempt)
        def rejected(call,reason):
            try:call()
            except ValueError as error:assert reason in str(error),str(error)
            else:raise AssertionError('unexpected acceptance: '+reason)
        assert validate(manifest())['image']['platform']=='linux/amd64'
        if scenario=='identity':
            for key,bad in [('ref','refs/heads/feature'),('gitSha','2'*40),('runId',43),('runAttempt',2),('workflow','other.yml')]:
                value=manifest();value['source'][key]=bad
                rejected(lambda:validate(value),'source_identity_mismatch')
            for key,bad in [('repository','ghcr.io/untrusted/loki'),('platform','linux/arm64'),('digest','latest')]:
                value=manifest();value['image'][key]=bad
                rejected(lambda:validate(value),'invalid_image_digest' if key=='digest' else 'unexpected_image_target')
        elif scenario=='checks':
            for key,bad in [('highCriticalCount',1),('highCriticalCount',False),('independentImageTransfer','skipped'),('sbom','missing')]:
                value=manifest();value['checks'][key]=bad
                rejected(lambda:validate(value),'required_checks_missing')
            value=manifest();del value['evidenceSha256']['persistence-result.json']
            rejected(lambda:validate(value),'source_evidence_missing')
        elif scenario=='runtime':
            for key,bad in [('memoryLimitBytes',805306368),('cpuLimit',1.0),('expectedRules',list(m.RUNTIME_RULES)[:-1]),
                    ('expectedRules',list(m.RUNTIME_RULES)+['unexpected']),('configSha256','a'*64)]:
                value=manifest();value['runtime'][key]=bad
                reason='runtime_configuration_mismatch' if key=='configSha256' else 'runtime_rule_gate_missing' if key=='expectedRules' else 'runtime_resource_gate_missing'
                rejected(lambda:validate(value),reason)
        elif scenario=='types':
            for bad in (True,0,-1,'42'):
                rejected(lambda:m.validate_source(manifest(),repository,sha,bad,1),'invalid_source_run')
            for key,bad in [('formatVersion',True),('formatVersion',1.0),('version','3.7.8')]:
                value=manifest();value[key]=bad
                rejected(lambda:validate(value),'unsupported_loki_release')
            value=manifest();value['runtime']['cpuLimit']=True
            rejected(lambda:validate(value),'runtime_resource_gate_missing')
        elif scenario=='attestation':
            statement=dict(predicateType='https://slsa.dev/provenance/v1',predicate=dict(runDetails=dict(metadata=dict(invocationId=invocation))))
            verified=[dict(verificationResult=dict(signature=dict(certificate=dict(runInvocationUri=invocation)),statement=statement))]
            commands=[]
            def command(args,timeout=45):commands.append(args);return json.dumps(verified).encode()
            with patch.object(m,'command',side_effect=command):
                m.verify_attestation('subject',temporary/'proof.json',statement['predicateType'],repository,sha,invocation)
                assert '--deny-self-hosted-runners' in commands[0]
                assert commands[0][commands[0].index('--signer-digest')+1]==sha
                assert commands[0][commands[0].index('--source-ref')+1]=='refs/heads/main'
                verified[0]['verificationResult']['signature']['certificate']['runInvocationUri']=invocation.replace('/1','/2')
                rejected(lambda:m.verify_attestation('subject',temporary/'bad.json',statement['predicateType'],repository,sha,invocation),'certificate_run_attempt_mismatch')
                assert not (temporary/'bad.json').exists()
        else:
            args=argparse.Namespace(repository=repository,source_sha=sha,run_id=run_id,output=temporary/'result')
            run=dict(path=m.SOURCE_WORKFLOW,head_repository=dict(full_name=repository),event='workflow_dispatch',head_branch='main',
                head_sha=sha,status='completed',conclusion='success',run_attempt=1)
            names=['Validate immutable observability images','Isolated Loki security rebuild','Verify Loki image on a fresh runner','Publish checked Loki source image']
            def github(repo,endpoint):
                if '/jobs?' in endpoint:return dict(jobs=[dict(name=name,conclusion='success') for name in names])
                if '/artifacts?' in endpoint:return dict(artifacts=[dict(name='loki-source-manifest-42-1',expired=False)])
                return run
            commands=[]
            def command(args,timeout=45):
                commands.append(args)
                directory=Path(args[args.index('--dir')+1]);m.write_json(directory/'loki-source-manifest.json',manifest())
                return b''
            def attest(subject,path,predicate,repo,digest,expected):
                Path(path).write_text('[]')
                if predicate=='https://cyclonedx.org/bom':
                    args.output.mkdir();(args.output/'user-asset.txt').write_text('preserve')
                    return [dict(subject=[dict(digest=dict(sha256='a'*64))],predicate=dict(bomFormat='CycloneDX',
                        components=[dict(name='stdlib',version='v1.26.9'),dict(name='golang.org/x/net',version='v0.60.0')]))]
                return [dict(subject=[dict(digest=dict(sha256='a'*64))])]
            with patch.object(m,'github',side_effect=github),patch.object(m,'command',side_effect=command),patch.object(m,'verify_attestation',side_effect=attest):
                if scenario=='untrusted-run':
                    run['head_branch']='feature'
                    rejected(lambda:m.fetch_source(args),'untrusted_source_run');assert not commands
                elif scenario=='existing-output':
                    args.output.mkdir();(args.output/'user-asset.txt').write_text('preserve')
                    rejected(lambda:m.fetch_source(args),'unsafe_output_directory');assert not commands
                else:
                    try:m.fetch_source(args)
                    except FileExistsError:pass
                    else:raise AssertionError('concurrent output directory was overwritten')
                if scenario!='untrusted-run':
                    assert (args.output/'user-asset.txt').read_text()=='preserve'
                    assert not (args.output/'verification-result.json').exists()
        print('CASE PASS: '+scenario)
        """;
}
