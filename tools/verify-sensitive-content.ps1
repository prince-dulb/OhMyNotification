[CmdletBinding()]
<#
.SYNOPSIS
Scans the public Git candidate tree without echoing any matched sensitive value.

.EXAMPLE
.\tools\verify-sensitive-content.ps1

.EXAMPLE
.\tools\verify-sensitive-content.ps1 -SelfTest
#>
param(
    [switch]$SelfTest
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

trap {
    Write-Output "FAIL $($_.Exception.Message)"
    exit 1
}

$projectRoot = Split-Path -Parent $PSScriptRoot
$textExtensions = @(
    '', '.bat', '.gitignore', '.gradle', '.json', '.kt', '.kts', '.md', '.pro',
    '.properties', '.ps1', '.sh', '.toml', '.txt', '.xml', '.yml', '.yaml'
)
$forbiddenPathRules = @(
    [pscustomobject]@{ Id = 'private_evidence_tracked'; Pattern = '(^|/)\.local-evidence(/|$)' },
    [pscustomobject]@{ Id = 'local_artifact_tracked'; Pattern = '(^|/)artifacts/.*\.(apk|aab|sha256)$' },
    [pscustomobject]@{ Id = 'local_properties_tracked'; Pattern = '(^|/)local\.properties$' },
    [pscustomobject]@{ Id = 'signing_material_tracked'; Pattern = '\.(jks|keystore|p12|pfx|pem|key)$' },
    [pscustomobject]@{ Id = 'private_database_tracked'; Pattern = '\.(db|sqlite|sqlite3)$' },
    [pscustomobject]@{ Id = 'environment_file_tracked'; Pattern = '(^|/)\.env(?:\..+)?$' }
)
$contentRules = @(
    [pscustomobject]@{ Id = 'windows_user_profile'; Pattern = '(?i)[A-Z]:[\\/]Users[\\/][^\\/\s"'']+[\\/]' },
    [pscustomobject]@{ Id = 'absolute_sdk_assignment'; Pattern = '(?im)^\s*sdk\.dir\s*=\s*(?:[A-Z]:[\\/]|/Users/|/home/)' },
    [pscustomobject]@{ Id = 'private_key_material'; Pattern = '-----BEGIN\s+(?:RSA\s+|EC\s+|OPENSSH\s+)?PRIVATE\s+KEY-----' },
    [pscustomobject]@{ Id = 'github_classic_token'; Pattern = '(?i)gh[pousr]_[A-Za-z0-9]{20,}' },
    [pscustomobject]@{ Id = 'github_fine_grained_token'; Pattern = '(?i)github_pat_[A-Za-z0-9_]{20,}' },
    [pscustomobject]@{ Id = 'google_api_key'; Pattern = 'AIza[0-9A-Za-z_-]{30,}' },
    [pscustomobject]@{ Id = 'bearer_credential'; Pattern = '(?i)Bearer\s+[A-Za-z0-9._~+/-]{24,}={0,2}' },
    [pscustomobject]@{ Id = 'raw_device_serial_field'; Pattern = '"deviceSerial"\s*:' }
)

function Get-RuleMatches {
    param([Parameter(Mandatory)][string]$Text)

    $matches = [System.Collections.Generic.List[string]]::new()
    foreach ($rule in $contentRules) {
        if ([regex]::IsMatch($Text, $rule.Pattern)) {
            $matches.Add($rule.Id)
        }
    }
    return $matches
}

if ($SelfTest) {
    $selfTestCases = @(
        [pscustomobject]@{ Rule = 'windows_user_profile'; Value = ('C:' + '\Users\' + 'sample-user\private.txt') },
        [pscustomobject]@{ Rule = 'absolute_sdk_assignment'; Value = ('sdk.dir=' + 'E:' + '\Android\Sdk') },
        [pscustomobject]@{ Rule = 'private_key_material'; Value = ('-----BEGIN ' + 'PRIVATE KEY-----') },
        [pscustomobject]@{ Rule = 'github_classic_token'; Value = ('ghp_' + ('A' * 36)) },
        [pscustomobject]@{ Rule = 'github_fine_grained_token'; Value = ('github_pat_' + ('B' * 30)) },
        [pscustomobject]@{ Rule = 'google_api_key'; Value = ('AIza' + ('C' * 35)) },
        [pscustomobject]@{ Rule = 'bearer_credential'; Value = ('Bearer ' + ('D' * 32)) },
        [pscustomobject]@{ Rule = 'raw_device_serial_field'; Value = ('"device' + 'Serial": "private"') }
    )
    foreach ($testCase in $selfTestCases) {
        $matchedRules = @(Get-RuleMatches -Text $testCase.Value)
        if ($matchedRules -notcontains $testCase.Rule) {
            throw "Self-test did not trigger rule=$($testCase.Rule)"
        }
    }
}

$candidateFiles = @(
    & git -c core.quotepath=false -C $projectRoot ls-files --cached --others --exclude-standard 2>&1 |
        ForEach-Object { $_.ToString() } |
        Where-Object { -not [string]::IsNullOrWhiteSpace($_) } |
        Sort-Object -Unique
)
if ($LASTEXITCODE -ne 0) {
    throw 'git ls-files failed.'
}

$privateSentinels = [System.Collections.Generic.List[string]]::new()
$sentinelDirectory = Join-Path $projectRoot '.local-evidence\phase-0'
if (Test-Path -LiteralPath $sentinelDirectory -PathType Container) {
    foreach ($sentinelFile in Get-ChildItem -LiteralPath $sentinelDirectory -Filter '*.sentinels.txt' -File) {
        foreach ($line in Get-Content -LiteralPath $sentinelFile.FullName -Encoding UTF8) {
            if (-not [string]::IsNullOrWhiteSpace($line) -and -not $line.TrimStart().StartsWith('#')) {
                if ($line.Length -lt 4) {
                    throw 'Private sentinel values must contain at least four characters.'
                }
                $privateSentinels.Add($line)
            }
        }
    }
}

$violations = [System.Collections.Generic.List[object]]::new()
$scannedTextFiles = 0
foreach ($relativePath in $candidateFiles) {
    $normalizedPath = $relativePath.Replace('\', '/')
    foreach ($rule in $forbiddenPathRules) {
        if ([regex]::IsMatch($normalizedPath, $rule.Pattern, [System.Text.RegularExpressions.RegexOptions]::IgnoreCase)) {
            $violations.Add([pscustomobject]@{ Rule = $rule.Id; File = $normalizedPath })
        }
    }

    $extension = [System.IO.Path]::GetExtension($normalizedPath).ToLowerInvariant()
    if ($textExtensions -notcontains $extension) {
        continue
    }
    $fullPath = Join-Path $projectRoot $relativePath
    if (-not (Test-Path -LiteralPath $fullPath -PathType Leaf)) {
        continue
    }
    $text = [System.IO.File]::ReadAllText($fullPath, [System.Text.Encoding]::UTF8)
    $scannedTextFiles += 1
    foreach ($ruleId in Get-RuleMatches -Text $text) {
        $violations.Add([pscustomobject]@{ Rule = $ruleId; File = $normalizedPath })
    }
    for ($index = 0; $index -lt $privateSentinels.Count; $index += 1) {
        if ($text.Contains($privateSentinels[$index], [System.StringComparison]::Ordinal)) {
            $violations.Add([pscustomobject]@{
                Rule = 'private_sentinel_{0:d3}' -f ($index + 1)
                File = $normalizedPath
            })
        }
    }
}

if ($violations.Count -gt 0) {
    foreach ($violation in $violations | Sort-Object Rule, File -Unique) {
        Write-Output "FAIL rule=$($violation.Rule) file=$($violation.File)"
    }
    exit 1
}

Write-Output "PASS publicFiles=$($candidateFiles.Count) textFiles=$scannedTextFiles rules=$($contentRules.Count) privateSentinels=$($privateSentinels.Count) selfTest=$([bool]$SelfTest)"
