[CmdletBinding()]
<#
.SYNOPSIS
Validates every committed synthetic dataset's metadata, SHA-256, and privacy sentinels.

.EXAMPLE
.\tools\verify-testdata.ps1
#>
param()

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$projectRoot = Split-Path -Parent $PSScriptRoot
$testDataRoot = Join-Path $projectRoot 'testdata'
$requiredKeys = @(
    'dataset.id',
    'dataset.version',
    'seed',
    'sha256',
    'generation.rule',
    'expected.summary',
    'change.reason'
)
$forbiddenPatterns = @(
    '(?i)tv\.danmaku\.bili',
    '(?i)bilibili\.com',
    '(?i)https?://',
    '[A-Za-z]:\\',
    '(?i)BV[0-9A-Za-z]{10}',
    '(?i)av[0-9]{3,}'
)

function Read-Metadata {
    param([Parameter(Mandatory)][string]$Path)

    $result = @{}
    foreach ($line in Get-Content -LiteralPath $Path -Encoding UTF8) {
        $trimmed = $line.Trim()
        if ($trimmed.Length -eq 0 -or $trimmed.StartsWith('#')) {
            continue
        }

        $parts = $trimmed.Split('=', 2)
        if ($parts.Count -ne 2 -or $parts[0].Trim().Length -eq 0) {
            throw "Invalid metadata line in ${Path}: $line"
        }
        $result[$parts[0].Trim()] = $parts[1].Trim()
    }
    return $result
}

$datasetDirectories = @(Get-ChildItem -LiteralPath $testDataRoot -Directory | Sort-Object Name)
if ($datasetDirectories.Count -eq 0) {
    throw "No versioned datasets found in $testDataRoot"
}

foreach ($directory in $datasetDirectories) {
    $datasetPath = Join-Path $directory.FullName 'dataset.json'
    $metadataPath = Join-Path $directory.FullName 'metadata.properties'
    if (-not (Test-Path -LiteralPath $datasetPath -PathType Leaf)) {
        throw "Missing dataset.json: $($directory.Name)"
    }
    if (-not (Test-Path -LiteralPath $metadataPath -PathType Leaf)) {
        throw "Missing metadata.properties: $($directory.Name)"
    }

    $metadata = Read-Metadata -Path $metadataPath
    foreach ($key in $requiredKeys) {
        if (-not $metadata.ContainsKey($key) -or [string]::IsNullOrWhiteSpace($metadata[$key])) {
            throw "Missing metadata key '$key' in $metadataPath"
        }
    }
    if ($metadata['dataset.id'] -ne $directory.Name) {
        throw "Dataset ID does not match directory: $($directory.Name)"
    }
    if ($metadata['sha256'] -notmatch '^[0-9a-f]{64}$') {
        throw "Invalid SHA-256 metadata for $($directory.Name)"
    }

    $actualHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $datasetPath).Hash.ToLowerInvariant()
    if ($actualHash -ne $metadata['sha256']) {
        throw "SHA-256 mismatch for $($directory.Name): expected $($metadata['sha256']), actual $actualHash"
    }

    $content = Get-Content -LiteralPath $datasetPath -Raw -Encoding UTF8
    if (-not $content.Contains('OMN_SYNTHETIC_')) {
        throw "Synthetic sentinel missing from $($directory.Name)"
    }
    foreach ($pattern in $forbiddenPatterns) {
        if ($content -match $pattern) {
            throw "Forbidden real-world pattern '$pattern' found in $($directory.Name)"
        }
    }

    Write-Output "PASS dataset=$($directory.Name) sha256=$actualHash"
}
