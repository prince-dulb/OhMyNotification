[CmdletBinding()]
<#
.SYNOPSIS
Creates a private, commit-linked Phase 0 evidence record and sentinel file.

.EXAMPLE
.\tools\new-phase0-evidence.ps1 -SpikeId SP-01

.EXAMPLE
.\tools\new-phase0-evidence.ps1 -SpikeId SP-04 -DatasetId burst-100-v1 -DatasetSha256 <sha256> -WithoutDevice
#>
param(
    [Parameter(Mandatory)]
    [ValidatePattern('^SP-0[1-9]$')]
    [string]$SpikeId,

    [ValidatePattern('^[a-z0-9][a-z0-9-]*$')]
    [string]$DatasetId = 'none',

    [ValidatePattern('^$|^[0-9a-f]{64}$')]
    [string]$DatasetSha256 = '',

    [string]$ArtifactPath,

    [string]$Serial,

    [switch]$WithoutDevice
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

trap {
    Write-Output "FAIL $($_.Exception.Message)"
    exit 1
}

$projectRoot = Split-Path -Parent $PSScriptRoot

function Get-Sha256Text {
    param([Parameter(Mandatory)][string]$Text)

    $bytes = [System.Text.Encoding]::UTF8.GetBytes($Text)
    $digest = [System.Security.Cryptography.SHA256]::Create()
    try {
        return ($digest.ComputeHash($bytes) | ForEach-Object { $_.ToString('x2') }) -join ''
    }
    finally {
        $digest.Dispose()
    }
}

function Resolve-AdbPath {
    $sdkCandidates = [System.Collections.Generic.List[string]]::new()
    foreach ($environmentName in @('ANDROID_SDK_ROOT', 'ANDROID_HOME')) {
        $candidate = [Environment]::GetEnvironmentVariable($environmentName, 'Process')
        if (-not [string]::IsNullOrWhiteSpace($candidate)) {
            $sdkCandidates.Add($candidate)
        }
    }

    $localProperties = Join-Path $projectRoot 'local.properties'
    if (Test-Path -LiteralPath $localProperties -PathType Leaf) {
        $sdkLine = Get-Content -LiteralPath $localProperties -Encoding UTF8 |
            Where-Object { $_.StartsWith('sdk.dir=') } |
            Select-Object -First 1
        if ($null -ne $sdkLine) {
            $encodedPath = $sdkLine.Substring('sdk.dir='.Length).Trim()
            $decodedPath = $encodedPath.Replace('\:', ':').Replace('\\', '\')
            if (-not [string]::IsNullOrWhiteSpace($decodedPath)) {
                $sdkCandidates.Add($decodedPath)
            }
        }
    }

    foreach ($sdkPath in $sdkCandidates) {
        $candidate = Join-Path $sdkPath 'platform-tools\adb.exe'
        if (Test-Path -LiteralPath $candidate -PathType Leaf) {
            return $candidate
        }
    }

    $pathCommand = Get-Command adb.exe -ErrorAction SilentlyContinue
    if ($null -ne $pathCommand) {
        return $pathCommand.Source
    }
    throw 'adb.exe was not found in the process environment or local.properties.'
}

function Invoke-Git {
    param([Parameter(Mandatory)][string[]]$Arguments)

    $output = & git -C $projectRoot @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "git command failed: $($Arguments[0])"
    }
    return @($output | ForEach-Object { $_.ToString() })
}

function Invoke-Adb {
    param(
        [Parameter(Mandatory)][string]$AdbPath,
        [Parameter(Mandatory)][string]$DeviceSerial,
        [Parameter(Mandatory)][string[]]$Arguments
    )

    $output = & $AdbPath -s $DeviceSerial @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "adb command failed: $($Arguments[0])"
    }
    return @($output | ForEach-Object { $_.ToString() })
}

$commit = (Invoke-Git -Arguments @('rev-parse', 'HEAD') | Select-Object -First 1).Trim()
$workingTreeDirty = @(Invoke-Git -Arguments @('status', '--porcelain')).Count -gt 0
$now = [DateTimeOffset]::Now
$timestamp = $now.ToString("yyyyMMdd'T'HHmmsszzz").Replace(':', '')
$runId = "$SpikeId-$timestamp"

$artifactName = $null
$artifactSha256 = $null
if (-not [string]::IsNullOrWhiteSpace($ArtifactPath)) {
    if (-not (Test-Path -LiteralPath $ArtifactPath -PathType Leaf)) {
        throw 'The requested artifact does not exist.'
    }
    $artifactName = [System.IO.Path]::GetFileName($ArtifactPath)
    $artifactSha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $ArtifactPath).Hash.ToLowerInvariant()
}

$device = [ordered]@{
    captureStatus = 'NOT_CAPTURED'
    serialSha256 = $null
    model = $null
    sdk = $null
    abiList = $null
}

if (-not $WithoutDevice) {
    $adbPath = Resolve-AdbPath
    $rawDevices = & $adbPath devices 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw 'adb devices failed.'
    }
    $availableDevices = @(
        $rawDevices |
            ForEach-Object { $_.ToString() } |
            Where-Object { $_ -match '^(\S+)\s+device$' } |
            ForEach-Object { $Matches[1] }
    )
    if ([string]::IsNullOrWhiteSpace($Serial)) {
        if ($availableDevices.Count -ne 1) {
            throw "Expected exactly one authorized Android device; found $($availableDevices.Count)."
        }
        $Serial = $availableDevices[0]
    }
    elseif ($availableDevices -notcontains $Serial) {
        throw 'The requested Android device is not connected and authorized.'
    }

    $device.captureStatus = 'CAPTURED'
    $device.serialSha256 = Get-Sha256Text -Text $Serial
    $device.model = (Invoke-Adb -AdbPath $adbPath -DeviceSerial $Serial -Arguments @('shell', 'getprop', 'ro.product.model') | Select-Object -First 1).Trim()
    $device.sdk = (Invoke-Adb -AdbPath $adbPath -DeviceSerial $Serial -Arguments @('shell', 'getprop', 'ro.build.version.sdk') | Select-Object -First 1).Trim()
    $device.abiList = (Invoke-Adb -AdbPath $adbPath -DeviceSerial $Serial -Arguments @('shell', 'getprop', 'ro.product.cpu.abilist') | Select-Object -First 1).Trim()
}

$evidence = [ordered]@{
    schemaVersion = 1
    spikeId = $SpikeId
    runId = $runId
    createdAt = $now.ToString('o')
    source = [ordered]@{
        commit = $commit
        workingTreeDirty = $workingTreeDirty
    }
    build = [ordered]@{
        variant = 'NOT_RECORDED'
        artifactName = $artifactName
        artifactSha256 = $artifactSha256
    }
    device = $device
    dataset = [ordered]@{
        id = $DatasetId
        sha256 = if ([string]::IsNullOrWhiteSpace($DatasetSha256)) { $null } else { $DatasetSha256 }
    }
    status = 'NOT_RUN'
    preconditions = @()
    steps = @()
    observations = @()
    failures = @()
    conclusion = [ordered]@{
        decision = 'NOT_EVALUATED'
        confidence = 'NOT_EVALUATED'
        limitations = @()
        openQuestions = @()
    }
    publication = [ordered]@{
        containsPrivateData = $true
        publicSummaryStatus = 'NOT_PREPARED'
    }
}

$evidenceDirectory = Join-Path $projectRoot '.local-evidence\phase-0'
$null = New-Item -ItemType Directory -Path $evidenceDirectory -Force
$evidenceName = "$runId.json"
$sentinelName = "$runId.sentinels.txt"
$evidencePath = Join-Path $evidenceDirectory $evidenceName
$sentinelPath = Join-Path $evidenceDirectory $sentinelName

[System.IO.File]::WriteAllText(
    $evidencePath,
    ($evidence | ConvertTo-Json -Depth 8) + [Environment]::NewLine,
    [System.Text.UTF8Encoding]::new($false)
)
[System.IO.File]::WriteAllText(
    $sentinelPath,
    "# One exact private value per line. Never copy this file into Git.`n# Add notification text, target IDs, action tokens, raw serials, and private paths used by this run.`n",
    [System.Text.UTF8Encoding]::new($false)
)

Write-Output "PASS evidence=$evidenceName sentinels=$sentinelName commit=$($commit.Substring(0, 12)) dirty=$workingTreeDirty"
