[CmdletBinding()]
<#
.SYNOPSIS
Installs the local Phase 0 Debug APK, launches its main activity, and records private smoke-test evidence.

.EXAMPLE
.\tools\install-and-smoke-test.ps1

.EXAMPLE
.\tools\install-and-smoke-test.ps1 -Serial <adb-serial>
#>
param(
    [string]$ApkPath,
    [string]$Serial
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

trap {
    Write-Output "FAIL $($_.Exception.Message)"
    exit 1
}

$projectRoot = Split-Path -Parent $PSScriptRoot
if ([string]::IsNullOrWhiteSpace($ApkPath)) {
    $ApkPath = Join-Path $projectRoot 'artifacts\OhMyNotification-0.0.0-phase0-debug.apk'
}
if (-not (Test-Path -LiteralPath $ApkPath -PathType Leaf)) {
    throw 'Local delivery APK is missing. Build and copy the documented Debug artifact first.'
}

$null = & (Join-Path $PSScriptRoot 'verify-installable-apk.ps1') -ApkPath $ApkPath

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

$adbPrefix = @('-s', $Serial)
function Invoke-Adb {
    param([Parameter(Mandatory)][string[]]$Arguments)

    $commandOutput = & $adbPath @adbPrefix @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "adb command failed: $($Arguments[0])"
    }
    return @($commandOutput | ForEach-Object { $_.ToString() })
}

$packageName = 'io.github.prince_dulb.ohmynotification'
$componentName = "$packageName/.MainActivity"
$timestamp = [DateTimeOffset]::Now.ToString("yyyyMMdd'T'HHmmsszzz").Replace(':', '')
$evidenceName = "P0-APK-smoke-$timestamp.json"
$evidencePath = Join-Path $projectRoot ".local-evidence\phase-0\$evidenceName"
$result = [ordered]@{
    schemaVersion = 1
    timestamp = [DateTimeOffset]::Now.ToString('o')
    status = 'RUNNING'
    packageName = $packageName
    componentName = $componentName
    artifactName = [System.IO.Path]::GetFileName($ApkPath)
    artifactSha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $ApkPath).Hash.ToLowerInvariant()
    deviceSerialSha256 = Get-Sha256Text -Text $Serial
    deviceSdk = $null
    deviceAbiList = $null
    deviceModel = $null
    installSucceeded = $false
    launchSucceeded = $false
    processObserved = $false
    resumedActivityObserved = $false
    failure = $null
}
$failure = $null

try {
    $result.deviceSdk = (Invoke-Adb -Arguments @('shell', 'getprop', 'ro.build.version.sdk') | Select-Object -First 1).Trim()
    $result.deviceAbiList = (Invoke-Adb -Arguments @('shell', 'getprop', 'ro.product.cpu.abilist') | Select-Object -First 1).Trim()
    $result.deviceModel = (Invoke-Adb -Arguments @('shell', 'getprop', 'ro.product.model') | Select-Object -First 1).Trim()
    if ([int]$result.deviceSdk -lt 36) {
        throw "Device SDK $($result.deviceSdk) is below the Android 16 project minimum."
    }
    if ($result.deviceAbiList -notmatch '(^|,)arm64-v8a(,|$)') {
        throw 'Device ABI list does not include arm64-v8a.'
    }

    $installOutput = Invoke-Adb -Arguments @('install', '-r', $ApkPath)
    if (($installOutput -join [Environment]::NewLine) -notmatch '(?m)^Success$') {
        throw 'adb install did not report Success.'
    }
    $result.installSucceeded = $true

    $launchOutput = Invoke-Adb -Arguments @('shell', 'am', 'start', '-W', '-S', '-n', $componentName)
    $launchText = $launchOutput -join [Environment]::NewLine
    if ($launchText -notmatch '(?m)^Status:\s+ok$' -or $launchText -notmatch [regex]::Escape($componentName)) {
        throw 'Activity Manager did not report a successful launch of MainActivity.'
    }
    $result.launchSucceeded = $true

    $pidOutput = Invoke-Adb -Arguments @('shell', 'pidof', $packageName)
    $result.processObserved = -not [string]::IsNullOrWhiteSpace($pidOutput -join '')
    if (-not $result.processObserved) {
        throw 'The application process was not observed after launch.'
    }

    $activityOutput = Invoke-Adb -Arguments @('shell', 'dumpsys', 'activity', 'activities')
    $activityText = $activityOutput -join [Environment]::NewLine
    $componentPattern = [regex]::Escape($componentName)
    $result.resumedActivityObserved = $activityText -match "(mResumedActivity|topResumedActivity).*${componentPattern}"
    if (-not $result.resumedActivityObserved) {
        throw 'MainActivity was not observed as the resumed activity.'
    }

    $result.status = 'PASS'
}
catch {
    $result.status = 'FAIL'
    $result.failure = $_.Exception.Message
    $failure = $_.Exception
}
finally {
    $json = $result | ConvertTo-Json -Depth 4
    [System.IO.File]::WriteAllText(
        $evidencePath,
        $json + [Environment]::NewLine,
        [System.Text.UTF8Encoding]::new($false)
    )
}

if ($null -ne $failure) {
    throw $failure
}

Write-Output "PASS install=true launch=true process=true resumed=true evidence=$evidenceName"
