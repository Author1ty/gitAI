<#
.SYNOPSIS
  Builds deterministic multi-dimensional Git AI fixture repositories.
.DESCRIPTION
  Creates six local bare remotes. Every remote has four business commits owned by
  several people over four dates, with Git AI Notes covering AI, human, mixed and
  untracked attribution. A manifest JSON is emitted for HTTP/browser regression.
#>
[CmdletBinding()]
param(
    [string] $OutputRoot = (Join-Path $PSScriptRoot '..\test-repos\git-ai-multi-dimension-fixture'),
    [switch] $Force
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$OutputRoot = [IO.Path]::GetFullPath($OutputRoot)
if (Test-Path $OutputRoot) {
    if (-not $Force) { throw "Fixture directory already exists: $OutputRoot. Use -Force to recreate it." }
    Remove-Item -LiteralPath $OutputRoot -Recurse -Force
}
New-Item -ItemType Directory -Path $OutputRoot -Force | Out-Null
$workRoot = Join-Path $OutputRoot 'work'
$remoteRoot = Join-Path $OutputRoot 'remotes'
New-Item -ItemType Directory -Path $workRoot,$remoteRoot -Force | Out-Null

function Invoke-Git {
    param([string] $Repository,[string[]] $Arguments,[hashtable] $Environment = @{})
    $old = @{}
    try {
        foreach ($key in $Environment.Keys) { $old[$key] = [Environment]::GetEnvironmentVariable($key); [Environment]::SetEnvironmentVariable($key, [string]$Environment[$key]) }
        $savedErrorActionPreference = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
        $output = & git -C $Repository @Arguments 2>&1
        $ErrorActionPreference = $savedErrorActionPreference
        if ($LASTEXITCODE -ne 0) { throw "git -C $Repository $($Arguments -join ' ') failed: $($output -join [Environment]::NewLine)" }
        return ($output -join [Environment]::NewLine).Trim()
    } finally {
        foreach ($key in $Environment.Keys) { [Environment]::SetEnvironmentVariable($key, $old[$key]) }
    }
}
function Add-FixtureCommit {
    param(
        [string] $Repository,[string] $File,[string] $AuthorName,[string] $AuthorEmail,
        [string] $Date,[ValidateSet('ai','human','mixed','unknown')] [string] $Attribution,
        [string] $Tool,[string] $Model,[int] $Sequence
    )
    $path = Join-Path $Repository $File
    New-Item -ItemType Directory -Path (Split-Path $path) -Force | Out-Null
    $lines = 1..10 | ForEach-Object { "export const item$Sequence`_$($_) = '$AuthorName-$Attribution-$($_)';" }
    [IO.File]::WriteAllLines($path, $lines, [Text.UTF8Encoding]::new($false))
    Invoke-Git -Repository $Repository -Arguments @('add','--',$File) | Out-Null
    $oldAuthorDate = $env:GIT_AUTHOR_DATE; $oldCommitterDate = $env:GIT_COMMITTER_DATE
    $oldAuthorName = $env:GIT_AUTHOR_NAME; $oldAuthorEmail = $env:GIT_AUTHOR_EMAIL
    $oldCommitterName = $env:GIT_COMMITTER_NAME; $oldCommitterEmail = $env:GIT_COMMITTER_EMAIL
    try {
        $env:GIT_AUTHOR_DATE=$Date; $env:GIT_COMMITTER_DATE=$Date
        $env:GIT_AUTHOR_NAME=$AuthorName; $env:GIT_AUTHOR_EMAIL=$AuthorEmail
        $env:GIT_COMMITTER_NAME=$AuthorName; $env:GIT_COMMITTER_EMAIL=$AuthorEmail
        $commitOutput = & git -C $Repository commit -m "fixture: $Attribution attribution for $AuthorName" 2>&1
        if ($LASTEXITCODE -ne 0) { throw "git commit failed: $($commitOutput -join [Environment]::NewLine)" }
    } finally {
        $env:GIT_AUTHOR_DATE=$oldAuthorDate; $env:GIT_COMMITTER_DATE=$oldCommitterDate
        $env:GIT_AUTHOR_NAME=$oldAuthorName; $env:GIT_AUTHOR_EMAIL=$oldAuthorEmail
        $env:GIT_COMMITTER_NAME=$oldCommitterName; $env:GIT_COMMITTER_EMAIL=$oldCommitterEmail
    }
    $sha = Invoke-Git -Repository $Repository -Arguments @('rev-parse','HEAD')
    # Codex can auto-attach an AI Note on git commit. An unknown fixture must explicitly remove it when present.
    if ($Attribution -eq 'unknown') {
        $existingNote = Invoke-Git -Repository $Repository -Arguments @('notes','--ref=ai','list') | Where-Object { $_ -match ("\s" + [regex]::Escape($sha) + "$") }
        if (-not [string]::IsNullOrWhiteSpace(($existingNote -join ''))) {
            Invoke-Git -Repository $Repository -Arguments @('notes','--ref=ai','remove',$sha) | Out-Null
        }
        return $sha
    }
    $session = "s_fixture$Sequence`::t_trace$Sequence"
    $human = "h_fixture$Sequence"
    $ranges = switch ($Attribution) {
        'ai' { "  $session 1-6" }
        'human' { "  $human 1-7" }
        'mixed' { "  $session 1-6`n  $human 5-10" }
    }
    $metadata = [ordered]@{ schema_version='authorship/3.0.0'; git_ai_version='1.6.22'; base_commit_sha=$sha; prompts=[ordered]@{} }
    if ($Attribution -in @('ai','mixed')) {
        $metadata.sessions = [ordered]@{ ("s_fixture$Sequence") = [ordered]@{ agent_id=[ordered]@{ tool=$Tool; id="fixture-session-$Sequence"; model=$Model }; human_author="$AuthorName <$AuthorEmail>" } }
    }
    if ($Attribution -in @('human','mixed')) {
        $metadata.humans = [ordered]@{ ("h_fixture$Sequence") = [ordered]@{ author="$AuthorName <$AuthorEmail>" } }
    }
    $note = "$File`n$ranges`n---`n$($metadata | ConvertTo-Json -Depth 8 -Compress)"
    $noteFile = Join-Path $Repository '.fixture-note.txt'
    [IO.File]::WriteAllText($noteFile,$note,[Text.UTF8Encoding]::new($false))
    Invoke-Git -Repository $Repository -Arguments @('notes','--ref=ai','add','-f','-F',$noteFile,$sha) | Out-Null
    Remove-Item -LiteralPath $noteFile -Force
    return $sha
}

function Set-FixtureNote {
    param(
        [string] $Repository,[string] $CommitSha,[string] $File,[string] $AuthorName,[string] $AuthorEmail,
        [ValidateSet('ai','human','mixed','unknown')] [string] $Attribution,
        [string] $Tool,[string] $Model,[int] $Sequence
    )
    # Commit instrumentation can write an automatic Codex Note shortly after a commit. This final pass is authoritative.
    if ($Attribution -eq 'unknown') {
        $existingNote = Invoke-Git -Repository $Repository -Arguments @('notes','--ref=ai','list') | Where-Object { $_ -match ("\s" + [regex]::Escape($CommitSha) + "$") }
        if (-not [string]::IsNullOrWhiteSpace(($existingNote -join ''))) {
            Invoke-Git -Repository $Repository -Arguments @('notes','--ref=ai','remove',$CommitSha) | Out-Null
        }
        return
    }
    $session = "s_fixture$Sequence" + '::' + "t_trace$Sequence"
    $human = "h_fixture$Sequence"
    $lineBreak = [Environment]::NewLine
    $ranges = switch ($Attribution) {
        'ai' { "  $session 1-6" }
        'human' { "  $human 1-7" }
        'mixed' { "  $session 1-6" + $lineBreak + "  $human 5-10" }
    }
    $metadata = [ordered]@{ schema_version='authorship/3.0.0'; git_ai_version='1.6.22'; base_commit_sha=$CommitSha; prompts=[ordered]@{} }
    if ($Attribution -in @('ai','mixed')) {
        $metadata.sessions = [ordered]@{ ("s_fixture$Sequence") = [ordered]@{ agent_id=[ordered]@{ tool=$Tool; id="fixture-session-$Sequence"; model=$Model }; human_author="$AuthorName <$AuthorEmail>" } }
    }
    if ($Attribution -in @('human','mixed')) {
        $metadata.humans = [ordered]@{ ("h_fixture$Sequence") = [ordered]@{ author="$AuthorName <$AuthorEmail>" } }
    }
    $noteFile = Join-Path $Repository '.fixture-note-final.txt'
    $note = $File + $lineBreak + $ranges + $lineBreak + '---' + $lineBreak + ($metadata | ConvertTo-Json -Depth 8 -Compress)
    [IO.File]::WriteAllText($noteFile,$note,[Text.UTF8Encoding]::new($false))
    try {
        Invoke-Git -Repository $Repository -Arguments @('notes','--ref=ai','add','-f','-F',$noteFile,$CommitSha) | Out-Null
    } finally {
        Remove-Item -LiteralPath $noteFile -Force -ErrorAction SilentlyContinue
    }
}

$definitions = @(
    [pscustomobject]@{ id='commerce-api'; department='Business Engineering'; project='Commerce Platform'; group='Core Services'; name='commerce-api'; authors=@('Alice','Bob','Carol','Alice'); dates=@('2026-08-12T09:10:00+08:00','2026-08-13T10:20:00+08:00','2026-08-14T11:30:00+08:00','2026-08-15T14:40:00+08:00'); kinds=@('ai','human','mixed','unknown'); tool='cursor'; model='gpt-4.1' },
    [pscustomobject]@{ id='payment-worker'; department='Business Engineering'; project='Commerce Platform'; group='Core Services'; name='payment-worker'; authors=@('Bob','Carol','Alice','Bob'); dates=@('2026-08-12T15:00:00+08:00','2026-08-13T16:00:00+08:00','2026-08-14T17:00:00+08:00','2026-08-15T18:00:00+08:00'); kinds=@('human','ai','unknown','mixed'); tool='copilot'; model='gpt-4.1' },
    [pscustomobject]@{ id='merchant-web'; department='Business Engineering'; project='Merchant Workspace'; group='Frontend Apps'; name='merchant-web'; authors=@('Carol','Alice','Bob','Carol'); dates=@('2026-08-12T08:00:00+08:00','2026-08-13T09:00:00+08:00','2026-08-14T10:00:00+08:00','2026-08-15T11:00:00+08:00'); kinds=@('mixed','unknown','ai','human'); tool='cursor'; model='claude-3.7' },
    [pscustomobject]@{ id='customer-portal'; department='Business Engineering'; project='Merchant Workspace'; group='Frontend Apps'; name='customer-portal'; authors=@('Alice','Carol','Bob','Alice'); dates=@('2026-08-12T12:00:00+08:00','2026-08-13T13:00:00+08:00','2026-08-14T14:00:00+08:00','2026-08-15T15:00:00+08:00'); kinds=@('unknown','mixed','human','ai'); tool='copilot'; model='gpt-4o' },
    [pscustomobject]@{ id='quality-gateway'; department='Platform Engineering'; project='Quality Platform'; group='Engineering Productivity'; name='quality-gateway'; authors=@('Bob','Alice','Carol','Bob'); dates=@('2026-08-12T16:00:00+08:00','2026-08-13T17:00:00+08:00','2026-08-14T18:00:00+08:00','2026-08-15T19:00:00+08:00'); kinds=@('ai','mixed','unknown','human'); tool='aider'; model='deepseek-v3' },
    [pscustomobject]@{ id='observability-cli'; department='Platform Engineering'; project='Observability Platform'; group='Developer Tools'; name='observability-cli'; authors=@('Carol','Bob','Alice','Carol'); dates=@('2026-08-12T07:00:00+08:00','2026-08-13T08:00:00+08:00','2026-08-14T09:00:00+08:00','2026-08-15T10:00:00+08:00'); kinds=@('human','unknown','mixed','ai'); tool='cursor'; model='gpt-4.1' }
)
$manifestRepositories = [Collections.Generic.List[object]]::new()
foreach ($definition in $definitions) {
    $repo = Join-Path $workRoot $definition.id
    New-Item -ItemType Directory -Path $repo -Force | Out-Null
    Invoke-Git -Repository $repo -Arguments @('init','-b','main') | Out-Null
    Invoke-Git -Repository $repo -Arguments @('config','user.name','Fixture Automation') | Out-Null
    Invoke-Git -Repository $repo -Arguments @('config','user.email','fixture-automation@example.invalid') | Out-Null
    $commits = [Collections.Generic.List[string]]::new()
    for ($i=0; $i -lt 4; $i++) {
        $author = $definition.authors[$i]
        $commits.Add((Add-FixtureCommit -Repository $repo -File "src/$($definition.id)-$($i+1).js" -AuthorName $author -AuthorEmail ("$($author.ToLower())@fixture.invalid") -Date $definition.dates[$i] -Attribution $definition.kinds[$i] -Tool $definition.tool -Model $definition.model -Sequence (($manifestRepositories.Count * 10) + $i + 1)))
    }
    $remote = Join-Path $remoteRoot "$($definition.id).git"
    & git init --bare $remote | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Unable to initialize bare remote $remote" }
    Invoke-Git -Repository $repo -Arguments @('remote','add','origin',$remote) | Out-Null
    Invoke-Git -Repository $repo -Arguments @('push','origin','main','refs/notes/ai:refs/notes/ai') | Out-Null
    $manifestRepositories.Add([ordered]@{ id=$definition.id; department=$definition.department; project=$definition.project; group=$definition.group; name=$definition.name; gitUrl=$remote; defaultBranch='main'; commits=$commits })
}
# Give any commit-time Git AI instrumentation a chance to finish before assigning deterministic fixture notes.
Start-Sleep -Seconds 2
for ($definitionIndex = 0; $definitionIndex -lt $definitions.Count; $definitionIndex++) {
    $definition = $definitions[$definitionIndex]
    $repo = Join-Path $workRoot $definition.id
    $commitText = Invoke-Git -Repository $repo -Arguments @('rev-list','--reverse','main')
    $commitShas = @($commitText -split "\r?\n")
    for ($i = 0; $i -lt 4; $i++) {
        $author = $definition.authors[$i]
        Set-FixtureNote -Repository $repo -CommitSha $commitShas[$i] -File "src/$($definition.id)-$($i+1).js" -AuthorName $author -AuthorEmail ("$($author.ToLower())@fixture.invalid") -Attribution $definition.kinds[$i] -Tool $definition.tool -Model $definition.model -Sequence (($definitionIndex * 10) + $i + 1)
    }
    Invoke-Git -Repository $repo -Arguments @('push','origin','refs/notes/ai:refs/notes/ai') | Out-Null
}

$manifest = [ordered]@{ schemaVersion=1; generatedAt='2026-08-16T00:00:00+08:00'; expectedDays=@('2026-08-12','2026-08-13','2026-08-14','2026-08-15'); expectedAuthors=@('Alice','Bob','Carol'); repositories=$manifestRepositories }
$manifestPath = Join-Path $OutputRoot 'fixture-manifest.json'
[IO.File]::WriteAllText($manifestPath,($manifest | ConvertTo-Json -Depth 8),[Text.UTF8Encoding]::new($false))
Write-Host "Created $($manifestRepositories.Count) fixture repositories."
Write-Host "Manifest: $manifestPath"
