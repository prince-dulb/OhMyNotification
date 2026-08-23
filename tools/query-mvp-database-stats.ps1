[CmdletBinding()]
param([string]$Serial)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$projectRoot = Split-Path -Parent $PSScriptRoot
$localProperties = Join-Path $projectRoot 'local.properties'
$sdkLine = Get-Content -LiteralPath $localProperties -Encoding UTF8 |
    Where-Object { $_.StartsWith('sdk.dir=') } |
    Select-Object -First 1
if ($null -eq $sdkLine) { throw 'sdk.dir is missing from local.properties.' }
$sdkRoot = $sdkLine.Substring('sdk.dir='.Length).Trim().Replace('\:', ':').Replace('\\', '\')
$adbPath = Join-Path $sdkRoot 'platform-tools\adb.exe'
if (-not (Test-Path -LiteralPath $adbPath -PathType Leaf)) { throw 'adb.exe was not found.' }

$devices = @(
    & $adbPath devices |
        Where-Object { $_ -match '^(\S+)\s+device$' } |
        ForEach-Object { $Matches[1] }
)
if ([string]::IsNullOrWhiteSpace($Serial)) {
    if ($devices.Count -ne 1) { throw "Expected one device; found $($devices.Count)." }
    $Serial = $devices[0]
}

$component = 'io.github.prince_dulb.ohmynotification/.phase0.MvpDatabaseStatsReceiver'
$action = 'io.github.prince_dulb.ohmynotification.debug.DATABASE_STATS'
$output = & $adbPath -s $Serial shell am broadcast -a $action -n $component 2>&1
if ($LASTEXITCODE -ne 0) { throw 'Database stats broadcast failed.' }
$resultLine = $output | Where-Object { $_ -match 'Broadcast completed: result=(-?\d+), data="(.*)"' } | Select-Object -Last 1
if ($null -eq $resultLine) { throw 'Database stats result was not returned.' }
if ([int]$Matches[1] -ne -1) { throw "Database stats receiver returned: $($Matches[2])" }
Write-Output $Matches[2]
