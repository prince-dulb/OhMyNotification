[CmdletBinding()]
<#
.SYNOPSIS
Installs or invokes the Phase 0 instrumentation APK as an independent synthetic notification source.

.EXAMPLE
.\tools\invoke-controlled-notification.ps1 -Operation publish -CaseId action -Install

.EXAMPLE
.\tools\invoke-controlled-notification.ps1 -Operation update -CaseId unicode

.EXAMPLE
.\tools\invoke-controlled-notification.ps1 -Operation remove

.EXAMPLE
.\tools\invoke-controlled-notification.ps1 -Operation contract
#>
param(
    [Parameter(Mandatory)]
    [ValidateSet('publish', 'update', 'remove', 'contract')]
    [string]$Operation,

    [ValidateSet('missing-title', 'missing-text', 'unicode', 'long-text', 'invalid-time', 'action')]
    [string]$CaseId = 'action',

    [switch]$Install,

    [string]$Serial
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$projectRoot = Split-Path -Parent $PSScriptRoot
$targetPackage = 'io.github.prince_dulb.ohmynotification'
$testPackage = 'io.github.prince_dulb.ohmynotification.test'
$runner = "$testPackage/androidx.test.runner.AndroidJUnitRunner"
$debugApk = Join-Path $projectRoot 'app\build\outputs\apk\debug\app-debug.apk'
$testApk = Join-Path $projectRoot 'app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk'

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
        throw "Expected exactly one authorized device; found $($availableDevices.Count). Use -Serial when multiple devices are connected."
    }
    $Serial = $availableDevices[0]
}
elseif ($availableDevices -notcontains $Serial) {
    throw 'The requested device is not connected and authorized.'
}

$adbPrefix = @('-s', $Serial)
function Invoke-Adb {
    param([Parameter(Mandatory)][string[]]$Arguments)

    $commandOutput = & $adbPath @adbPrefix @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "adb command failed: $($Arguments[0])"
    }
    return @($commandOutput | ForEach-Object { $_.ToString() })
}

if ($Install) {
    foreach ($apk in @($debugApk, $testApk)) {
        if (-not (Test-Path -LiteralPath $apk -PathType Leaf)) {
            throw 'Required APK is missing. Build debug and debugAndroidTest APKs before using -Install.'
        }
    }
    $null = Invoke-Adb -Arguments @('install', '-r', $debugApk)
    $null = Invoke-Adb -Arguments @('install', '-r', '-t', $testApk)
}

$targetPath = Invoke-Adb -Arguments @('shell', 'pm', 'path', $targetPackage)
$testPath = Invoke-Adb -Arguments @('shell', 'pm', 'path', $testPackage)
if ($targetPath.Count -eq 0 -or $testPath.Count -eq 0) {
    throw 'The target or instrumentation package is not installed. Re-run with -Install.'
}
$null = Invoke-Adb -Arguments @(
    'shell',
    'pm',
    'grant',
    $testPackage,
    'android.permission.POST_NOTIFICATIONS'
)

if ($Operation -eq 'contract') {
    $testClass = 'io.github.prince_dulb.ohmynotification.testsource.ControlledNotificationSourceContractTest'
    $instrumentationOutput = Invoke-Adb -Arguments @(
        'shell',
        'am',
        'instrument',
        '-w',
        '-r',
        '-e',
        'class',
        $testClass,
        $runner
    )
}
else {
    $testMethod = 'io.github.prince_dulb.ohmynotification.testsource.ControlledNotificationCommandTest#executeCommand'
    $instrumentationOutput = Invoke-Adb -Arguments @(
        'shell',
        'am',
        'instrument',
        '-w',
        '-r',
        '-e',
        'class',
        $testMethod,
        '-e',
        'omn_operation',
        $Operation,
        '-e',
        'omn_case_id',
        $CaseId,
        $runner
    )
}

$instrumentationText = $instrumentationOutput -join [Environment]::NewLine
if ($instrumentationText -match 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed' -or $instrumentationText -notmatch 'OK \(') {
    throw "Instrumentation did not report a clean JUnit result.$([Environment]::NewLine)$instrumentationText"
}

$instrumentationOutput | Write-Output
Write-Output "PASS operation=$Operation case=$CaseId"
