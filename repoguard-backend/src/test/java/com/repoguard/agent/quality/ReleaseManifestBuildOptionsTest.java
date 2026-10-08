package com.repoguard.agent.quality;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;

/** Functional contracts for the production Python release verifier; no network or Docker is used. */
class ReleaseManifestBuildOptionsTest {
    @TempDir Path temporaryDirectory;

    @ParameterizedTest
    @ValueSource(strings = {"validation", "source", "reuse", "promotion"})
    void releaseOptionsStayBoundToVerifiedImageIdentity(String scenario) throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.isRegularFile(root.resolve("scripts/release-manifest.py"))) root = root.getParent();
        assertThat(root).as("repository root").isNotNull();
        String python = System.getProperty("repoguard.python.executable",
            System.getProperty("os.name").startsWith("Windows") ? "python" : "python3");
        Process process = new ProcessBuilder(python, "-B", "-c", CONTRACT,
            root.resolve("scripts/release-manifest.py").toString(), scenario, temporaryDirectory.toString())
            .directory(root.toFile()).redirectErrorStream(true).start();
        boolean completed = process.waitFor(25, TimeUnit.SECONDS);
        if (!completed) process.destroyForcibly();
        assertThat(completed).as("bounded native verifier contract").isTrue();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.exitValue()).withFailMessage(output).isZero();
        assertThat(output).contains("CASE PASS: " + scenario);
    }

    private static final String CONTRACT = """
        import argparse, copy, importlib.util, io, json, os, sys
        from contextlib import redirect_stdout
        from pathlib import Path
        from unittest.mock import patch
        spec = importlib.util.spec_from_file_location('release_manifest', sys.argv[1])
        m = importlib.util.module_from_spec(spec); spec.loader.exec_module(m)
        scenario, temporary = sys.argv[2], Path(sys.argv[3])
        digest = 'sha256:' + 'a' * 64
        config = 'sha256:' + 'b' * 64
        sha = '1' * 40
        environment = dict(GITHUB_REPOSITORY='cocojiu/PRAgent', GITHUB_SHA=sha,
            GITHUB_REF='refs/heads/main', GITHUB_EVENT_NAME='workflow_dispatch',
            GITHUB_RUN_ID='999', GITHUB_RUN_ATTEMPT='1', IMAGE_VERSION='main-test')
        def manifest(enabled=False, run=11):
            return dict(formatVersion=1, gitSha=sha, version='main-test-sse' if enabled else 'main-test',
                source=dict(repository='cocojiu/PRAgent', workflow=m.WORKFLOW, runId=run,
                    runAttempt=1, ref='refs/heads/main', event='push'),
                schema=dict(requiredVersion=103, minimumRollbackVersion=100),
                checks=dict(backendScan='passed', frontendScan='passed', backendSbom='attested', frontendSbom='attested'),
                features=dict(frontendReviewProgressStream=enabled),
                images={role:dict(source=dict(repository='ghcr.io/cocojiu/pragent-'+role, digest=digest),
                    imageId=config, platform='linux/amd64') for role in ('backend','frontend')})
        def rejected(call, message):
            try: call()
            except ValueError as error: assert message in str(error), str(error)
            else: raise AssertionError('request unexpectedly accepted: ' + message)
        if scenario == 'validation':
            legacy = manifest(); del legacy['features']
            assert m.validate(legacy) is legacy and not m.frontend_progress_stream(legacy)
            for enabled in (False, True):
                assert m.frontend_progress_stream(m.validate(manifest(enabled))) is enabled
            for invalid in ('true', 1, None, {}, []):
                value=manifest(); value['features']={'frontendReviewProgressStream':invalid}
                rejected(lambda:m.validate(value), 'Invalid release feature options')
            value=manifest(); value['features']['unknown']=False
            rejected(lambda:m.validate(value), 'Invalid release feature options')
            value=manifest(True); value['version']='main-test'
            rejected(lambda:m.validate(value), 'Release version differs from frontend build variant')
            with patch.dict(os.environ, {'VITE_REVIEW_PROGRESS_STREAM':'true','IMAGE_TAG_INPUT':'v1'}, clear=True):
                assert m.requested_image_tag() == 'v1-sse'
                os.environ['IMAGE_TAG_INPUT']='v1-sse'; assert m.requested_image_tag() == 'v1-sse'
                os.environ['IMAGE_TAG_INPUT']='x'*128
                rejected(m.requested_image_tag, 'Invalid variant image tag')
                os.environ['VITE_REVIEW_PROGRESS_STREAM']='1'
                rejected(m.requested_frontend_progress_stream, 'Invalid frontend progress stream')
            with patch.dict(os.environ, {}, clear=True): assert not m.requested_frontend_progress_stream()
            with patch.dict(os.environ, {'VITE_REVIEW_PROGRESS_STREAM':'true',
                    'DEPLOY_EXISTING_TAG':'main-test','RELEASE_RUN_ID_INPUT':'11'}, clear=True):
                rejected(lambda:m.resolve(argparse.Namespace(directory=temporary)), 'Rollback cannot override')
        elif scenario == 'source':
            reference='ghcr.io/cocojiu/pragent-frontend@'+digest
            metadata=dict(Id=config, Os='linux', Architecture='amd64', RepoDigests=[reference],
                Config={'Labels':{'io.repoguard.frontend.review-progress-stream':'true'}})
            registry={'Descriptor':{'digest':digest,'platform':{'os':'linux','architecture':'amd64'}},
                'SchemaV2Manifest':{'schemaVersion':2,'config':{'digest':config}}}
            with patch.object(m.subprocess, 'check_output', side_effect=[json.dumps([metadata]),json.dumps(registry)]):
                assert m.inspection('frontend',reference)['FrontendReviewProgressStream'] is True
            metadata['Config']['Labels']['io.repoguard.frontend.review-progress-stream']='invalid'
            with patch.object(m.subprocess, 'check_output', side_effect=[json.dumps([metadata]),json.dumps(registry)]):
                rejected(lambda:m.inspection('frontend',reference), 'Invalid inspected frontend build option')
            for requested, actual in ((False,False),(True,True),(True,False)):
                target=temporary/str(requested)/str(actual)/'source.json';target.parent.mkdir(parents=True)
                env={**environment, 'VITE_REVIEW_PROGRESS_STREAM':str(requested).lower(),
                    'IMAGE_VERSION':'main-test-sse' if requested else 'main-test',
                    'BACKEND_DIGEST':digest,'FRONTEND_DIGEST':digest}
                inspected=dict(Id=config,Os='linux',Architecture='amd64',FrontendReviewProgressStream=actual)
                with patch.dict(os.environ,env,clear=True), patch.object(m.subprocess,'run'), patch.object(m,'inspection',return_value=inspected):
                    if requested != actual:
                        rejected(lambda:m.create_source(argparse.Namespace(output=target)), 'Frontend image build option differs')
                        assert not target.exists()
                    else:
                        m.create_source(argparse.Namespace(output=target))
                        assert m.frontend_progress_stream(m.read_json(target)) is requested
        elif scenario == 'reuse':
            available={11:manifest(False,11),22:manifest(True,22)}
            verified=[]; downloads={}; bad_invocation=[False]
            def api(path):
                if '/workflows/' in path: return {'workflow_runs':[{'id':11,'run_attempt':1},{'id':22,'run_attempt':1}]}
                run=int(path.split('/runs/')[1].split('/')[0].split('?')[0])
                if '/artifacts?' in path:
                    return {'artifacts':[{'name':f'release-source-manifest-{run}-1','expired':False}]}
                return dict(id=run,path=m.WORKFLOW,head_repository={'full_name':'cocojiu/PRAgent'},
                    head_sha=sha,event='push',status='completed',conclusion='success',run_attempt=1)
            def download(args,**kwargs):
                run=int(args[3]);directory=Path(args[args.index('--dir')+1]);directory.mkdir(parents=True,exist_ok=True)
                body=json.dumps(available[run],separators=(',',':')).encode()
                (directory/'release-source-manifest.json').write_bytes(body);downloads[run]=body
            def verify(args,**kwargs):
                assert '--source-digest' in args and '--source-ref' in args and '--signer-workflow' in args
                assert '--deny-self-hosted-runners' in args
                assert args[args.index('--source-digest')+1]==sha
                assert args[args.index('--source-ref')+1]=='refs/heads/main'
                run=json.loads(Path(args[3]).read_text())['source']['runId'];verified.append(run)
                invocation=f'https://github.com/cocojiu/PRAgent/actions/runs/{run}/attempts/1'
                if bad_invocation[0]: invocation+='-wrong'
                return json.dumps([{'verificationResult':{'statement':{'predicate':{
                    'runDetails':{'metadata':{'invocationId':invocation}}}}}}])
            env={**environment,'VITE_REVIEW_PROGRESS_STREAM':'true','GITHUB_OUTPUT':str(temporary/'output')}
            with patch.dict(os.environ,env,clear=True),patch.object(m,'api',side_effect=api),patch.object(m.subprocess,'run',side_effect=download),patch.object(m.subprocess,'check_output',side_effect=verify),redirect_stdout(io.StringIO()):
                m.resolve(argparse.Namespace(directory=temporary/'auto'))
                assert (temporary/'output').read_text().startswith('reuse_run_id=22')
                assert verified==[11,22]
                assert (temporary/'auto/release-source-manifest.json').read_bytes()==downloads[22]
                rejected(lambda:m.fetch(argparse.Namespace(run_id='11',kind='source',directory=temporary/'explicit',tag='')), 'Frontend build option differs')
                os.environ['IMAGE_TAG_INPUT']='other-version'
                rejected(lambda:m.fetch(argparse.Namespace(run_id='22',kind='source',directory=temporary/'tag',tag='')), 'Image tag override differs')
                os.environ['IMAGE_TAG_INPUT']=''; bad_invocation[0]=True
                rejected(lambda:m.fetch(argparse.Namespace(run_id='22',kind='source',directory=temporary/'signature',tag='')), 'Attestation belongs to a different run')
                bad_invocation[0]=False;del available[22]
                with patch.object(m,'api',side_effect=lambda path: {'workflow_runs':[{'id':11,'run_attempt':1}]} if '/workflows/' in path else api(path)):
                    os.environ['GITHUB_OUTPUT']=str(temporary/'no-match')
                    m.resolve(argparse.Namespace(directory=temporary/'no-matching-variant'))
                    assert (temporary/'no-match').read_text().startswith('reuse_run_id=\\n')
                    assert not (temporary/'no-matching-variant/release-source-manifest.json').exists()
        elif scenario == 'promotion':
            source=temporary/'source.json'; m.write_json(source,manifest(True))
            target='registry.example/release/app'
            metadata=[dict(Id=config,Os='linux',Architecture='amd64',RepoDigests=[target+'@'+digest]),
                dict(Id=config,Os='linux',Architecture='amd64',RepoDigests=[target+'@'+digest],FrontendReviewProgressStream=True)]
            inspection=temporary/'inspection.json'; output=temporary/'approved.json'
            args=argparse.Namespace(source=source,inspections=inspection,target_repository=target,output=output)
            with patch.dict(os.environ,environment,clear=True):
                for invalid in (False,1):
                    metadata[1]['FrontendReviewProgressStream']=invalid;m.write_json(inspection,metadata)
                    rejected(lambda:m.promote(args), 'Promotion changed the frontend build option')
                    assert not output.exists()
                metadata[1]['FrontendReviewProgressStream']=True;m.write_json(inspection,metadata)
                m.promote(args);assert m.frontend_progress_stream(m.validate(m.read_json(output),True))
        print('CASE PASS: '+scenario)
        """;
}
