[CmdletBinding()]
<#
.SYNOPSIS
Dispatches only the latest runtime action captured from the controlled test APK.

.EXAMPLE
.\tools\invoke-phase0-controlled-runtime-action.ps1

.EXAMPLE
.\tools\invoke-phase0-controlled-runtime-action.ps1 -ExpectedStatus NOT_FOUND
#>
param(
    [ValidateSet('ACCEPTED', 'CANCELED', 'NOT_FOUND', 'SECURITY_REJECTED')]
    [string]$ExpectedStatus = 'ACCEPTED',

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
$testPackage = 'io.github.prince_dulb.ohmynotification.test'
$dispatchComponent = "$packageName/io.github.prince_dulb.ohmynotification.phase0.PhaseZeroControlledActionActivity"
$dispatchResultPath = 'files/phase0-controlled-action-result.properties'
$targetResultPath = 'files/controlled-target-result.properties'

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

function Read-PrivateProperties {
    param(
        [Parameter(Mandatory)][string]$OwnerPackage,
        [Parameter(Mandatory)][string]$RelativePath
    )

    $raw = & $adbPath -s $Serial exec-out run-as $OwnerPackage cat $RelativePath 2>&1
    if ($LASTEXITCODE -ne 0) {
        return $null
    }
    $properties = @{}
    foreach ($lineValue in @($raw | ForEach-Object { $_.ToString() })) {
        $line = $lineValue.Trim()
        if ($line.Length -eq 0 -or $line.StartsWith('#')) {
            continue
        }
        $parts = $line.Split('=', 2)
        if ($parts.Count -eq 2) {
            $properties[$parts[0].Trim()] = $parts[1].Trim()
        }
    }
    return $properties
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

foreach ($requiredPackage in @($packageName, $testPackage)) {
    $installed = @(Invoke-Adb -Arguments @('shell', 'pm', 'path', $requiredPackage))
    if ($installed.Count -eq 0 -or $installed[0] -notlike 'package:*') {
        throw "Required package is not installed: $requiredPackage"
    }
}

$beforeTarget = Read-PrivateProperties -OwnerPackage $testPackage -RelativePath $targetResultPath
$beforeElapsed = if ($null -ne $beforeTarget) { $beforeTarget['opened_elapsed_nanos'] } else { $null }
$commandToken = [Guid]::NewGuid().ToString('N')
$startOutput = Invoke-Adb -Arguments @(
    'shell',
    'am',
    'start',
    '-W',
    '-n',
    $dispatchComponent,
    '--es',
    'omn_command_token',
    $commandToken
)
$startText = $startOutput -join [Environment]::NewLine
if ($startText -match '(?m)^Error:|SecurityException|Exception occurred') {
    throw "Unable to start the controlled runtime-action dispatcher.$([Environment]::NewLine)$startText"
}

$dispatchResult = $null
$deadline = [DateTimeOffset]::Now.AddSeconds(4)
do {
    $candidate = Read-PrivateProperties -OwnerPackage $packageName -RelativePath $dispatchResultPath
    if ($null -ne $candidate -and $candidate['command_token'] -eq $commandToken) {
        $dispatchResult = $candidate
        break
    }
    Start-Sleep -Milliseconds 50
} while ([DateTimeOffset]::Now -lt $deadline)
if ($null -eq $dispatchResult) {
    throw 'Timed out waiting for the controlled runtime-action dispatch result.'
}
if ($dispatchResult['status'] -ne $ExpectedStatus) {
    throw "Expected dispatch status $ExpectedStatus but observed $($dispatchResult['status'])."
}

$targetOpened = $false
if ($ExpectedStatus -eq 'ACCEPTED') {
    $targetDeadline = [DateTimeOffset]::Now.AddSeconds(4)
    do {
        $targetResult = Read-PrivateProperties -OwnerPackage $testPackage -RelativePath $targetResultPath
        if (
            $null -ne $targetResult -and
            $targetResult['status'] -eq 'OPENED' -and
            -not [string]::IsNullOrWhiteSpace($targetResult['opened_elapsed_nanos']) -and
            $targetResult['opened_elapsed_nanos'] -ne $beforeElapsed
        ) {
            $targetOpened = $true
            break
        }
        Start-Sleep -Milliseconds 50
    } while ([DateTimeOffset]::Now -lt $targetDeadline)
    if (-not $targetOpened) {
        throw 'The system accepted the controlled runtime action but the target landing receipt did not advance.'
    }
}

Write-Output (
    "PASS dispatch=$($dispatchResult['status']) targetOpened=$targetOpened " +
        "registrySizeAfter=$($dispatchResult['registry_size_after'])"
)
