[CmdletBinding()]
<#
.SYNOPSIS
Requests Android to rebind the already-authorized debug Phase 0 notification listener.

.EXAMPLE
.\tools\request-phase0-listener-rebind.ps1

.EXAMPLE
.\tools\request-phase0-listener-rebind.ps1 -ResetBinding
#>
param(
    [string]$Serial,
    [switch]$ResetBinding
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

trap {
    Write-Output "FAIL $($_.Exception.Message)"
    exit 1
}

$projectRoot = Split-Path -Parent $PSScriptRoot
$packageName = 'io.github.prince_dulb.ohmynotification'
$listenerClass = 'io.github.prince_dulb.ohmynotification.phase0.PhaseZeroNotificationListener'
$listenerComponent = "$packageName/$listenerClass"
$rebindComponent = "$packageName/io.github.prince_dulb.ohmynotification.phase0.PhaseZeroListenerRebindActivity"

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

$installed = @(Invoke-Adb -Arguments @('shell', 'pm', 'path', $packageName))
if ($installed.Count -eq 0 -or $installed[0] -notlike 'package:*') {
    throw 'The debug application is not installed.'
}
$accessSetting = (Invoke-Adb -Arguments @('shell', 'settings', 'get', 'secure', 'enabled_notification_listeners')) -join ''
if (@($accessSetting -split ':' | Where-Object { $_ -eq $listenerComponent }).Count -eq 0) {
    throw 'Notification listener access is not granted; requestRebind cannot grant it.'
}

$resetValue = $ResetBinding.IsPresent.ToString().ToLowerInvariant()
$startOutput = Invoke-Adb -Arguments @(
    'shell', 'am', 'start', '-W', '-n', $rebindComponent,
    '--ez', 'omn_reset_binding', $resetValue
)
$startText = $startOutput -join [Environment]::NewLine
if ($startText -match '(?m)^Error:|SecurityException|Exception occurred') {
    throw "Unable to start the debug rebind activity.$([Environment]::NewLine)$startText"
}

$deadline = [DateTimeOffset]::Now.AddSeconds(5)
do {
    $serviceDump = Invoke-Adb -Arguments @('shell', 'dumpsys', 'activity', 'services', $packageName)
    if (($serviceDump -join "`n") -match 'PhaseZeroNotificationListener') {
        Write-Output "PASS access=true bound=true requested=true reset=$resetValue"
        exit 0
    }
    Start-Sleep -Milliseconds 100
} while ([DateTimeOffset]::Now -lt $deadline)

throw 'Android accepted the rebind request but the listener did not bind within 5 seconds.'
