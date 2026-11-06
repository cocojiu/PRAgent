#!/usr/bin/env bash
# Transfer the checked Loki image through SSH; the host never contacts GHCR.
set -euo pipefail
source_manifest="$1"
target_manifest="$2"
DEPLOY_PORT="$(python3 -c 'import os; print(os.environ.get("DEPLOY_PORT") or "22")')"
python3 - "$source_manifest" <<'PY'
import hashlib,importlib.util,os,pathlib,re,sys
p=pathlib.Path('scripts/loki-release.py')
s=importlib.util.spec_from_file_location('loki_release',p);m=importlib.util.module_from_spec(s);s.loader.exec_module(m)
v=m.read_json(sys.argv[1]);raw=pathlib.Path(sys.argv[1]).read_bytes();identity=v['source']
m.validate_source(v,os.environ['GITHUB_REPOSITORY'],identity['gitSha'],identity['runId'],identity['runAttempt'])
assert hashlib.sha256(raw).hexdigest()==os.environ['EXPECTED_MANIFEST_SHA']
assert os.environ['GITHUB_REF']=='refs/heads/main' and re.fullmatch('[a-f0-9]{40}',os.environ['GITHUB_SHA'])
for key in ('GITHUB_RUN_ID','GITHUB_RUN_ATTEMPT'):assert re.fullmatch('[1-9][0-9]*',os.environ[key])
assert os.environ['DEPLOY_PATH']=='/opt/repoguard'
assert re.fullmatch('[A-Za-z0-9.:-]+',os.environ['DEPLOY_HOST'])
assert re.fullmatch('[A-Za-z0-9._-]+',os.environ['DEPLOY_USER'])
assert re.fullmatch('[A-Za-z0-9_.@+-]+',os.environ['ACR_USER'])
assert re.fullmatch('[a-z0-9][a-z0-9.-]*[.]aliyuncs[.]com',os.environ['ACR_REGISTRY'])
for key in ('ACR_NAMESPACE','ACR_REPOSITORY'):assert re.fullmatch('[a-z0-9][a-z0-9._-]*',os.environ[key])
assert os.environ['LOKI_STAGE']=='/opt/repoguard/.deploy-state/loki-release-input-'+os.environ['GITHUB_RUN_ID']+'-'+os.environ['GITHUB_RUN_ATTEMPT']
PY
[[ "$DEPLOY_PORT" =~ ^[0-9]+$ ]] && (( DEPLOY_PORT >= 1 && DEPLOY_PORT <= 65535 ))
test -n "$ACR_TOKEN"
source_image="$(jq -r '.image.repository + "@" + .image.digest' "$source_manifest")"
source_digest="$(jq -r '.image.digest | sub("^sha256:"; "")' "$source_manifest")"
transfer_image="pragent-loki-transfer:$source_digest"
target_repository="$ACR_REGISTRY/$ACR_NAMESPACE/$ACR_REPOSITORY"
target_image="$target_repository:loki-$GITHUB_RUN_ID-$GITHUB_RUN_ATTEMPT"
transfer_dir="$RUNNER_TEMP/repoguard-loki-images-$GITHUB_RUN_ID-$GITHUB_RUN_ATTEMPT"
remote_dir="/tmp/repoguard-loki-images-$GITHUB_RUN_ID-$GITHUB_RUN_ATTEMPT"
remote_ssh() {
  local limit="$1"; shift
  timeout --signal=TERM --kill-after=10s "$limit" ssh -o BatchMode=yes \
    -o StrictHostKeyChecking=yes -o ConnectTimeout=15 -o ConnectionAttempts=1 \
    -o ServerAliveInterval=15 -o ServerAliveCountMax=40 -i ~/.ssh/deploy_key \
    -p "$DEPLOY_PORT" "$DEPLOY_USER@$DEPLOY_HOST" "$@"
}
remote_scp() {
  timeout --signal=TERM --kill-after=10s 300s scp -q -o BatchMode=yes \
    -o StrictHostKeyChecking=yes -o ConnectTimeout=15 -o ConnectionAttempts=1 \
    -o ServerAliveInterval=15 -o ServerAliveCountMax=20 -i ~/.ssh/deploy_key \
    -P "$DEPLOY_PORT" "$1" "$DEPLOY_USER@$DEPLOY_HOST:$2"
}
# The source job has already scanned this public immutable image without registry credentials.
timeout 600s docker pull "$source_image"
python3 - "$source_manifest" "$source_image" <<'PY'
import importlib.util,pathlib,sys
spec=importlib.util.spec_from_file_location('release_manifest',pathlib.Path('scripts/release-manifest.py'))
m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
source=m.read_json(sys.argv[1]);actual=m.inspection('',sys.argv[2])
assert actual['Id']==source['image']['imageId'] and actual['RepoDigests']==[sys.argv[2]]
PY
docker tag "$source_image" "$transfer_image"
test ! -e "$transfer_dir"
install -d -m 700 "$transfer_dir"
archive="$transfer_dir/image.tar.gz"
timeout 600s docker save "$transfer_image" | gzip -1 > "$archive"
test "$(stat -c %s "$archive")" -le 134217728
split -b 8m -d -a 5 "$archive" "$transfer_dir/part-"
python3 - "$transfer_dir" <<'PY'
import hashlib,json,pathlib,re,sys
root=pathlib.Path(sys.argv[1]);archive=root/'image.tar.gz';parts=sorted(root.glob('part-*'))
assert 1<=len(parts)<=16 and 0<archive.stat().st_size<=128*1024*1024
items=[]
for i,p in enumerate(parts):
    assert p.name=='part-'+str(i).zfill(5) and 0<p.stat().st_size<=8*1024*1024
    items.append({'name':p.name,'bytes':p.stat().st_size,'sha256':hashlib.sha256(p.read_bytes()).hexdigest()})
value={'formatVersion':1,'archiveBytes':archive.stat().st_size,'archiveSha256':hashlib.sha256(archive.read_bytes()).hexdigest(),'chunks':items}
with (root/'transfer-manifest.json').open('x') as f:json.dump(value,f)
PY
remote_ssh 60s "python3 - '$target_repository'" <<'PY'
import json,subprocess,sys
expected=sys.argv[1]
for name in ('repoguard-backend','repoguard-backend-worker','repoguard-frontend'):
    value=json.loads(subprocess.check_output(['docker','inspect',name],timeout=15))[0]
    reference=value['Config']['Image']
    repository=reference.split('@',1)[0] if '@' in reference else reference.rsplit(':',1)[0]
    assert repository==expected
PY
printf '%s' "$ACR_TOKEN" | remote_ssh 120s "docker login '$ACR_REGISTRY' -u '$ACR_USER' --password-stdin"
remote_ssh 60s "python3 - '$remote_dir'" <<'PY'
import os,pathlib,re,sys
p=pathlib.Path(sys.argv[1])
assert re.fullmatch('/tmp/repoguard-loki-images-[1-9][0-9]*-[1-9][0-9]*',str(p))
assert p.parent.resolve()==p.parent and not p.exists() and os.geteuid()==0
p.mkdir(mode=0o700)
PY
upload_chunk() {
  local part="$1"
  local attempt
  for attempt in 1 2 3; do
    if remote_scp "$part" "$remote_dir/$(basename "$part")"; then return 0; fi
    sleep "$attempt"
  done
  return 1
}
export -f remote_scp upload_chunk
export DEPLOY_PORT DEPLOY_USER DEPLOY_HOST remote_dir
find "$transfer_dir" -maxdepth 1 -type f -name 'part-*' -print0 |
  xargs -0 -r -n1 -P4 bash -c 'upload_chunk "$1"' _
remote_scp "$transfer_dir/transfer-manifest.json" "$remote_dir/transfer-manifest.json"
remote_ssh 600s "python3 - '$remote_dir'" <<'PY'
import hashlib,json,pathlib,re,stat,subprocess,sys
root=pathlib.Path(sys.argv[1]);assert root.resolve()==root and root.stat().st_uid==0
path=root/'transfer-manifest.json';assert path.is_file() and not path.is_symlink() and path.stat().st_size<=16384
value=json.loads(path.read_text());chunks=value['chunks']
assert value['formatVersion']==1 and 1<=len(chunks)<=16 and 0<value['archiveBytes']<=128*1024*1024
assert re.fullmatch('[a-f0-9]{64}',value['archiveSha256'])
assert {p.name for p in root.iterdir()}=={'transfer-manifest.json',*(x['name'] for x in chunks)}
whole=hashlib.sha256();total=0
for i,item in enumerate(chunks):
    assert item['name']=='part-'+str(i).zfill(5)
    part=root/item['name'];assert not part.is_symlink() and part.is_file() and part.stat().st_uid==0
    raw=part.read_bytes();assert 0<len(raw)<=8*1024*1024 and len(raw)==item['bytes'] and hashlib.sha256(raw).hexdigest()==item['sha256']
    whole.update(raw);total+=len(raw)
assert total==value['archiveBytes'] and whole.hexdigest()==value['archiveSha256']
process=subprocess.Popen(['docker','load'],stdin=subprocess.PIPE,stdout=sys.stderr,stderr=sys.stderr)
import gzip
try:
    # The exact verified compressed archive is reassembled only in this owned directory.
    joined=root/'verified-image.tar.gz'
    with joined.open('xb') as f:
        for item in chunks:f.write((root/item['name']).read_bytes())
    with gzip.open(joined,'rb') as source:
        expanded=0
        while True:
            data=source.read(1024*1024)
            if not data:break
            expanded+=len(data);assert expanded<=512*1024*1024
            process.stdin.write(data)
    process.stdin.close();assert process.wait(timeout=60)==0
finally:
    if process.poll() is None:process.kill();process.wait(timeout=10)
PY
remote_ssh 600s "
  set -e
  docker tag '$transfer_image' '$target_image'
  test \"\$(docker image inspect --format '{{.Id}}' '$transfer_image')\" = \"\$(docker image inspect --format '{{.Id}}' '$target_image')\"
  timeout 300s docker push '$target_image' >&2
  timeout 120s docker pull '$target_image' >&2
  python3 '$LOKI_STAGE/release-manifest.py' inspect-images --output '$remote_dir/inspections.json' '$target_image'
  cat '$remote_dir/inspections.json'
" > "$RUNNER_TEMP/promoted-loki.json"
python3 scripts/loki-target.py --source "$source_manifest" \
  --inspections "$RUNNER_TEMP/promoted-loki.json" --target-repository "$target_repository" --output "$target_manifest"
# Retain only this bounded, owned transfer directory for diagnosis; no old image, volume or backup is removed.
cp "$transfer_dir/transfer-manifest.json" loki-image-transfer.json
