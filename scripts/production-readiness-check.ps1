param(
    [ValidateSet("full", "quick")]
    [string] $Mode = "full",
    [switch] $SkipBackendTests,
    [switch] $IncludeFrontendBuild
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$Root = Split-Path -Parent $PSScriptRoot
$BackendDir = Join-Path $Root "repoguard-backend"
$FrontendDir = Join-Path $Root "repoguard-frontend"
$MigrationDir = Join-Path $BackendDir "src/main/resources/db/migration"

if ($IncludeFrontendBuild) {
    $Mode = "full"
}

$RunFrontendGate = $Mode -eq "full"
Write-Host "Production readiness mode: $Mode"

function Invoke-Check {
    param(
        [string] $Name,
        [scriptblock] $Body
    )

    Write-Host "==> $Name"
    & $Body
    Write-Host "OK: $Name"
}

function Invoke-CommandChecked {
    param(
        [string] $FilePath,
        [string[]] $Arguments,
        [string] $WorkingDirectory
    )

    $previous = Get-Location
    try {
        Set-Location $WorkingDirectory
        $ResolvedCommand = Resolve-ToolCommand -FilePath $FilePath
        $ResolvedArguments = @($ResolvedCommand.Arguments) + $Arguments
        & $ResolvedCommand.FilePath @ResolvedArguments
        if ($LASTEXITCODE -ne 0) {
            throw "Command failed with exit code $LASTEXITCODE`: $($ResolvedCommand.FilePath) $($ResolvedArguments -join ' ')"
        }
    } finally {
        Set-Location $previous
    }
}

function Resolve-ToolCommand {
    param(
        [string] $FilePath
    )

    $command = Get-Command $FilePath -ErrorAction SilentlyContinue
    if ($command) {
        return [pscustomobject]@{
            FilePath = $command.Source
            Arguments = @()
        }
    }

    if ($FilePath -eq "npm") {
        $node = Get-Command "node" -ErrorAction SilentlyContinue
        $nodePath = if ($node) { $node.Source } else { Join-Path $env:APPDATA "npm/node_modules/node/bin/node.exe" }
        $npmCli = Join-Path $env:APPDATA "npm/node_modules/npm/bin/npm-cli.js"
        if ((Test-Path -LiteralPath $nodePath) -and (Test-Path -LiteralPath $npmCli)) {
            return [pscustomobject]@{
                FilePath = $nodePath
                Arguments = @($npmCli)
            }
        }
    }

    return [pscustomobject]@{
        FilePath = $FilePath
        Arguments = @()
    }
}

function Get-TrackedRepositoryFiles {
    $trackedFiles = & git -c core.quotePath=false -C $Root ls-files
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to list tracked files"
    }
    return $trackedFiles | ForEach-Object { $_ -replace '\\', '/' }
}

function Test-TestOrTemporaryScriptPath {
    param(
        [string] $Path
    )

    $extension = [System.IO.Path]::GetExtension($Path).ToLowerInvariant()
    $scriptExtensions = @(".sh", ".ps1", ".bat", ".cmd")
    if ($scriptExtensions -notcontains $extension) {
        return $false
    }

    $normalizedPath = ($Path -replace '\\', '/').ToLowerInvariant()
    $fileName = [System.IO.Path]::GetFileNameWithoutExtension($normalizedPath)
    return $normalizedPath -match '(^|/)(test|tests|__tests__|tmp|temp|scratch)(/|$)' `
        -or $fileName -match '(^|[-_.])(test|spec|tmp|temp|scratch|debug|local)([-_.]|$)'
}

Invoke-Check "git diff whitespace check" {
    Invoke-CommandChecked -FilePath "git" -Arguments @("diff", "--check") -WorkingDirectory $Root
}

Invoke-Check "repository governance tracked file guard" {
    $allowedMarkdown = @("README.md")
    $invalidMarkdown = @()
    $invalidScripts = @()

    foreach ($relativePath in Get-TrackedRepositoryFiles) {
        $extension = [System.IO.Path]::GetExtension($relativePath).ToLowerInvariant()
        if ($extension -eq ".md" -and $relativePath -notin $allowedMarkdown) {
            $invalidMarkdown += $relativePath
        }
        if (Test-TestOrTemporaryScriptPath -Path $relativePath) {
            $invalidScripts += $relativePath
        }
    }

    if ($invalidMarkdown.Count -gt 0) {
        throw "Only approved root markdown files may be tracked. Invalid markdown files:`n$($invalidMarkdown -join "`n")"
    }
    if ($invalidScripts.Count -gt 0) {
        throw "Test or temporary scripts must not be tracked. Invalid script files:`n$($invalidScripts -join "`n")"
    }
}

Invoke-Check "production deployment shell syntax" {
    $shCommand = Get-Command "sh" -ErrorAction SilentlyContinue
    $shPath = if ($shCommand) { $shCommand.Source } else { $null }
    if (-not $shPath -and $env:OS -eq "Windows_NT") {
        $windowsCandidates = @(
            "C:\Program Files\Git\bin\sh.exe",
            "C:\Program Files\Git\usr\bin\sh.exe"
        )
        $shPath = $windowsCandidates |
            Where-Object { Test-Path -LiteralPath $_ } |
            Select-Object -First 1
    }
    if (-not $shPath) {
        throw "A POSIX sh executable is required to validate scripts/deploy-prod.sh"
    }

    Invoke-CommandChecked `
        -FilePath $shPath `
        -Arguments @("-n", "scripts/deploy-prod.sh") `
        -WorkingDirectory $Root
}

Invoke-Check "Flyway migration naming and duplicate version check" {
    $migrations = Get-ChildItem -LiteralPath $MigrationDir -Filter "*.sql" | Sort-Object Name
    if ($migrations.Count -eq 0) {
        throw "No Flyway migration files found under $MigrationDir"
    }

    $versions = @{}
    foreach ($migration in $migrations) {
        if ($migration.Name -notmatch '^V(?<version>\d+)__[\w.-]+\.sql$') {
            throw "Invalid Flyway migration name: $($migration.Name)"
        }

        $version = $Matches.version
        if ($versions.ContainsKey($version)) {
            throw "Duplicate Flyway migration version V$version`: $($versions[$version]) and $($migration.Name)"
        }
        $versions[$version] = $migration.Name
    }
}

Invoke-Check "Schema version guard expectation matches migration chain" {
    $migrations = Get-ChildItem -LiteralPath $MigrationDir -Filter "*.sql" | Sort-Object Name
    if ($migrations.Count -eq 0) {
        throw "No Flyway migration files found under $MigrationDir"
    }

    $highestVersion = 0
    foreach ($migration in $migrations) {
        if ($migration.Name -match '^V(?<version>\d+)__') {
            $version = [int]$Matches.version
            if ($version -gt $highestVersion) {
                $highestVersion = $version
            }
        }
    }

    $applicationYml = Join-Path $BackendDir "src/main/resources/application.yml"
    if (-not (Test-Path -LiteralPath $applicationYml)) {
        throw "application.yml not found at $applicationYml"
    }

    $content = Get-Content -LiteralPath $applicationYml -Raw -Encoding UTF8
    if ($content -notmatch 'expected-version:\s*\$\{REPOGUARD_SCHEMA_EXPECTED_VERSION:(?<expected>\d+)\}') {
        throw "Could not read repoguard.schema.expected-version from application.yml. SchemaVersionGuard relies on this default; keep the '`${REPOGUARD_SCHEMA_EXPECTED_VERSION:<n>}' form."
    }

    $expectedVersion = [int]$Matches.expected
    if ($expectedVersion -ne $highestVersion) {
        throw "repoguard.schema.expected-version is $expectedVersion but the highest migration is V$highestVersion. Update the default in application.yml so non-owner roles reject a stale schema."
    }
}

Invoke-Check "Flyway migration demo data guard" {
    $allowedDemoMigrations = @(
        "V2__seed_demo_data.sql",
        "V24__seed_llm_quality_demo_data.sql",
        "V25__remove_legacy_v2_demo_data.sql",
        "V35__remove_llm_quality_demo_data.sql",
        "V36__purge_demo_review_data.sql"
    )
    $demoMarkers = @(
        "repo-guard-demo",
        "https://github.com/monorepo/",
        "src/main/java/com/demo/",
        "demo901a",
        "demo902b",
        "demo903c",
        "demo904d"
    )

    $migrations = Get-ChildItem -LiteralPath $MigrationDir -Filter "*.sql" | Sort-Object Name
    foreach ($migration in $migrations) {
        $content = Get-Content -LiteralPath $migration.FullName -Raw -Encoding UTF8

        if ($migration.Name -eq "V36__purge_demo_review_data.sql" -and $content -match '(?im)^\s*or\s+(?:\w+\.)?id\s+in\s*\(') {
            throw "Unsafe fixed-id OR filter found in $($migration.Name). Demo purge migrations must match demo-owned rows by organization, URL, or commit markers before deleting."
        }

        if ($allowedDemoMigrations -contains $migration.Name) {
            continue
        }

        if ($migration.Name -match '(?i)seed.*demo|demo.*seed') {
            throw "Demo seed migration is not allowed in the main Flyway chain: $($migration.Name). Put demo data under repoguard-backend/src/main/resources/db/demo instead."
        }

        foreach ($marker in $demoMarkers) {
            if ($content.Contains($marker)) {
                throw "Demo data marker '$marker' found in main Flyway migration $($migration.Name). Put demo data under repoguard-backend/src/main/resources/db/demo instead."
            }
        }
    }
}

Invoke-Check "secret and token leakage heuristic scan" {
    $trackedFiles = Get-TrackedRepositoryFiles

    $scannedExtensions = @(
        ".java", ".ts", ".vue", ".js", ".json", ".yml", ".yaml", ".properties",
        ".sql", ".md", ".sh", ".ps1"
    )
    $excludedPaths = @(
        "repoguard-frontend/package-lock.json",
        "repoguard-backend/src/test/resources/review-quality/evaluation-cases.json"
    )
    $secretPattern = '(?i)(?<![-@\w])(api[_-]?key|access[_-]?token|secret|password|private[_-]?key)\s*[:=]\s*["'']?(?!\$\{|<|your-|xxx|example|demo|test|masked|redacted|\*{3,}|changeme|null|true|false|default_|[a-z_][a-z0-9_]*\.|[a-z_][a-z0-9_]*\(|credentials\.|process\.|env\.|__env\.)[A-Za-z0-9_./+=-]{16,}'

    foreach ($relativePath in $trackedFiles) {
        $normalizedPath = $relativePath -replace '\\', '/'
        if ($excludedPaths -contains $normalizedPath) {
            continue
        }

        $extension = ""
        if ($normalizedPath -match '(\.[^./]+)$') {
            $extension = $Matches[1]
        }
        if ($scannedExtensions -notcontains $extension) {
            continue
        }

        $absolutePath = Join-Path $Root $normalizedPath
        if (-not (Test-Path -LiteralPath $absolutePath -PathType Leaf)) {
            continue
        }
        $matches = Select-String -LiteralPath $absolutePath -Pattern $secretPattern -AllMatches
        if ($matches) {
            $locations = $matches | ForEach-Object { "$($_.Path):$($_.LineNumber)" }
            throw "Potential secret/token leakage found:`n$($locations -join "`n")"
        }
    }
}

if (-not $SkipBackendTests) {
    Invoke-Check "backend production readiness test slice" {
        Invoke-CommandChecked `
            -FilePath "mvn" `
            -Arguments @("-Dtest=ApiContractTest,ControllerAuthorizationContractTest,DashboardControllerTest,ReviewControllerTest,GithubWebhookControllerTest,NotificationIntegrationControllerTest,DashboardMapperSqlContractTest,DashboardSqlVerificationPlanTest,SpringBeanConstructorSelectionTest", "test") `
            -WorkingDirectory $BackendDir
    }
}

if ($RunFrontendGate) {
    Invoke-Check "frontend quality gate" {
        Invoke-CommandChecked -FilePath "npm" -Arguments @("run", "quality") -WorkingDirectory $FrontendDir
    }

    Invoke-Check "frontend production build" {
        Invoke-CommandChecked -FilePath "npm" -Arguments @("run", "build") -WorkingDirectory $FrontendDir
    }
} else {
    Write-Host "SKIP: frontend quality gate and build (quick mode; pass -Mode full for production release readiness)"
}

Write-Host "Production readiness checks completed."
