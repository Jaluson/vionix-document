# Purpose: verify the M1 backend skeleton and optional local compose profile.
# Dependencies: PowerShell 5+, Maven; Docker CLI is optional for compose config validation.
# Usage: pwsh -File scripts/verify-m1-backend.ps1

[CmdletBinding()]
param(
    [string] $EnvFile = "deploy/local/.env.example",
    [switch] $RunTests,
    [switch] $StrictToolchain
)

$ErrorActionPreference = "Stop"

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
$pomFile = Join-Path $repoRoot "backend/pom.xml"
$applicationFile = Join-Path $repoRoot "backend/src/main/resources/application.yml"
$migrationFile = Join-Path $repoRoot "database/migrations/V20260527_001__init_schema.sql"
$composeFile = Join-Path $repoRoot "deploy/local/docker-compose.yml"
$envFilePath = Join-Path $repoRoot $EnvFile

$failures = New-Object System.Collections.Generic.List[string]
$warnings = New-Object System.Collections.Generic.List[string]

function Add-Failure {
    param([string] $Message)
    $failures.Add($Message) | Out-Null
}

function Add-WarningMessage {
    param([string] $Message)
    $warnings.Add($Message) | Out-Null
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

if (-not (Test-Path $pomFile)) {
    Add-Failure "backend/pom.xml is required."
}
else {
    [xml] $pom = Get-Content -Raw -Encoding UTF8 $pomFile
    $namespace = New-Object System.Xml.XmlNamespaceManager($pom.NameTable)
    $namespace.AddNamespace("m", "http://maven.apache.org/POM/4.0.0")

    $springBootVersion = $pom.SelectSingleNode("/m:project/m:parent/m:version", $namespace).InnerText
    $javaVersion = $pom.SelectSingleNode("/m:project/m:properties/m:java.version", $namespace).InnerText
    if ($springBootVersion -ne "4.0.6") {
        Add-Failure "Spring Boot parent must be 4.0.6."
    }
    if ($javaVersion -ne "25") {
        Add-Failure "Backend must target Java 25."
    }

    $pomContent = Get-Content -Raw -Encoding UTF8 $pomFile
    foreach ($dependency in @(
            "spring-boot-starter-actuator",
            "spring-boot-starter-data-redis",
            "spring-boot-starter-jdbc",
            "spring-boot-starter-validation",
            "spring-boot-starter-webmvc",
            "flyway-core",
            "flyway-mysql",
            "mysql-connector-j"
        )) {
        if ($pomContent -notmatch [regex]::Escape($dependency)) {
            Add-Failure "Missing backend dependency: $dependency"
        }
    }
}

Assert-FileContains $applicationFile 'SPRING_DATASOURCE_URL' "Backend config must read MySQL URL from environment."
Assert-FileContains $applicationFile 'VIONIX_FLYWAY_ENABLED' "Backend config must expose Flyway enablement."
Assert-FileContains $applicationFile 'SPRING_DATA_REDIS_HOST' "Backend config must read Redis host from environment."
Assert-FileContains $applicationFile 'INFLUXDB_URL' "Backend config must read InfluxDB URL from environment."
Assert-FileContains $applicationFile 'MQTT_BROKER' "Backend config must read MQTT broker from environment."
Assert-FileContains $applicationFile 'AUTH_JWT_SECRET' "Backend config must read JWT secret from environment."
Assert-FileNotContains $applicationFile '(?i)(password|secret|token-hash-salt):\s+[A-Za-z0-9+/=._-]{12,}' "Backend config must not contain hard-coded secret values."

foreach ($tablePattern in @(
        'CREATE TABLE IF NOT EXISTS tenant',
        'CREATE TABLE IF NOT EXISTS `user`',
        'CREATE TABLE IF NOT EXISTS `role`',
        'CREATE TABLE IF NOT EXISTS permission',
        'CREATE TABLE IF NOT EXISTS audit_log',
        'CREATE TABLE IF NOT EXISTS device',
        'CREATE TABLE IF NOT EXISTS device_group',
        'CREATE TABLE IF NOT EXISTS `rule`',
        'CREATE TABLE IF NOT EXISTS alert',
        'CREATE TABLE IF NOT EXISTS dashboard'
    )) {
    Assert-FileContains $migrationFile $tablePattern "Migration must include table pattern: $tablePattern"
}
Assert-FileContains $migrationFile 'UNIQUE KEY uk_tenant_device_id \(tenant_id, device_id\)' "Device table must enforce tenant_id + device_id uniqueness."

Assert-FileContains $composeFile 'profiles:\s*\r?\n\s+- m1' "Compose must expose the M1 backend profile."
Assert-FileContains $composeFile 'vionix-backend' "Compose M1 profile must include backend service."
Assert-FileContains $composeFile 'vionix-mysql' "Compose M1 profile must include MySQL service."
Assert-FileContains $composeFile 'vionix-redis' "Compose M1 profile must include Redis service."
Assert-FileContains $composeFile 'AUTH_JWT_SECRET: \$\{AUTH_JWT_SECRET:\?set AUTH_JWT_SECRET\}' "Compose must require JWT secret from env."

$mvn = Get-Command mvn -ErrorAction SilentlyContinue
if ($null -eq $mvn) {
    Add-Failure "Maven was not found."
}
else {
    Push-Location $repoRoot
    try {
        $mvnVersionOutput = & mvn -version 2>&1
        $mvnVersionText = $mvnVersionOutput -join "`n"
        if ($mvnVersionText -notmatch 'Apache Maven 3\.(6\.[3-9]|[7-9]\.|[1-9][0-9]\.)') {
            $message = "Maven 3.6.3+ is required for Spring Boot 4 compilation."
            if ($StrictToolchain) {
                Add-Failure $message
            }
            else {
                Add-WarningMessage $message
            }
        }

        $validateOutput = & mvn -f backend/pom.xml -DskipTests validate 2>&1
        if ($LASTEXITCODE -ne 0) {
            Add-Failure ("mvn validate failed: " + ($validateOutput -join "`n"))
        }

        if ($RunTests) {
            $testOutput = & mvn -f backend/pom.xml test 2>&1
            if ($LASTEXITCODE -ne 0) {
                Add-Failure ("mvn test failed: " + ($testOutput -join "`n"))
            }
        }
    }
    finally {
        Pop-Location
    }
}

$java = Get-Command java -ErrorAction SilentlyContinue
if ($null -eq $java) {
    Add-Failure "Java was not found."
}
else {
    $javaVersionOutput = & cmd /c "java -version 2>&1"
    $javaVersionText = $javaVersionOutput -join "`n"
    if ($javaVersionText -notmatch 'version "25\.') {
        $message = "JDK 25 is required for the documented backend target."
        if ($StrictToolchain) {
            Add-Failure $message
        }
        else {
            Add-WarningMessage $message
        }
    }
}

$docker = Get-Command docker -ErrorAction SilentlyContinue
if ($null -eq $docker) {
    Add-WarningMessage "Docker CLI was not found; skipped M1 compose profile config validation."
}
elseif (-not (Test-Path $envFilePath)) {
    Add-WarningMessage "Environment file '$EnvFile' was not found; skipped M1 compose profile config validation."
}
else {
    Push-Location $repoRoot
    try {
        $composeOutput = & docker compose --env-file $EnvFile -f deploy/local/docker-compose.yml --profile m1 config --quiet 2>&1
        if ($LASTEXITCODE -ne 0) {
            Add-Failure ("docker compose m1 config failed: " + ($composeOutput -join "`n"))
        }
    }
    finally {
        Pop-Location
    }
}

foreach ($warning in $warnings) {
    Write-Warning $warning
}

if ($failures.Count -gt 0) {
    foreach ($failure in $failures) {
        Write-Error $failure
    }
    exit 1
}

Write-Host "M1 backend skeleton checks passed."
