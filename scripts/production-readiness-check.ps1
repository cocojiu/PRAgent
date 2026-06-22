param(
    [switch] $SkipBackendTests,
    [switch] $IncludeFrontendBuild
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$Root = Split-Path -Parent $PSScriptRoot
$BackendDir = Join-Path $Root "repoguard-backend"
$FrontendDir = Join-Path $Root "repoguard-frontend"
$MigrationDir = Join-Path $BackendDir "src/main/resources/db/migration"

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
        & $FilePath @Arguments
        if ($LASTEXITCODE -ne 0) {
            throw "Command failed with exit code $LASTEXITCODE`: $FilePath $($Arguments -join ' ')"
        }
    } finally {
        Set-Location $previous
    }
}

Invoke-Check "git diff whitespace check" {
    Invoke-CommandChecked -FilePath "git" -Arguments @("diff", "--check") -WorkingDirectory $Root
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

Invoke-Check "secret and token leakage heuristic scan" {
    $trackedFiles = & git -C $Root ls-files
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to list tracked files"
    }

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
            -Arguments @("-Dtest=DashboardControllerTest,ReviewControllerTest,GithubWebhookControllerTest,NotificationIntegrationControllerTest,DashboardMapperSqlContractTest,DashboardSqlVerificationPlanTest,SpringBeanConstructorSelectionTest", "test") `
            -WorkingDirectory $BackendDir
    }
}

if ($IncludeFrontendBuild) {
    Invoke-Check "frontend type check and build" {
        Invoke-CommandChecked -FilePath "npm" -Arguments @("run", "build") -WorkingDirectory $FrontendDir
    }
} else {
    Write-Host "SKIP: frontend type check and build (pass -IncludeFrontendBuild to enable)"
}

Write-Host "Production readiness checks completed."
