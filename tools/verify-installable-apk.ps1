[CmdletBinding()]
<#
.SYNOPSIS
Checks the locally built Debug APK's signature, alignment, identity, SDK boundary, launcher, and 64-bit ABI support.

.EXAMPLE
.\tools\verify-installable-apk.ps1

.EXAMPLE
.\tools\verify-installable-apk.ps1 -ApkPath .\artifacts\OhMyNotification-0.1.3-mvp-debug.apk
#>
param(
    [string]$ApkPath
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$projectRoot = Split-Path -Parent $PSScriptRoot
if ([string]::IsNullOrWhiteSpace($ApkPath)) {
    $ApkPath = Join-Path $projectRoot 'app\build\outputs\apk\debug\app-debug.apk'
}
if (-not (Test-Path -LiteralPath $ApkPath -PathType Leaf)) {
    throw 'Debug APK is missing. Run .\gradlew.bat :app:assembleDebug first.'
}

function Resolve-SdkPath {
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
        $buildToolsPath = Join-Path $sdkPath 'build-tools\36.1.0'
        if (
            (Test-Path -LiteralPath (Join-Path $buildToolsPath 'aapt2.exe') -PathType Leaf) -and
            (Test-Path -LiteralPath (Join-Path $buildToolsPath 'apksigner.bat') -PathType Leaf) -and
            (Test-Path -LiteralPath (Join-Path $buildToolsPath 'zipalign.exe') -PathType Leaf)
        ) {
            return $sdkPath
        }
    }
    throw 'Android SDK Build Tools 36.1.0 were not found in the process environment or local.properties.'
}

$sdkRoot = Resolve-SdkPath
$buildTools = Join-Path $sdkRoot 'build-tools\36.1.0'
$aapt2 = Join-Path $buildTools 'aapt2.exe'
$apksigner = Join-Path $buildTools 'apksigner.bat'
$zipalign = Join-Path $buildTools 'zipalign.exe'

$null = & $apksigner verify --verbose $ApkPath 2>&1
if ($LASTEXITCODE -ne 0) {
    throw 'APK signature verification failed.'
}
$null = & $zipalign -c -P 16 -v 4 $ApkPath 2>&1
if ($LASTEXITCODE -ne 0) {
    throw 'APK zip/page alignment verification failed.'
}

$badging = (& $aapt2 dump badging $ApkPath 2>&1) -join [Environment]::NewLine
if ($LASTEXITCODE -ne 0) {
    throw 'aapt2 could not inspect the APK.'
}

$expectedPackage = 'io.github.prince_dulb.ohmynotification'
$expectedVersion = '0.1.3-mvp'
if ($badging -notmatch "package: name='$([regex]::Escape($expectedPackage))'.*versionName='$([regex]::Escape($expectedVersion))'") {
    throw 'APK package identity or version does not match the MVP contract.'
}
if ($badging -notmatch "sdkVersion:'36'" -or $badging -notmatch "targetSdkVersion:'36'") {
    throw 'APK minSdk or targetSdk does not match the Android 16-only contract.'
}
if ($badging -notmatch "launchable-activity: name='$([regex]::Escape($expectedPackage)).MainActivity'") {
    throw 'APK does not expose the expected launcher activity.'
}
if ($badging -notmatch 'application-debuggable') {
    throw 'The local installation artifact must be a debuggable Debug APK.'
}

$forbiddenPermissions = @(
    'android.permission.INTERNET',
    'android.permission.GET_ACCOUNTS',
    'android.permission.READ_CONTACTS',
    'android.permission.QUERY_ALL_PACKAGES'
)
foreach ($permission in $forbiddenPermissions) {
    if ($badging -match "uses-permission: name='$([regex]::Escape($permission))'") {
        throw "APK contains forbidden Phase 0 permission '$permission'."
    }
}

Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [System.IO.Compression.ZipFile]::OpenRead((Resolve-Path -LiteralPath $ApkPath))
try {
    $nativeEntries = @($archive.Entries | Where-Object { $_.FullName -match '^lib/[^/]+/[^/]+\.so$' })
    $hasArm64 = @($nativeEntries | Where-Object { $_.FullName.StartsWith('lib/arm64-v8a/') }).Count -gt 0
    if ($nativeEntries.Count -gt 0 -and -not $hasArm64) {
        throw 'APK contains native libraries but no arm64-v8a variant.'
    }
}
finally {
    $archive.Dispose()
}

$sha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $ApkPath).Hash.ToLowerInvariant()
Write-Output "PASS apk=$([System.IO.Path]::GetFileName($ApkPath)) sha256=$sha256 package=$expectedPackage version=$expectedVersion arm64=$hasArm64"
