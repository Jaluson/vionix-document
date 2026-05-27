# Purpose: verify the M0 infrastructure baseline without requiring live services.
# Dependencies: PowerShell 5+; Docker Compose is optional for compose config validation.
# Usage: pwsh -File scripts/verify-m0-infrastructure.ps1

[CmdletBinding()]
param(
    [string] $EnvFile = "deploy/local/.env.example",
    [switch] $SkipDocker
)

$ErrorActionPreference = "Stop"

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
$composeFile = Join-Path $repoRoot "deploy/local/docker-compose.yml"
$telegrafFile = Join-Path $repoRoot "deploy/local/telegraf/telegraf.conf"
$influxInitFile = Join-Path $repoRoot "deploy/local/influxdb/init.sh"
$envFilePath = Join-Path $repoRoot $EnvFile

$failures = New-Object System.Collections.Generic.List[string]
$oldToken = ("my-super" + "-secret-token")
$oldPassword = ("admin" + "123456")
$latestTelegrafTag = ("telegraf:" + "latest")
$oldSecretPattern = [regex]::Escape($oldToken) + "|" + [regex]::Escape($oldPassword)
$oldComposePattern = $oldSecretPattern + "|" + [regex]::Escape($latestTelegrafTag)

function Add-Failure {
    param([string] $Message)
    $failures.Add($Message) | Out-Null
}

function Assert-FileContains {
    param(
        [string] $Path,
        [string] $Pattern,
        [string] $Message
    )

    $content = Get-Content -Raw -Encoding UTF8 $Path
    if ($content -notmatch $Pattern) {
        Add-Failure $Message
    }
}

function Assert-FileNotContains {
    param(
        [string] $Path,
        [string] $Pattern,
        [string] $Message
    )

    $content = Get-Content -Raw -Encoding UTF8 $Path
    if ($content -match $Pattern) {
        Add-Failure $Message
    }
}

Assert-FileContains $composeFile 'VIONIX_INFLUXDB_ADMIN_TOKEN' "Compose must use environment interpolation for the InfluxDB admin token."
Assert-FileContains $composeFile 'telegraf:1\.38' "Telegraf image must be version-pinned for repeatable local runs."
Assert-FileNotContains $composeFile $oldComposePattern "Compose must not contain old plaintext credentials or latest tags."

Assert-FileContains $telegrafFile 'vionix/\+/\+/metrics' "Telegraf must subscribe to production MQTT topic vionix/{tenant_id}/{device_id}/metrics."
Assert-FileContains $telegrafFile 'tags = "_/tenant_id/device_id/_"' "Telegraf must parse tenant_id and device_id from the production topic."
Assert-FileContains $telegrafFile 'tags = "_/device_id"' "Telegraf must parse device_id from the development sensors topic."
Assert-FileContains $telegrafFile 'topic_tag = ""' "Telegraf must not write the full MQTT topic as a high-cardinality tag."
Assert-FileContains $telegrafFile '\[processors\.defaults\.tags\]' "Telegraf must set default tags for development-compatible messages."
Assert-FileNotContains $telegrafFile $oldSecretPattern "Telegraf config must not contain plaintext credentials."

Assert-FileContains $influxInitFile 'downsample-raw-to-min' "InfluxDB init must create the raw-to-min downsampling task."
Assert-FileContains $influxInitFile 'downsample-min-to-hour' "InfluxDB init must create the min-to-hour downsampling task."
Assert-FileContains $influxInitFile 'downsample-hour-to-day' "InfluxDB init must create the hour-to-day downsampling task."
Assert-FileContains $influxInitFile 'strings\.trimSuffix' "InfluxDB tasks must trim aggregate suffixes before writing the next level."
Assert-FileContains $influxInitFile 'join\(' "InfluxDB tasks must join *_sum and *_count streams to compute weighted means."
Assert-FileContains $influxInitFile 'float\(v: r\._value_count\)' "InfluxDB weighted means must divide by aggregated count."
Assert-FileContains $influxInitFile 'org: "\$\{ORG\}"' "InfluxDB task output org must use the configured local org."
Assert-FileNotContains $influxInitFile ("_mean_mean|_sum_sum|_max_max|_min_min|_count_count|" + $oldSecretPattern) "InfluxDB init must not contain second-level aggregate suffixes or plaintext credentials."

if (-not $SkipDocker) {
    $docker = Get-Command docker -ErrorAction SilentlyContinue
    if ($null -eq $docker) {
        Write-Warning "Docker CLI was not found; skipped compose config validation."
    }
    elseif (-not (Test-Path $envFilePath)) {
        Write-Warning "Environment file '$EnvFile' was not found; skipped compose config validation."
    }
    else {
        Push-Location $repoRoot
        try {
            $composeArgs = @("compose", "--env-file", $EnvFile, "-f", "deploy/local/docker-compose.yml", "config", "--quiet")
            $output = & docker @composeArgs 2>&1
            if ($LASTEXITCODE -ne 0) {
                Add-Failure ("docker compose config failed: " + ($output -join "`n"))
            }
        }
        finally {
            Pop-Location
        }
    }
}

if ($failures.Count -gt 0) {
    foreach ($failure in $failures) {
        Write-Error $failure
    }
    exit 1
}

Write-Host "M0 infrastructure checks passed."
