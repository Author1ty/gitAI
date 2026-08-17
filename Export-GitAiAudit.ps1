<#
.SYNOPSIS
Exports Git AI Notes and repository metadata for a compatibility audit.

.DESCRIPTION
Read-only collector. It does not change the target repository, its notes,
configuration, refs, working tree, or remotes.

By default, email addresses, HTTP(S) URLs, and credential-bearing remote URLs
are redacted because the export may be shared. Use -IncludeSensitive only when
it is safe to retain the original values.

.EXAMPLE
.\Export-GitAiAudit.ps1 -RepositoryPath D:\code\my-repo

.EXAMPLE
.\Export-GitAiAudit.ps1 -RepositoryPath D:\code\my-repo -OutputPath D:\exports\my-repo-git-ai-audit -IncludeSensitive
#>

[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [string]$RepositoryPath = (Get-Location).Path,

    [string]$OutputPath,

    [ValidateRange(0, 1000000)]
    [int]$MaxNotes = 0,

    [switch]$IncludeSensitive
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Invoke-GitCapture {
    param(
        [Parameter(Mandatory = $true)]
        [string[]]$Arguments
    )

    $previousPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $output = & git @Arguments 2>&1
        $exitCode = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $previousPreference
    }

    return [pscustomobject]@{
        ExitCode = $exitCode
        Text     = (($output | ForEach-Object { $_.ToString() }) -join [Environment]::NewLine)
    }
}

function Get-Sha256 {
    param([Parameter(Mandatory = $true)][string]$Text)

    $bytes = [System.Text.Encoding]::UTF8.GetBytes($Text)
    $hash = [System.Security.Cryptography.SHA256]::Create().ComputeHash($bytes)
    return (-join ($hash | ForEach-Object { $_.ToString('x2') }))
}

function ConvertTo-SafeText {
    param([AllowEmptyString()][string]$Text)

    if ($IncludeSensitive) {
        return $Text
    }

    # Keep the shape of a Note intact while removing values that should not be
    # uploaded for a schema / compatibility review.
    $safe = $Text
    $safe = [regex]::Replace($safe, '(?i)https?://[^\s"''<>]+', '<REDACTED_URL>')
    $safe = [regex]::Replace($safe, '(?i)\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}\b', '<REDACTED_EMAIL>')
    $safe = [regex]::Replace($safe, '(?i)([a-z][a-z0-9_-]*(?:token|api[_-]?key|secret|password)[a-z0-9_-]*\s*[=:]\s*)[^\s"'']+', '$1<REDACTED_SECRET>')
    $safe = [regex]::Replace($safe, '(?i)(https?://)[^/@\s]+@', '$1<REDACTED_CREDENTIAL>@')
    return $safe
}

function Get-SafeRemoteUrl {
    param([AllowEmptyString()][string]$Url)

    if ($IncludeSensitive) {
        return $Url
    }

    return [regex]::Replace($Url, '^(?<scheme>[a-z]+://)(?<credentials>[^/@\s]+@)', '${scheme}<REDACTED_CREDENTIAL>@', 'IgnoreCase')
}

function Write-Utf8File {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [AllowEmptyString()][string]$Text
    )

    $directory = Split-Path -Parent $Path
    if (-not (Test-Path -LiteralPath $directory)) {
        New-Item -ItemType Directory -Path $directory -Force | Out-Null
    }

    [System.IO.File]::WriteAllText($Path, $Text, [System.Text.UTF8Encoding]::new($false))
}

function Add-JsonLine {
    param(
        [Parameter(Mandatory = $true)]$Value,
        [Parameter(Mandatory = $true)][string]$Path
    )

    $json = $Value | ConvertTo-Json -Depth 12 -Compress
    Add-Content -LiteralPath $Path -Value $json -Encoding utf8
}

function Get-SafeFileName {
    param([Parameter(Mandatory = $true)][string]$Value)

    $safe = $Value -replace '[\\/:*?"<>|]', '_'
    if ($safe.Length -gt 100) {
        $safe = $safe.Substring(0, 80) + '_' + (Get-Sha256 $Value).Substring(0, 16)
    }
    return $safe
}

function Get-JsonKeyNames {
    param([AllowEmptyString()][string]$Text)

    $keys = New-Object System.Collections.Generic.List[string]
    foreach ($match in [regex]::Matches($Text, '(?m)"(?<key>(?:\\.|[^"\\])*)"\s*:')) {
        $keys.Add($match.Groups['key'].Value)
    }
    return $keys
}

$resolvedRepository = (Resolve-Path -LiteralPath $RepositoryPath).Path
Push-Location -LiteralPath $resolvedRepository
try {
    $rootResult = Invoke-GitCapture @('rev-parse', '--show-toplevel')
    if ($rootResult.ExitCode -ne 0) {
        throw "'$resolvedRepository' is not a Git repository."
    }
    $repositoryRoot = $rootResult.Text.Trim()

    if ([string]::IsNullOrWhiteSpace($OutputPath)) {
        $stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
        $repositoryName = Split-Path -Leaf $repositoryRoot
        $OutputPath = Join-Path ([System.IO.Path]::GetTempPath()) ("git-ai-audit\$repositoryName-$stamp")
    }
    $outputRoot = [System.IO.Path]::GetFullPath($OutputPath)

    if (Test-Path -LiteralPath $outputRoot) {
        throw "Output path already exists: $outputRoot. Choose a new -OutputPath."
    }

    $null = New-Item -ItemType Directory -Path $outputRoot -Force
    $notesDirectory = Join-Path $outputRoot 'notes'
    $metadataDirectory = Join-Path $outputRoot 'metadata'
    $commandsDirectory = Join-Path $outputRoot 'commands'
    $null = New-Item -ItemType Directory -Path $notesDirectory -Force
    $null = New-Item -ItemType Directory -Path $metadataDirectory -Force
    $null = New-Item -ItemType Directory -Path $commandsDirectory -Force

    $captureCommands = [ordered]@{
        'git-version.txt'          = @('--version')
        'git-ai-version.txt'       = @('ai', '--version')
        'git-ai-help.txt'          = @('ai', '--help')
        'git-ai-stats-help.txt'    = @('ai', 'stats', '--help')
        'git-ai-blame-help.txt'    = @('ai', 'blame', '--help')
        'git-status.txt'           = @('status', '--short', '--branch')
        'git-config.txt'           = @('config', '--show-origin', '--list')
        'git-remotes.txt'          = @('remote', '-v')
        'git-note-refs.txt'        = @('for-each-ref', '--format=%(refname) %(objectname)', 'refs/notes/')
        'git-head.txt'             = @('log', '-1', '--date=iso-strict', '--format=%H%n%P%n%an <%ae>%n%ad%n%s')
    }

    foreach ($name in $captureCommands.Keys) {
        $result = Invoke-GitCapture $captureCommands[$name]
        $text = $result.Text
        if ($name -eq 'git-config.txt' -or $name -eq 'git-remotes.txt' -or $name -eq 'git-head.txt') {
            $text = ConvertTo-SafeText $text
        }
        Write-Utf8File -Path (Join-Path $commandsDirectory $name) -Text ("exit_code=$($result.ExitCode)" + [Environment]::NewLine + $text)
    }

    $noteRefResult = Invoke-GitCapture @('for-each-ref', '--format=%(refname)', 'refs/notes/')
    $noteRefs = @($noteRefResult.Text -split "`r?`n" | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })

    $noteIndexPath = Join-Path $metadataDirectory 'note-index.jsonl'
    $commitIndexPath = Join-Path $metadataDirectory 'commit-index.jsonl'
    $schemaIndexPath = Join-Path $metadataDirectory 'schema-summary.json'
    New-Item -ItemType File -Path $noteIndexPath -Force | Out-Null
    New-Item -ItemType File -Path $commitIndexPath -Force | Out-Null

    $fieldCounts = @{}
    $noteCount = 0
    $truncated = $false
    $seenCommits = New-Object 'System.Collections.Generic.HashSet[string]'
    $noteRefSummary = New-Object System.Collections.Generic.List[object]

    foreach ($noteRef in $noteRefs) {
        $listResult = Invoke-GitCapture @('notes', "--ref=$noteRef", 'list')
        $entries = @($listResult.Text -split "`r?`n" | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
        $noteRefSummary.Add([pscustomobject]@{
            ref = $noteRef
            list_exit_code = $listResult.ExitCode
            mapped_note_count = $entries.Count
        })

        foreach ($entry in $entries) {
            if ($MaxNotes -gt 0 -and $noteCount -ge $MaxNotes) {
                $truncated = $true
                break
            }

            $parts = $entry -split '\s+', 2
            if ($parts.Count -ne 2) {
                Add-JsonLine -Path $noteIndexPath -Value ([ordered]@{
                    note_ref = $noteRef
                    parse_error = 'Unexpected output from git notes list'
                    raw_entry = $entry
                })
                continue
            }

            $noteObject = $parts[0]
            $commitSha = $parts[1]
            $noteResult = Invoke-GitCapture @('notes', "--ref=$noteRef", 'show', $commitSha)
            $rawText = $noteResult.Text
            $safeText = ConvertTo-SafeText $rawText

            $refDirectory = Join-Path $notesDirectory (Get-SafeFileName $noteRef)
            $noteFile = Join-Path $refDirectory ("$commitSha.txt")
            Write-Utf8File -Path $noteFile -Text $safeText

            $jsonKeys = @(Get-JsonKeyNames $safeText)
            foreach ($key in $jsonKeys) {
                if ($fieldCounts.ContainsKey($key)) {
                    $fieldCounts[$key]++
                }
                else {
                    $fieldCounts[$key] = 1
                }
            }

            $noteInfo = [ordered]@{
                note_ref = $noteRef
                target_commit = $commitSha
                note_object = $noteObject
                note_show_exit_code = $noteResult.ExitCode
                raw_character_count = $rawText.Length
                raw_utf8_sha256 = Get-Sha256 $rawText
                exported_file = (Join-Path (Split-Path -Leaf $notesDirectory) ((Get-SafeFileName $noteRef) + '/' + $commitSha + '.txt')).Replace('\\', '/')
                json_keys_detected = $jsonKeys | Select-Object -Unique
            }
            Add-JsonLine -Path $noteIndexPath -Value $noteInfo
            $noteCount++

            if ($seenCommits.Add($commitSha)) {
                $commitResult = Invoke-GitCapture @('show', '-s', '--date=iso-strict', '--format=%H%x1f%P%x1f%an%x1f%ae%x1f%ad%x1f%s%x1f%T', $commitSha)
                $changeResult = Invoke-GitCapture @('diff-tree', '--no-commit-id', '--name-status', '-r', $commitSha)
                $commitParts = $commitResult.Text -split [char]0x1f, 7
                Add-JsonLine -Path $commitIndexPath -Value ([ordered]@{
                    commit = $commitSha
                    parents = if ($commitParts.Count -gt 1) { $commitParts[1] } else { '' }
                    author_name = if ($commitParts.Count -gt 2) { ConvertTo-SafeText $commitParts[2] } else { '' }
                    author_email = if ($commitParts.Count -gt 3) { ConvertTo-SafeText $commitParts[3] } else { '' }
                    authored_at = if ($commitParts.Count -gt 4) { $commitParts[4] } else { '' }
                    subject = if ($commitParts.Count -gt 5) { ConvertTo-SafeText $commitParts[5] } else { '' }
                    tree = if ($commitParts.Count -gt 6) { $commitParts[6] } else { '' }
                    changed_paths = @($changeResult.Text -split "`r?`n" | Where-Object { $_ })
                })
            }
        }

        if ($truncated) { break }
    }

    $schemaSummary = [ordered]@{
        generated_at = Get-Date -Format 'o'
        note_refs = $noteRefSummary
        exported_note_count = $noteCount
        max_notes = $MaxNotes
        truncated = $truncated
        json_key_frequency = [ordered]@{}
    }
    foreach ($key in ($fieldCounts.Keys | Sort-Object)) {
        $schemaSummary.json_key_frequency[$key] = $fieldCounts[$key]
    }
    $schemaSummary | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $schemaIndexPath -Encoding utf8

    $readme = @"
Git AI Note compatibility-audit export
======================================

This folder was generated read-only from:
  $repositoryRoot

Contents
--------
commands/                 Git, git-ai, config, ref, and help output
metadata/note-index.jsonl One record per exported Note
metadata/commit-index.jsonl Commit metadata and changed-path summaries
metadata/schema-summary.json Fields detected in JSON-like Note metadata
notes/                    One exported raw Note per note ref and target commit

Privacy
-------
IncludeSensitive: $($IncludeSensitive.IsPresent)
When false, email addresses, HTTP(S) URLs, and obvious credential values were
replaced with placeholders. Raw Git object IDs remain so duplicate Notes and
format differences can still be compared.

Coverage
--------
Exported Notes: $noteCount
MaxNotes: $MaxNotes  (0 means all Notes)
Truncated: $truncated

To share for review, zip this directory. Do not include the repository itself.
"@
    Write-Utf8File -Path (Join-Path $outputRoot 'README.txt') -Text $readme

    Write-Host "Git AI audit export completed."
    Write-Host "Output: $outputRoot"
    Write-Host "Notes exported: $noteCount"
    if ($truncated) {
        Write-Warning "Export stopped at -MaxNotes $MaxNotes. Re-run with -MaxNotes 0 for all Notes."
    }
}
finally {
    Pop-Location
}



