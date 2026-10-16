package com.repoguard.agent.quality;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class LokiDeploymentSafetyTest {
    @TempDir Path temporaryDirectory;

    @ParameterizedTest
    @ValueSource(strings = {"compose-scope", "log-retention", "confirmation", "signal-cancel", "unclean-canary", "partial-rollback", "pre-switch-recovery"})
    void failedUpgradePreservesUnrelatedServicesAndOriginalData(String scenario) throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.isRegularFile(root.resolve("scripts/loki-deploy.py"))) root = root.getParent();
        assertThat(root).isNotNull();
        String python = System.getProperty("repoguard.python.executable",
            System.getProperty("os.name").startsWith("Windows") ? "python" : "python3");
        Process process = new ProcessBuilder(python, "-B", "-c", CONTRACT,
            root.resolve("scripts/loki-deploy.py").toString(), scenario, temporaryDirectory.toString())
            .directory(root.toFile()).redirectErrorStream(true).start();
        boolean completed = process.waitFor(25, TimeUnit.SECONDS);
        if (!completed) process.destroyForcibly();
        assertThat(completed).isTrue();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.exitValue()).withFailMessage(output).isZero();
        assertThat(output).contains("CASE PASS: " + scenario);
    }

    private static final String CONTRACT = """
        import argparse, copy, importlib.util, json, sys
        from pathlib import Path
        from types import SimpleNamespace
        from unittest.mock import Mock, patch
        spec=importlib.util.spec_from_file_location('loki_deploy',sys.argv[1])
        m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
        scenario,temporary=sys.argv[2],Path(sys.argv[3])
        def rejected(call,reason):
            try:call()
            except ValueError as error:assert reason in str(error),str(error)
            else:raise AssertionError('unexpected acceptance: '+reason)
        plan=dict(volumes={role:dict(logicalName=role+'_data',name='original-'+role,mountpoint=str(temporary/('original-'+role))) for role in ('loki','alloy')},
            baselineImages=dict(loki='old-loki',alloy='same-alloy'),containerIds=dict(loki='original-loki-id',alloy='original-alloy-id'))
        clones={role:dict(name='restored-'+role,mountpoint=str(temporary/('restored-'+role))) for role in ('loki','alloy')}
        owner='loki-security-123456789-1'
        deployment=m.Deployment.__new__(m.Deployment)
        deployment.args=argparse.Namespace(confirmation='UPGRADE_LOKI_SECURITY',release_id='123456789-1')
        deployment.manifest=dict(image=dict(imageId='checked-id'));deployment.reference='checked-loki'
        deployment.owner=owner;deployment.stage=temporary/'stage';deployment.stage.mkdir()
        deployment.record=dict(events=[]);deployment.plan=plan;deployment.clones=clones
        deployment.host=Mock();deployment.canary=None;deployment.network=None;deployment.switch_started=False;deployment.stopped=False
        deployment.protected={}
        events=[]
        def audit(state,**values):events.append(state);deployment.record.update(state=state,**values)
        deployment.audit=audit
        if scenario=='compose-scope':
            before=dict(services=dict(loki=dict(image='old-loki'),alloy=dict(image='same-alloy'),api=dict(image='same-api')),
                volumes={role+'_data':dict(name='original-'+role,external=True) for role in ('loki','alloy')})
            after=copy.deepcopy(before);after['services']['loki']['image']='checked-loki'
            for role in ('loki','alloy'):after['volumes'][role+'_data']['name']='restored-'+role
            m.validate_compose_change(before,after,plan,clones,'checked-loki')
            assert before['volumes']['loki_data']['name']=='original-loki'
            value=copy.deepcopy(after);value['services']['api']['image']='unexpected-api'
            rejected(lambda:m.validate_compose_change(before,value,plan,clones,'checked-loki'),'compose_change_exceeds_reviewed_scope')
            value=copy.deepcopy(after);value['services']['loki']['ports']=['0.0.0.0:3100:3100']
            rejected(lambda:m.validate_compose_change(before,value,plan,clones,'checked-loki'),'compose_change_exceeds_reviewed_scope')
        elif scenario=='log-retention':
            expected=dict(sampleHashes=['a','b'])
            assert m.retains_logs(dict(sampleHashes=['a','b','c']),expected)
            assert not m.retains_logs(dict(sampleHashes=['b','c']),expected)
            assert not m.retains_logs(dict(sampleHashes=['a']),dict(sampleHashes=[]))
        elif scenario=='confirmation':
            argv=['loki-deploy.py','upgrade','--repository','cocojiu/PRAgent','--source-sha','1'*40,'--run-id','42',
                '--run-attempt','1','--manifest','unread-file','--manifest-sha','a'*64,'--release-id','123456789-1','--confirmation','wrong']
            with patch.object(sys,'argv',argv),patch.object(m.os,'open') as opened:
                rejected(m.main,'explicit_production_confirmation_required');opened.assert_not_called()
        elif scenario=='signal-cancel':
            with patch.object(m.signal,'signal') as handled:
                try:m.interrupted_operation(15,None)
                except InterruptedError as error:assert str(error)=='production_operation_interrupted'
                else:raise AssertionError('workflow cancellation was ignored')
                assert handled.call_count>=2
                assert all(call.args[1]==m.signal.SIG_IGN for call in handled.call_args_list)
        elif scenario=='unclean-canary':
            deployment.canary='owned-canary';deployment.network='owned-network'
            deployment.host.inspect.side_effect=[dict(Config=dict(Labels={'repoguard.loki-release-owner':owner}),State=dict(Status='running')),
                dict(State=dict(Status='exited',ExitCode=137,OOMKilled=False))]
            calls=[]
            def docker(*args,**options):
                calls.append(args)
                return json.dumps([dict(Labels={'repoguard.loki-release-owner':owner})]).encode() if args[:2]==('network','inspect') else b''
            with patch.object(m,'docker',side_effect=docker):
                rejected(deployment.remove_canary,'canary_did_not_stop_cleanly')
            assert deployment.canary is None and deployment.network is None
            assert ('rm','owned-canary') in calls and ('network','rm','owned-network') in calls
        elif scenario=='partial-rollback':
            deployment.remove_canary=Mock()
            deployment.apply_overlay=Mock(return_value='reviewed-rollback-overlay')
            deployment.verify_active=Mock()
            def probe(args,**options):
                if args[-1]=='repoguard-alloy':return SimpleNamespace(returncode=1,stdout=b'')
                return SimpleNamespace(returncode=0,stdout=json.dumps([dict(Config=dict(Image='checked-loki',Labels={'com.docker.compose.project':'repoguard-observability'}),State=dict(Status='running'),Id='new-loki')]).encode())
            with patch.object(m.subprocess,'run',side_effect=probe),patch.object(m,'docker') as docker:
                deployment.rollback_active({'start':'1','end':'2'},{'sampleHashes':['a']})
            deployment.apply_overlay.assert_called_once_with('old-loki','rollback.compose.json')
            assert deployment.record['rollbackUsesRestoredActiveVolumes'] and deployment.record['originalVolumesPreserved']
            assert plan['volumes']['loki']['name']=='original-loki' and deployment.clones==clones
        else:
            deployment.verify_image=Mock();deployment.frozen=Mock(return_value=True)
            deployment.canary='cleanup-will-fail'
            deployment.remove_canary=Mock(side_effect=ValueError('unknown_canary_owner'))
            calls=[]
            def docker(*args,**options):calls.append(args);return b''
            def directory(path):Path(path).mkdir(parents=True,exist_ok=True);return Path(path)
            with patch.object(m,'ROOT',temporary),patch.object(m,'ensure_directory',side_effect=directory), \
                    patch.object(m.release,'production_preflight',return_value=plan),patch.object(m,'container_baseline',return_value={}), \
                    patch.object(m,'available_memory',return_value=1024**3),patch.object(m,'docker',side_effect=docker), \
                    patch.object(m,'log_fingerprint',return_value={'sampleHashes':['a']}),patch.object(m,'wait_for'), \
                    patch.object(m,'write_record'),patch.object(m.snapshot,'create_snapshot',side_effect=ValueError('snapshot_fault')):
                rejected(deployment.upgrade,'snapshot_fault')
            assert ('start','original-loki-id') in calls and ('start','original-alloy-id') in calls
            assert 'original_services_restarted' in events
            assert not any(call[:1] in [('rm',),('volume',)] for call in calls)
        print('CASE PASS: '+scenario)
        """;
}
