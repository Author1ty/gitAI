<#
.SYNOPSIS
  End-to-end regression for the multi-department Git AI dashboard fixture.
.DESCRIPTION
  Uses six local Git repositories with 24 historical commits (2026-08-12 through
  2026-08-15), three commit authors, four projects and four repository groups.
  Run this only against an isolated database because it creates catalog records,
  sync jobs and one department administrator account.
#>
[CmdletBinding()]
param(
    [string] $BaseUrl = 'http://127.0.0.1:18081',
    [string] $FixtureRoot = (Join-Path $PSScriptRoot '..\test-repos\git-ai-multi-dimension-fixture'),
    [int] $SyncTimeoutSeconds = 180
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$BaseUrl = $BaseUrl.TrimEnd('/')
$FixtureRoot = [IO.Path]::GetFullPath($FixtureRoot)
$results = [Collections.Generic.List[object]]::new()

function Add-Result([string] $Name, [string] $Status, [string] $Detail = '') {
    $results.Add([pscustomobject]@{ Name = $Name; Status = $Status; Detail = $Detail })
    Write-Host "[$Status] $Name$(if ($Detail) { ': ' + $Detail })"
}
function Assert-Condition([string] $Name, [bool] $Condition, [string] $Detail = '') {
    Add-Result $Name $(if ($Condition) { 'PASS' } else { 'FAIL' }) $Detail
    return $Condition
}
function Invoke-Api {
    param([ValidateSet('GET','POST','PUT')] [string] $Method,[string] $Path,[object] $Body,[string] $Token)
    $headers = @{}
    if ($Token) { $headers.Authorization = "Bearer $Token" }
    $content = $null
    try {
        if ($PSBoundParameters.ContainsKey('Body')) {
            $json = $Body | ConvertTo-Json -Depth 10 -Compress
            $response = Invoke-WebRequest -UseBasicParsing -Uri "$BaseUrl$Path" -Method $Method -Headers $headers -Body $json -ContentType 'application/json; charset=utf-8' -ErrorAction Stop
        } else {
            $response = Invoke-WebRequest -UseBasicParsing -Uri "$BaseUrl$Path" -Method $Method -Headers $headers -ErrorAction Stop
        }
        $status = [int]$response.StatusCode; $content = [string]$response.Content
    } catch {
        if ($null -eq $_.Exception.Response) { throw }
        $status = [int]$_.Exception.Response.StatusCode
        $reader = [IO.StreamReader]::new($_.Exception.Response.GetResponseStream())
        try { $content = $reader.ReadToEnd() } finally { $reader.Dispose(); $_.Exception.Response.Dispose() }
    }
    $payload = $null
    if ($content) { try { $payload = $content | ConvertFrom-Json } catch { $payload = $content } }
    [pscustomobject]@{ StatusCode=$status; Body=$payload; Raw=$content }
}
function Assert-Status([string] $Name,$Response,[int[]] $Expected) {
    if ($Response.StatusCode -in $Expected) { Add-Result $Name 'PASS' "HTTP $($Response.StatusCode)"; return $true }
    Add-Result $Name 'FAIL' "expected $($Expected -join '/') received $($Response.StatusCode): $($Response.Raw)"
    return $false
}
function Login([string] $Username) {
    $response = Invoke-Api POST '/api/auth/login' @{ username=$Username; password='test' }
    if (-not (Assert-Status "login $Username" $response @(200))) { return $null }
    if (-not $response.Body.token) { Add-Result "token $Username" 'FAIL' 'missing token'; return $null }
    Add-Result "token $Username" 'PASS'
    return [string]$response.Body.token
}
function Wait-ForJob([long] $JobId,[string] $Token) {
    $deadline = (Get-Date).AddSeconds($SyncTimeoutSeconds)
    do {
        Start-Sleep -Milliseconds 600
        $response = Invoke-Api GET "/api/sync-jobs/$JobId" $null $Token
        if ($response.StatusCode -ne 200) { return $response }
        $state = $response.Body
    } while ($state.status -in @('QUEUED','RUNNING') -and (Get-Date) -lt $deadline)
    return [pscustomobject]@{ StatusCode=200; Body=$state; Raw='' }
}

$manifestPath = Join-Path $FixtureRoot 'fixture-manifest.json'
if (-not (Test-Path $manifestPath)) { throw "Fixture manifest is missing: $manifestPath" }
$manifest = Get-Content -Raw $manifestPath | ConvertFrom-Json
Assert-Condition 'fixture has six repositories' ($manifest.repositories.Count -eq 6) "count=$($manifest.repositories.Count)" | Out-Null
Assert-Condition 'fixture has four dates including 2026-08-15' (($manifest.expectedDays -join ',') -eq '2026-08-12,2026-08-13,2026-08-14,2026-08-15') ($manifest.expectedDays -join ',') | Out-Null
Assert-Condition 'fixture has three authors' ((($manifest.expectedAuthors | Sort-Object) -join ',') -eq 'Alice,Bob,Carol') 'Alice,Bob,Carol' | Out-Null

$superToken = Login 'superadmin'
if (-not $superToken) { throw 'Unable to obtain super administrator token.' }
Assert-Status 'health endpoint' (Invoke-Api GET '/api/health') @(200) | Out-Null

$departmentIds = @{}; $projectIds = @{}; $groupIds = @{}; $repositoryIds = @{}
$departmentNames = @($manifest.repositories.department | Sort-Object -Unique)
foreach ($departmentName in $departmentNames) {
    $created = Invoke-Api POST '/api/catalog/departments' @{ name=$departmentName; description='multi-dimension E2E fixture' } $superToken
    if (Assert-Status "create department $departmentName" $created @(200)) { $departmentIds[$departmentName] = [long]$created.Body.id }
}
$projectPairs = @($manifest.repositories | ForEach-Object { "$($_.department)|$($_.project)" } | Sort-Object -Unique)
foreach ($pair in $projectPairs) {
    $parts = $pair -split '\|',2; $created = Invoke-Api POST '/api/catalog/projects' @{ departmentId=$departmentIds[$parts[0]]; name=$parts[1]; description='multi-dimension E2E fixture' } $superToken
    if (Assert-Status "create project $($parts[1])" $created @(200)) { $projectIds[$pair] = [long]$created.Body.id }
}
$groupPairs = @($manifest.repositories | ForEach-Object { "$($_.department)|$($_.project)|$($_.group)" } | Sort-Object -Unique)
foreach ($pair in $groupPairs) {
    $parts = $pair -split '\|',3; $projectKey = "$($parts[0])|$($parts[1])"; $created = Invoke-Api POST '/api/catalog/groups' @{ projectId=$projectIds[$projectKey]; name=$parts[2] } $superToken
    if (Assert-Status "create group $($parts[2])" $created @(200)) { $groupIds[$pair] = [long]$created.Body.id }
}
foreach ($entry in $manifest.repositories) {
    $projectKey = "$($entry.department)|$($entry.project)"; $groupKey = "$($entry.department)|$($entry.project)|$($entry.group)"
    $created = Invoke-Api POST '/api/catalog/repositories' @{ projectId=$projectIds[$projectKey]; groupId=$groupIds[$groupKey]; name=$entry.name; gitUrl=$entry.gitUrl; defaultBranch=$entry.defaultBranch; mirrorPath='' } $superToken
    if (Assert-Status "create repository $($entry.name)" $created @(200)) { $repositoryIds[$entry.name] = [long]$created.Body.id }
}

$jobs = @()
foreach ($entry in $manifest.repositories) {
    $job = Invoke-Api POST "/api/repositories/$($repositoryIds[$entry.name])/sync-jobs" $null $superToken
    if (Assert-Status "queue sync $($entry.name)" $job @(200)) { $jobs += [pscustomobject]@{ Name=$entry.name; Id=[long]$job.Body.id } }
}
foreach ($job in $jobs) {
    $final = Wait-ForJob $job.Id $superToken
    Assert-Status "read sync $($job.Name)" $final @(200) | Out-Null
    Assert-Condition "sync succeeds $($job.Name)" ($final.Body.status -eq 'SUCCESS') "status=$($final.Body.status); error=$($final.Body.error)" | Out-Null
    Assert-Condition "history complete $($job.Name)" ([bool]$final.Body.historyComplete) "offset=$($final.Body.historyOffset)" | Out-Null
}

$filters = Invoke-Api GET '/api/filters' $null $superToken
if (Assert-Status 'read filters' $filters @(200)) {
    Assert-Condition 'two fixture departments are selectable' (@($filters.Body.departments | Where-Object { $_.name -in $departmentNames }).Count -eq 2) '' | Out-Null
    Assert-Condition 'four fixture projects are selectable' (@($filters.Body.projects | Where-Object { $_.name -in @($manifest.repositories.project | Sort-Object -Unique) }).Count -eq 4) '' | Out-Null
    Assert-Condition 'four fixture groups are selectable' (@($filters.Body.groups | Where-Object { $_.name -in @($manifest.repositories.group | Sort-Object -Unique) }).Count -eq 4) '' | Out-Null
    Assert-Condition 'six fixture repositories are selectable' (@($filters.Body.repositories | Where-Object { $_.name -in @($manifest.repositories.name) }).Count -eq 6) '' | Out-Null
}

$all = Invoke-Api GET '/api/dashboard' $null $superToken
if (Assert-Status 'read unfiltered dashboard' $all @(200)) {
    Assert-Condition 'dashboard contains six repositories' ([long]$all.Body.summary.repositories -eq 6) "actual=$($all.Body.summary.repositories)" | Out-Null
    Assert-Condition 'dashboard contains 24 commits' ([long]$all.Body.summary.commits -eq 24) "actual=$($all.Body.summary.commits)" | Out-Null
    Assert-Condition 'all attribution categories are represented' (([long]$all.Body.summary.aiLines -gt 0) -and ([long]$all.Body.summary.humanLines -gt 0) -and ([long]$all.Body.summary.mixedLines -gt 0) -and ([long]$all.Body.summary.unknownLines -gt 0)) ($all.Body.summary | ConvertTo-Json -Compress) | Out-Null
    Assert-Condition 'trend includes all four fixture dates' ((@($all.Body.trend.date | ForEach-Object { [string]$_ } | Sort-Object) -join ',') -eq ($manifest.expectedDays -join ',')) ($manifest.expectedDays -join ',') | Out-Null
    Assert-Condition 'project panorama has four projects' (@($all.Body.projectPanorama | Where-Object { $_.name -in @($manifest.repositories.project | Sort-Object -Unique) }).Count -eq 4) '' | Out-Null
    Assert-Condition 'group panorama has four groups' (@($all.Body.groupPanorama | Where-Object { $_.name -in @($manifest.repositories.group | Sort-Object -Unique) }).Count -eq 4) '' | Out-Null
    Assert-Condition 'personal ranking includes Alice Bob and Carol' ((@($all.Body.personRankings.byAiLines.author) -contains 'Alice') -and (@($all.Body.personRankings.byAiLines.author) -contains 'Bob') -and (@($all.Body.personRankings.byAiLines.author) -contains 'Carol')) '' | Out-Null
    Assert-Condition 'multiple AI tools are displayed' ((@($all.Body.agents.agent) -contains 'cursor') -and (@($all.Body.agents.agent) -contains 'copilot') -and (@($all.Body.agents.agent) -contains 'aider')) (@($all.Body.agents | ConvertTo-Json -Compress)) | Out-Null
}

$businessId = $departmentIds['Business Engineering']; $commerceId = $projectIds['Business Engineering|Commerce Platform']; $coreId = $groupIds['Business Engineering|Commerce Platform|Core Services']; $commerceRepoId = $repositoryIds['commerce-api']
$business = Invoke-Api GET "/api/dashboard?departmentId=$businessId" $null $superToken
if (Assert-Status 'filter dashboard by department' $business @(200)) { Assert-Condition 'department scope isolates four repositories and 16 commits' (([long]$business.Body.summary.repositories -eq 4) -and ([long]$business.Body.summary.commits -eq 16)) ($business.Body.summary | ConvertTo-Json -Compress) | Out-Null }
$project = Invoke-Api GET "/api/dashboard?projectId=$commerceId" $null $superToken
if (Assert-Status 'filter dashboard by project' $project @(200)) { Assert-Condition 'project scope isolates two repositories and eight commits' (([long]$project.Body.summary.repositories -eq 2) -and ([long]$project.Body.summary.commits -eq 8)) ($project.Body.summary | ConvertTo-Json -Compress) | Out-Null }
$group = Invoke-Api GET "/api/dashboard?groupId=$coreId" $null $superToken
if (Assert-Status 'filter dashboard by group' $group @(200)) { Assert-Condition 'group scope matches its two repositories' (([long]$group.Body.summary.repositories -eq 2) -and ([long]$group.Body.summary.commits -eq 8)) ($group.Body.summary | ConvertTo-Json -Compress) | Out-Null }
$repository = Invoke-Api GET "/api/dashboard?repositoryId=$commerceRepoId" $null $superToken
if (Assert-Status 'filter dashboard by repository' $repository @(200)) { Assert-Condition 'repository scope contains four commits' (([long]$repository.Body.summary.repositories -eq 1) -and ([long]$repository.Body.summary.commits -eq 4)) ($repository.Body.summary | ConvertTo-Json -Compress) | Out-Null }
$yesterday = Invoke-Api GET '/api/dashboard?from=2026-08-15&to=2026-08-15' $null $superToken
if (Assert-Status 'filter dashboard by 2026-08-15' $yesterday @(200)) { Assert-Condition 'yesterday has six commits and one trend point' (([long]$yesterday.Body.summary.commits -eq 6) -and (@($yesterday.Body.trend).Count -eq 1) -and ([string]$yesterday.Body.trend[0].date -eq '2026-08-15')) ($yesterday.Body.summary | ConvertTo-Json -Compress) | Out-Null }

$suffix = [Guid]::NewGuid().ToString('N').Substring(0,8); $departmentAdminUsername = "multidept$suffix"
$departmentAdmin = Invoke-Api POST '/api/operations/users' @{ username=$departmentAdminUsername; displayName='Multi Dimension Department Admin'; role='DEPARTMENT_ADMIN'; departmentId=$businessId; enabled=$true } $superToken
if (Assert-Status 'create business department administrator' $departmentAdmin @(200)) {
    $departmentToken = Login $departmentAdminUsername
    if ($departmentToken) {
        $scopedFilters = Invoke-Api GET '/api/filters' $null $departmentToken
        if (Assert-Status 'department administrator reads scoped filters' $scopedFilters @(200)) { Assert-Condition 'department administrator sees only Business Engineering' ((@($scopedFilters.Body.departments).Count -eq 1) -and ($scopedFilters.Body.departments[0].id -eq $businessId)) '' | Out-Null }
        $scopedDashboard = Invoke-Api GET '/api/dashboard' $null $departmentToken
        if (Assert-Status 'department administrator reads scoped dashboard' $scopedDashboard @(200)) { Assert-Condition 'department administrator sees 16 commits only' ([long]$scopedDashboard.Body.summary.commits -eq 16) '' | Out-Null }
        Assert-Status 'department administrator cannot create a department' (Invoke-Api POST '/api/catalog/departments' @{ name="forbidden-$suffix"; description='must fail' } $departmentToken) @(403) | Out-Null
    }
}
$viewerToken = Login 'viewer'
if ($viewerToken) { Assert-Status 'viewer cannot trigger synchronization' (Invoke-Api POST "/api/repositories/$commerceRepoId/sync-jobs" $null $viewerToken) @(403) | Out-Null }
Assert-Status 'operations overview after synchronization' (Invoke-Api GET '/api/operations/overview' $null $superToken) @(200) | Out-Null
Assert-Status 'audit log is readable after write flows' (Invoke-Api GET '/api/operations/audit-logs?limit=300' $null $superToken) @(200) | Out-Null

$passed=@($results | Where-Object Status -eq 'PASS').Count; $failed=@($results | Where-Object Status -eq 'FAIL').Count
Write-Host "Summary: PASS=$passed FAIL=$failed"
$results | Format-Table -AutoSize
if ($failed -gt 0) { exit 1 }