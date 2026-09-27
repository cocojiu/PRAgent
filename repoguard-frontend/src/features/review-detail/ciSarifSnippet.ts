import type { CiSarifSetup } from "@/api/ciSarif";

export type SarifCiPlatform = "github" | "gitlab" | "shell";
export type SarifScanner = "codeql" | "semgrep";

export function buildCiSarifSnippet(setup: CiSarifSetup, baseUrl: string, platform: SarifCiPlatform,
  scanner: SarifScanner): string {
  const url = new URL(baseUrl);
  if (url.protocol !== "https:" && !(url.protocol === "http:" && ["localhost", "127.0.0.1"].includes(url.hostname))) {
    throw new Error("上传地址需使用 HTTPS，本机调试可使用 HTTP。");
  }
  if (url.username || url.password || url.search || url.hash || !["", "/"].includes(url.pathname)) {
    throw new Error("请输入站点根地址，不包含账号、路径或查询参数。");
  }
  if (!Number.isSafeInteger(setup.taskId) || setup.taskId < 1 || !setup.attemptId || !/^[a-f0-9]{7,64}$/i.test(setup.commitSha ?? "")) {
    throw new Error("当前任务缺少可用的审查批次或 commit，请刷新任务后重试。");
  }
  const endpoint = `${url.origin}/api/v1/scanners/sarif/ci/tasks/${setup.taskId}/upload`;
  const script = [
    "import datetime, json, os, pathlib, re, subprocess, sys, urllib.error, urllib.request",
    "token = os.environ.get('REPOGUARD_CI_CREDENTIAL', '')",
    "if not token: sys.exit('Missing REPOGUARD_CI_CREDENTIAL; generate a fresh short-lived credential.')",
    `expected_commit = ${JSON.stringify(setup.commitSha)}`,
    "head = subprocess.check_output(['git', 'rev-parse', 'HEAD'], text=True).strip()",
    "if head != expected_commit: sys.exit('Checkout the bound PR head commit before uploading; do not use the merge commit.')",
    `path = pathlib.Path(${JSON.stringify(`${scanner}.sarif`)})`,
    `if path.stat().st_size > ${setup.maxSarifBytes}: sys.exit('SARIF exceeds the JSON size limit; reduce the scanner report.')`,
    "payload = path.read_bytes()",
    "document = json.loads(payload)",
    "if document.get('version') != '2.1.0' or len(document.get('runs', [])) != 1: sys.exit('Supply one SARIF 2.1.0 run per file.')",
    "driver = document['runs'][0]['tool']['driver']",
    "def metadata(value, limit): return ''.join(c for c in str(value).strip() if ord(c) >= 32 and not 127 <= ord(c) <= 159)[:limit]",
    "tool = metadata(driver.get('name', ''), 128)",
    "version = metadata(driver.get('version', ''), 64)",
    "if not tool: sys.exit('SARIF tool.driver.name is required.')",
    "run_id = os.environ.get('SARIF_SCAN_RUN', '')",
    "if not re.fullmatch(r'[A-Za-z0-9._:-]{1,128}', run_id): sys.exit('Set a stable SARIF_SCAN_RUN for this scan; retain it when retrying.')",
    "completed = datetime.datetime.fromtimestamp(path.stat().st_mtime, datetime.timezone.utc).isoformat()",
    "headers = {'Content-Type': 'application/json', 'X-RepoGuard-CI-Credential': token,",
    "           'X-RepoGuard-CI-Tool': tool, 'X-RepoGuard-CI-Tool-Version': version,",
    "           'X-RepoGuard-CI-Scan-Run': run_id, 'X-RepoGuard-CI-Commit-SHA': head,",
    "           'X-RepoGuard-CI-Completed-At': completed}",
    `request = urllib.request.Request(${JSON.stringify(endpoint)}, data=payload, headers=headers, method='POST')`,
    "class NoRedirect(urllib.request.HTTPRedirectHandler):",
    "    def redirect_request(self, req, fp, code, msg, headers, newurl): return None",
    "opener = urllib.request.build_opener(NoRedirect())",
    "try:",
    "    with opener.open(request, timeout=30) as response:",
    "        result = json.load(response)",
    "    if not result.get('success'): sys.exit('Upload rejected; inspect the response code in RepoGuard.')",
    "    data = result['data']",
    "    print('SARIF uploaded:', data['status'], 'imported=', data['imported'], 'skipped=', data['skipped'])",
    "except urllib.error.HTTPError as error:",
    "    print('Upload HTTP', error.code, file=sys.stderr)",
    "    try: print(json.load(error).get('message', 'Upload rejected'), file=sys.stderr)",
    "    except (ValueError, AttributeError): pass",
    "    sys.exit(1)",
    "except (urllib.error.URLError, TimeoutError):",
    "    sys.exit('Upload interrupted; retry the same report and scan ID before the credential expires.')"
  ];
  const command = ["python3 - <<'REPOGUARD_SARIF_PY'", ...script, "REPOGUARD_SARIF_PY"];
  if (platform === "github") {
    return ["# Add after the existing scanner step, with the bound PR head checked out.",
      "- name: Upload SARIF to RepoGuard", "  shell: bash", "  env:",
      "    REPOGUARD_CI_CREDENTIAL: ${{ secrets.REPOGUARD_CI_CREDENTIAL }}",
      "    SARIF_SCAN_RUN: github-${{ github.run_id }}-${{ github.run_attempt }}-${{ github.job }}",
      "  run: |", ...command.map(line => `    ${line}`)].join("\n");
  }
  if (platform === "gitlab") {
    return ["# Add this job after the existing scanner; download its SARIF artifact first.",
      "# Configure REPOGUARD_CI_CREDENTIAL as a masked CI/CD variable.",
      "repoguard_sarif:", "  variables:", "    SARIF_SCAN_RUN: gitlab-$CI_PIPELINE_ID-$CI_JOB_ID",
      "  script:", "    - |", ...command.map(line => `      ${line}`)].join("\n");
  }
  return ["# Requires Python 3 and Git. Run in the checked-out repository after scanning.",
    "# Inject REPOGUARD_CI_CREDENTIAL through your secret manager; do not paste it into this file.",
    "export SARIF_SCAN_RUN=scan-unique-id", ...command].join("\n");
}
