[CmdletBinding()]
<#
.SYNOPSIS
Captures a read-only SP-05 listener/process checkpoint into private local evidence.

.EXAMPLE
.\tools\capture-phase0-listener-state.ps1 -Checkpoint baseline

.EXAMPLE
.\tools\capture-phase0-listener-state.ps1 -Checkpoint after-lock -RunId lock-8h-01

.EXAMPLE
.\tools\capture-phase0-listener-state.ps1 -SelfTest
#>
param(
    [ValidatePattern('^[a-z0-9][a-z0-9-]{0,63}$')]
    [string]$Checkpoint = 'baseline',

    [ValidatePattern('^[a-z0-9][a-z0-9-]{0,63}$')]
    [string]$RunId,

    [string]$Serial,

    [switch]$SelfTest
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

trap {
    Write-Output "FAIL $($_.Exception.Message)"
    exit 1
}

$projectRoot = Split-Path -Parent $PSScriptRoot
$packageName = 'io.github.prince_dulb.ohmynotification'
$listenerComponent = "$packageName/io.github.prince_dulb.ohmynotification.phase0.PhaseZeroNotificationListener"

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

function Test-ColonListContains {
    param(
        [AllowEmptyString()][string]$Value,
        [Parameter(Mandatory)][string]$Expected
    )

    return @($Value -split ':' | Where-Object { $_ -eq $Expected }).Count -gt 0
}

function Get-AppOpMode {
    param([Parameter(Mandatory)][AllowEmptyCollection()][AllowEmptyString()][string[]]$Lines)

    foreach ($line in $Lines) {
        if ($line -match ':\s*(allow|ignore|deny|default|foreground)\b') {
            return $Matches[1].ToUpperInvariant()
        }
    }
    return 'UNKNOWN'
}

function Get-UserStoppedState {
    param([Parameter(Mandatory)][AllowEmptyCollection()][AllowEmptyString()][string[]]$Lines)

    foreach ($line in $Lines) {
        if ($line -match '^\s*User 0:.*\bstopped=(true|false)\b') {
            return [bool]::Parse($Matches[1])
        }
    }
    return $null
}

function Get-Wakefulness {
    param([Parameter(Mandatory)][AllowEmptyCollection()][AllowEmptyString()][string[]]$Lines)

    foreach ($line in $Lines) {
        if ($line -match '^\s*mWakefulness(?:Raw)?=([A-Za-z_]+)') {
            return $Matches[1].ToUpperInvariant()
        }
    }
    return 'UNKNOWN'
}

function Get-FirstProcessId {
    param([Parameter(Mandatory)][AllowEmptyCollection()][AllowEmptyString()][string[]]$Lines)

    $tokens = @(
        $Lines |
            ForEach-Object { $_ -split '\s+' } |
            Where-Object { $_ -match '^\d+$' }
    )
    if ($tokens.Count -eq 0) {
        return $null
    }
    return [int]$tokens[0]
}

function Assert-Equal {
    param(
        [Parameter(Mandatory)]$Expected,
        [Parameter(Mandatory)]$Actual,
        [Parameter(Mandatory)][string]$Name
    )

    if ($Expected -ne $Actual) {
        throw "Self-test '$Name' failed: expected '$Expected', actual '$Actual'."
    }
}

if ($SelfTest) {
    Assert-Equal -Expected $true -Actual (Test-ColonListContains -Value 'a/A:b/B' -Expected 'b/B') -Name 'colon-present'
    Assert-Equal -Expected $false -Actual (Test-ColonListContains -Value 'a/A:b/B' -Expected 'b/C') -Name 'colon-absent'
    Assert-Equal -Expected 'IGNORE' -Actual (Get-AppOpMode -Lines @('Uid mode: POST_NOTIFICATION: ignore')) -Name 'app-op'
    Assert-Equal -Expected $false -Actual (Get-UserStoppedState -Lines @('  User 0: installed=true stopped=false enabled=0')) -Name 'not-stopped'
    Assert-Equal -Expected 'AWAKE' -Actual (Get-Wakefulness -Lines @('  mWakefulness=Awake')) -Name 'wakefulness'
    Assert-Equal -Expected 123 -Actual (Get-FirstProcessId -Lines @('123 456')) -Name 'pid'
    Write-Output 'PASS selfTest=true assertions=6'
    exit 0
}

function Resolve-AdbPath {
    $localProperties = Join-Path $projectRoot 'local.properties'
    if (Test-Path -LiteralPath $localProperties -PathType Leaf) {
        $sdkLine = Get-Content -LiteralPath $localProperties -Encoding UTF8 |
            Where-Object { $_.StartsWith('sdk.dir=') } |
            Select-Object -First 1
        if ($null -ne $sdkLine) {
            $encodedPath = $sdkLine.Substring('sdk.dir='.Length).Trim()
            $sdkPath = $encodedPath.Replace('\:', ':').Replace('\\', '\')
            $candidate = Join-Path $sdkPath 'platform-tools\adb.exe'
            if (Test-Path -LiteralPath $candidate -PathType Leaf) {
                return $candidate
            }
        }
    }
    $command = Get-Command adb.exe -ErrorAction SilentlyContinue
    if ($null -ne $command) {
        return $command.Source
    }
    throw 'adb.exe was not found.'
}

function Invoke-Adb {
    param([Parameter(Mandatory)][string[]]$Arguments)

    $output = & $adbPath -s $Serial @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "adb command failed: $($Arguments -join ' ')"
    }
    return @($output | ForEach-Object { $_.ToString() })
}

function Invoke-Git {
    param([Parameter(Mandatory)][string[]]$Arguments)

    $output = & git -C $projectRoot @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "git command failed: $($Arguments[0])"
    }
    return @($output | ForEach-Object { $_.ToString() })
}

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
    throw 'The requested device is not connected and authorized.'
}

$capturedAt = [DateTimeOffset]::Now
$timestamp = $capturedAt.ToString("yyyyMMdd'T'HHmmssfffzzz").Replace(':', '')
if ([string]::IsNullOrWhiteSpace($RunId)) {
    $RunId = "sp05-$timestamp"
}

$packagePath = @(Invoke-Adb -Arguments @('shell', 'pm', 'path', $packageName))
$installed = $packagePath.Count -gt 0 -and $packagePath[0] -like 'package:*'
$listenerSetting = (Invoke-Adb -Arguments @('shell', 'settings', 'get', 'secure', 'enabled_notification_listeners')) -join ''
$listenerAccessGranted = Test-ColonListContains -Value $listenerSetting -Expected $listenerComponent
$serviceLines = Invoke-Adb -Arguments @('shell', 'dumpsys', 'activity', 'services', $packageName)
$listenerServiceBound = ($serviceLines -join "`n") -match 'PhaseZeroNotificationListener'
$processOutput = & $adbPath -s $Serial shell pidof $packageName 2>&1
$processExitCode = $LASTEXITCODE
$processLines = @($processOutput | ForEach-Object { $_.ToString() })
if ($processExitCode -ne 0 -and @($processLines | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }).Count -gt 0) {
    throw 'adb pidof failed with unexpected output.'
}
$processId = Get-FirstProcessId -Lines $processLines
$oomScoreAdj = $null
if ($null -ne $processId) {
    $oomScoreAdjLines = Invoke-Adb -Arguments @('shell', 'cat', "/proc/$processId/oom_score_adj")
    $parsedOomScoreAdj = 0
    if ([int]::TryParse(($oomScoreAdjLines | Select-Object -First 1).Trim(), [ref]$parsedOomScoreAdj)) {
        $oomScoreAdj = $parsedOomScoreAdj
    }
}

$packageLines = Invoke-Adb -Arguments @('shell', 'dumpsys', 'package', $packageName)
$postNotificationMode = Get-AppOpMode -Lines (Invoke-Adb -Arguments @('shell', 'cmd', 'appops', 'get', $packageName, 'POST_NOTIFICATION'))
$runAnyBackgroundMode = Get-AppOpMode -Lines (Invoke-Adb -Arguments @('shell', 'cmd', 'appops', 'get', $packageName, 'RUN_ANY_IN_BACKGROUND'))
$standbyBucket = ((Invoke-Adb -Arguments @('shell', 'am', 'get-standby-bucket', $packageName)) | Select-Object -First 1).Trim()
$powerLines = Invoke-Adb -Arguments @('shell', 'dumpsys', 'power')
$dozeState = ((Invoke-Adb -Arguments @('shell', 'dumpsys', 'deviceidle', 'get', 'deep')) | Select-Object -First 1).Trim().ToUpperInvariant()
$deviceIdleWhitelistLines = Invoke-Adb -Arguments @('shell', 'dumpsys', 'deviceidle', 'whitelist')
$batteryOptimizationExempt = @($deviceIdleWhitelistLines | Where-Object { $_ -eq "user,$packageName" -or $_ -eq "system,$packageName" }).Count -gt 0
$uptimeText = ((Invoke-Adb -Arguments @('shell', 'cat', '/proc/uptime')) | Select-Object -First 1).Trim().Split(' ')[0]
$uptimeSeconds = 0.0
if (-not [double]::TryParse($uptimeText, [Globalization.NumberStyles]::Float, [Globalization.CultureInfo]::InvariantCulture, [ref]$uptimeSeconds)) {
    throw 'Unable to parse device uptime.'
}
$bootId = ((Invoke-Adb -Arguments @('shell', 'cat', '/proc/sys/kernel/random/boot_id')) | Select-Object -First 1).Trim()

$commit = ((Invoke-Git -Arguments @('rev-parse', 'HEAD')) | Select-Object -First 1).Trim()
$workingTreeDirty = @(Invoke-Git -Arguments @('status', '--porcelain')).Count -gt 0
$snapshot = [ordered]@{
    schemaVersion = 1
    spikeId = 'SP-05'
    runId = $RunId
    checkpoint = $Checkpoint
    capturedAt = $capturedAt.ToString('o')
    deviceElapsedRealtimeMillis = [long][Math]::Round($uptimeSeconds * 1000.0)
    bootIdSha256 = Get-Sha256Text -Text $bootId
    source = [ordered]@{
        commit = $commit
        workingTreeDirty = $workingTreeDirty
    }
    device = [ordered]@{
        serialSha256 = Get-Sha256Text -Text $Serial
        model = ((Invoke-Adb -Arguments @('shell', 'getprop', 'ro.product.model')) | Select-Object -First 1).Trim()
        sdk = ((Invoke-Adb -Arguments @('shell', 'getprop', 'ro.build.version.sdk')) | Select-Object -First 1).Trim()
        wakefulness = Get-Wakefulness -Lines $powerLines
        dozeState = $dozeState
    }
    app = [ordered]@{
        installed = $installed
        stopped = Get-UserStoppedState -Lines $packageLines
        listenerAccessGranted = $listenerAccessGranted
        listenerServiceBound = $listenerServiceBound
        processRunning = $null -ne $processId
        oomScoreAdj = $oomScoreAdj
        standbyBucket = $standbyBucket
        postNotificationMode = $postNotificationMode
        runAnyBackgroundMode = $runAnyBackgroundMode
        batteryOptimizationExempt = $batteryOptimizationExempt
    }
    privacy = [ordered]@{
        notificationContentCaptured = $false
        installedPackageListCaptured = $false
        rawListenerSettingCaptured = $false
    }
}

$evidenceDirectory = Join-Path $projectRoot '.local-evidence\phase-0'
$null = New-Item -ItemType Directory -Path $evidenceDirectory -Force
$evidenceName = "SP-05-listener-state-$RunId-$Checkpoint-$timestamp.json"
$evidencePath = Join-Path $evidenceDirectory $evidenceName
[System.IO.File]::WriteAllText(
    $evidencePath,
    ($snapshot | ConvertTo-Json -Depth 8) + [Environment]::NewLine,
    [System.Text.UTF8Encoding]::new($false)
)

Write-Output (
    "PASS evidence=$evidenceName access=$listenerAccessGranted bound=$listenerServiceBound " +
        "process=$($null -ne $processId) postNotification=$postNotificationMode wakefulness=$($snapshot.device.wakefulness)"
)
