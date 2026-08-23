[CmdletBinding()]
<#
.SYNOPSIS
Pulls the debug-only Phase 0 notification event stream into private local evidence.

.EXAMPLE
.\tools\pull-phase0-notification-events.ps1
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
$remoteRelativePath = 'files/phase0/notification-events-v1.jsonl'

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

$timestamp = [DateTimeOffset]::Now.ToString("yyyyMMdd'T'HHmmsszzz").Replace(':', '')
$evidenceDirectory = Join-Path $projectRoot '.local-evidence\phase-0'
$null = New-Item -ItemType Directory -Path $evidenceDirectory -Force
$outputName = "SP-01-notification-events-$timestamp.jsonl"
$outputPath = Join-Path $evidenceDirectory $outputName

$startInfo = [System.Diagnostics.ProcessStartInfo]::new()
$startInfo.FileName = $adbPath
$startInfo.UseShellExecute = $false
$startInfo.RedirectStandardOutput = $true
$startInfo.RedirectStandardError = $true
foreach ($argument in @('-s', $Serial, 'exec-out', 'run-as', $packageName, 'cat', $remoteRelativePath)) {
    $null = $startInfo.ArgumentList.Add($argument)
}
$process = [System.Diagnostics.Process]::new()
$process.StartInfo = $startInfo
$memoryStream = [System.IO.MemoryStream]::new()
try {
    if (-not $process.Start()) {
        throw 'Unable to start adb evidence pull.'
    }
    $process.StandardOutput.BaseStream.CopyTo($memoryStream)
    $standardError = $process.StandardError.ReadToEnd()
    $process.WaitForExit()
    if ($process.ExitCode -ne 0) {
        throw "adb evidence pull failed: $($standardError.Trim())"
    }
    if ($memoryStream.Length -eq 0) {
        throw 'The pulled evidence stream is empty. Grant notification access and wait for a callback first.'
    }
    [System.IO.File]::WriteAllBytes($outputPath, $memoryStream.ToArray())
}
finally {
    $memoryStream.Dispose()
    $process.Dispose()
}

$item = Get-Item -LiteralPath $outputPath
$lineCount = @(Get-Content -LiteralPath $outputPath -Encoding UTF8).Count
$sha256 = (Get-FileHash -LiteralPath $outputPath -Algorithm SHA256).Hash.ToLowerInvariant()
Write-Output "PASS evidence=$outputName bytes=$($item.Length) lines=$lineCount sha256=$sha256"
