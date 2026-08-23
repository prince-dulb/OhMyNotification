[CmdletBinding()]
<#
.SYNOPSIS
Proves that controlled test-source code and fixtures exist only in the instrumentation APK.

.EXAMPLE
.\tools\verify-apk-boundary.ps1
#>
param(
    [string]$ProductionApk,
    [string]$DebugApk,
    [string]$TestApk
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

Add-Type -AssemblyName System.IO.Compression.FileSystem

$projectRoot = Split-Path -Parent $PSScriptRoot
if ([string]::IsNullOrWhiteSpace($ProductionApk)) {
    $ProductionApk = Join-Path $projectRoot 'app\build\outputs\apk\release\app-release-unsigned.apk'
}
if ([string]::IsNullOrWhiteSpace($DebugApk)) {
    $DebugApk = Join-Path $projectRoot 'app\build\outputs\apk\debug\app-debug.apk'
}
if ([string]::IsNullOrWhiteSpace($TestApk)) {
    $TestApk = Join-Path $projectRoot 'app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk'
}

foreach ($apk in @($ProductionApk, $DebugApk, $TestApk)) {
    if (-not (Test-Path -LiteralPath $apk -PathType Leaf)) {
        throw "Required APK is missing. Run the documented build gate before this check."
    }
}

function Read-ApkIndex {
    param([Parameter(Mandatory)][string]$Path)

    $archive = [System.IO.Compression.ZipFile]::OpenRead($Path)
    try {
        $entryNames = @($archive.Entries | ForEach-Object FullName)
        $payload = [System.Text.StringBuilder]::new()
        foreach ($entry in $archive.Entries) {
            if ($entry.FullName.EndsWith('/')) {
                continue
            }
            $entryStream = $entry.Open()
            $memory = [System.IO.MemoryStream]::new()
            try {
                $entryStream.CopyTo($memory)
                $null = $payload.Append([System.Text.Encoding]::ASCII.GetString($memory.ToArray()))
            }
            finally {
                $memory.Dispose()
                $entryStream.Dispose()
            }
        }
        return [pscustomobject]@{
            EntryNames = $entryNames
            Payload = $payload.ToString()
        }
    }
    finally {
        $archive.Dispose()
    }
}

$productionIndex = Read-ApkIndex -Path $ProductionApk
$debugIndex = Read-ApkIndex -Path $DebugApk
$testIndex = Read-ApkIndex -Path $TestApk
$testOnlyMarkers = @(
    'OMN_TEST_ONLY_NOTIFICATION',
    'ControlledNotificationSource',
    'notification-fields-v1'
)

foreach ($marker in $testOnlyMarkers) {
    if ($productionIndex.Payload.Contains($marker)) {
        throw "Production APK contains test-only marker '$marker'."
    }
    if (-not $testIndex.Payload.Contains($marker)) {
        throw "Instrumentation APK is missing expected test-only marker '$marker'."
    }
}

$debugOnlyMarkers = @(
    'PhaseZeroControlledActionActivity',
    'PhaseZeroNotificationListener',
    'PhaseZeroListenerRebindActivity',
    'notification-events-v1.jsonl'
)
foreach ($marker in $debugOnlyMarkers) {
    if ($productionIndex.Payload.Contains($marker)) {
        throw "Production APK contains debug-only marker '$marker'."
    }
    if (-not $debugIndex.Payload.Contains($marker)) {
        throw "Debug APK is missing expected Phase 0 marker '$marker'."
    }
}

$fixtureEntry = 'assets/notification-fields-v1/dataset.json'
if ($productionIndex.EntryNames -contains $fixtureEntry) {
    throw "Production APK contains the controlled fixture asset."
}
if ($testIndex.EntryNames -notcontains $fixtureEntry) {
    throw "Instrumentation APK does not contain the controlled fixture asset."
}

function Resolve-Aapt2Path {
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
        $candidate = Join-Path $sdkPath 'build-tools\36.1.0\aapt2.exe'
        if (Test-Path -LiteralPath $candidate -PathType Leaf) {
            return $candidate
        }
    }
    throw 'The fixed Build Tools 36.1.0 aapt2 executable was not found.'
}

function Read-ApkBadging {
    param(
        [Parameter(Mandatory)][string]$Aapt2,
        [Parameter(Mandatory)][string]$Path
    )

    $output = & $Aapt2 dump badging $Path 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw 'aapt2 could not read an APK.'
    }
    return $output -join [Environment]::NewLine
}

$aapt2Path = Resolve-Aapt2Path
$productionBadging = Read-ApkBadging -Aapt2 $aapt2Path -Path $ProductionApk
$testBadging = Read-ApkBadging -Aapt2 $aapt2Path -Path $TestApk
$productionPackage = 'io.github.prince_dulb.ohmynotification'
$testPackage = "$productionPackage.test"
if ($productionBadging -notmatch "package: name='$([regex]::Escape($productionPackage))'") {
    throw 'Production APK package identity does not match the project contract.'
}
if ($testBadging -notmatch "package: name='$([regex]::Escape($testPackage))'") {
    throw 'Instrumentation APK does not use the required .test package identity.'
}

$allowedTestPermissions = @(
    'android.permission.POST_NOTIFICATIONS',
    'android.permission.REORDER_TASKS'
)
$testPermissions = @(
    [regex]::Matches($testBadging, "uses-permission: name='([^']+)'") |
        ForEach-Object { $_.Groups[1].Value } |
        Sort-Object -Unique
)
foreach ($permission in $testPermissions) {
    if ($allowedTestPermissions -notcontains $permission) {
        throw "Instrumentation APK contains non-whitelisted permission '$permission'."
    }
}
foreach ($permission in $allowedTestPermissions) {
    if ($testPermissions -notcontains $permission) {
        throw "Instrumentation APK is missing expected permission '$permission'."
    }
}

Write-Output "PASS production=$([System.IO.Path]::GetFileName($ProductionApk)) debug=$([System.IO.Path]::GetFileName($DebugApk)) test=$([System.IO.Path]::GetFileName($TestApk)) testMarkers=$($testOnlyMarkers.Count) debugMarkers=$($debugOnlyMarkers.Count) permissions=$($testPermissions.Count)"
