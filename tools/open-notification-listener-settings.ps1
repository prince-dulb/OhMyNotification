[CmdletBinding()]
<#
.SYNOPSIS
Opens Android's notification-listener permission page for the Phase 0 debug service.

.EXAMPLE
.\tools\open-notification-listener-settings.ps1
#>
param(
    [string]$Serial
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

trap {
    Write-Output "FAIL $($_.Exception.Message)"
    exit 1
}

$projectRoot = Split-Path -Parent $PSScriptRoot
$packageName = 'io.github.prince_dulb.ohmynotification'
$serviceClass = 'io.github.prince_dulb.ohmynotification.phase0.PhaseZeroNotificationListener'
$componentName = "$packageName/$serviceClass"

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

$installed = & $adbPath -s $Serial shell pm path $packageName 2>&1
if ($LASTEXITCODE -ne 0 -or ($installed -join '') -notmatch '^package:') {
    throw 'The OMN debug APK is not installed.'
}

$detailOutput = & $adbPath -s $Serial shell am start -W `
    -a android.settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS `
    --es android.provider.extra.NOTIFICATION_LISTENER_COMPONENT_NAME $componentName 2>&1
$detailText = $detailOutput -join [Environment]::NewLine
if ($LASTEXITCODE -ne 0 -or $detailText -notmatch '(?m)^Status:\s+ok\r?$') {
    $listOutput = & $adbPath -s $Serial shell am start -W `
        -a android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS 2>&1
    $listText = $listOutput -join [Environment]::NewLine
    if ($LASTEXITCODE -ne 0 -or $listText -notmatch '(?m)^Status:\s+ok\r?$') {
        throw 'Android did not open notification-listener settings.'
    }
    Write-Output 'PASS opened=listener-list fallback=true'
    exit 0
}

Write-Output 'PASS opened=listener-detail fallback=false'
