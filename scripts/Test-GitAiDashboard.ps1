<#
.SYNOPSIS
  Git AI Insight Dashboard HTTP regression test.
.DESCRIPTION
  Validates authentication, dashboard reads, input validation, roles and, when
  -IncludeWriteTests is specified, catalog, synchronization, schedule and
  operations-management write flows.

  Use an isolated H2/MySQL database for -IncludeWriteTests. The script creates
  test departments, repositories and users; the application currently has no
  delete API for those resources.
#>
[CmdletBinding()]
param(
    [string] $BaseUrl = 'http://127.0.0.1:8080',
    [switch] $IncludeWriteTests,
    [string] $RepositoryUrl = '',
    [int] $SyncTimeoutSeconds = 90
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$BaseUrl = $BaseUrl.TrimEnd('/')
$results = [System.Collections.Generic.List[object]]::new()

function Add-Result([string] $Name, [string] $Status, [string] $Detail = '') {
    $results.Add([pscustomobject]@{ Name = $Name; Status = $Status; Detail = $Detail })
    $mark = switch ($Status) { 'PASS' { '[PASS]' } 'SKIP' { '[SKIP]' } default { '[FAIL]' } }
    $suffix = if ($Detail) { ': ' + $Detail } else { '' }
    Write-Host "$mark $Name$suffix"
}

function Invoke-DashboardApi {
    param(
        [Parameter(Mandatory)] [ValidateSet('GET','POST','PUT')] [string] $Method,
        [Parameter(Mandatory)] [string] $Path,
        [object] $Body,
        [string] $Token
    )
    $headers = @{}
    if ($Token) { $headers['Authorization'] = "Bearer $Token" }
    $uri = "$BaseUrl$Path"
    try {
        if ($PSBoundParameters.ContainsKey('Body')) {
            $json = $Body | ConvertTo-Json -Depth 12 -Compress
            $response = Invoke-WebRequest -UseBasicParsing -Method $Method -Uri $uri -Headers $headers -Body $json -ContentType 'application/json; charset=utf-8' -ErrorAction Stop
        } else {
            $response = Invoke-WebRequest -UseBasicParsing -Method $Method -Uri $uri -Headers $headers -ErrorAction Stop
        }
        $statusCode = [int]$response.StatusCode
        $content = [string]$response.Content
    } catch {
        $webResponse = $_.Exception.Response
        if ($null -eq $webResponse) { throw }
        $statusCode = [int]$webResponse.StatusCode
        $reader = [System.IO.StreamReader]::new($webResponse.GetResponseStream())
        try { $content = $reader.ReadToEnd() } finally { $reader.Dispose(); $webResponse.Dispose() }
    }
    $payload = $null
    if ($content) {
        try { $payload = $content | ConvertFrom-Json } catch { $payload = $content }
    }
    [pscustomobject]@{ StatusCode = $statusCode; Body = $payload; Raw = $content }
}

function Assert-Status([string] $Name, $Response, [int[]] $Expected) {
    if ($Response.StatusCode -in $Expected) { Add-Result $Name 'PASS' "HTTP $($Response.StatusCode)"; return $true }
    Add-Result $Name 'FAIL' "expected $($Expected -join '/') but received $($Response.StatusCode): $($Response.Raw)"
    return $false
}
function Assert-Condition([string] $Name, [bool] $Condition, [string] $Detail = '') {
    if ($Condition) { Add-Result $Name 'PASS' $Detail } else { Add-Result $Name 'FAIL' $Detail }
    return $Condition
}
function Login([string] $Username, [string] $Password = 'test') {
    $response = Invoke-DashboardApi POST '/api/auth/login' @{ username = $Username; password = $Password }
    if (-not (Assert-Status "登录：$Username" $response @(200))) { return $null }
    if (-not $response.Body.token) { Add-Result "登录令牌：$Username" 'FAIL' 'response does not contain token'; return $null }
    Add-Result "登录令牌：$Username" 'PASS'
    return [string]$response.Body.token
}

# Public endpoint and authentication validation.
Assert-Status '健康检查' (Invoke-DashboardApi GET '/api/health') @(200) | Out-Null
Assert-Status '匿名访问看板受保护' (Invoke-DashboardApi GET '/api/dashboard') @(401,403) | Out-Null
Assert-Status '空凭据被拒绝' (Invoke-DashboardApi POST '/api/auth/login' @{ username = ''; password = '' }) @(400) | Out-Null
Assert-Status '未知用户被拒绝' (Invoke-DashboardApi POST '/api/auth/login' @{ username = 'not-a-user'; password = 'test' }) @(401) | Out-Null
$superToken = Login 'superadmin'
$deptToken = Login 'deptadmin'
$viewerToken = Login 'viewer'

if ($superToken) {
    $me = Invoke-DashboardApi GET '/api/auth/me' $null $superToken
    Assert-Status '超级管理员身份查询' $me @(200) | Out-Null
    Assert-Condition '超级管理员角色正确' ($me.Body.role -eq 'SUPER_ADMIN') "actual=$($me.Body.role)" | Out-Null
    $filters = Invoke-DashboardApi GET '/api/filters' $null $superToken
    Assert-Status '筛选条件读取' $filters @(200) | Out-Null
    Assert-Condition '筛选条件包含部门和仓库' ($null -ne $filters.Body.departments -and $null -ne $filters.Body.repositories) | Out-Null
    foreach ($path in @('/api/dashboard','/api/dashboard?from=2020-01-01&to=2030-01-01','/api/sync-jobs?activeOnly=false&limit=30','/api/sync-jobs/status','/api/sync-schedule','/api/operations/overview','/api/operations/users','/api/operations/audit-logs?limit=5')) {
        Assert-Status "超级管理员读取 $path" (Invoke-DashboardApi GET $path $null $superToken) @(200) | Out-Null
    }
    Assert-Status '看板日期范围校验' (Invoke-DashboardApi GET '/api/dashboard?from=2030-01-02&to=2030-01-01' $null $superToken) @(400) | Out-Null
    Assert-Status '看板日期格式校验' (Invoke-DashboardApi GET '/api/dashboard?from=bad-date' $null $superToken) @(400) | Out-Null
    Assert-Status '同步列表 limit 下界约束' (Invoke-DashboardApi GET '/api/sync-jobs?limit=0' $null $superToken) @(200) | Out-Null
    Assert-Status '不存在同步任务返回 404' (Invoke-DashboardApi GET '/api/sync-jobs/999999999' $null $superToken) @(404) | Out-Null
}

if ($deptToken) {
    $me = Invoke-DashboardApi GET '/api/auth/me' $null $deptToken
    Assert-Status '部门管理员身份查询' $me @(200) | Out-Null
    Assert-Condition '部门管理员角色正确' ($me.Body.role -eq 'DEPARTMENT_ADMIN') "actual=$($me.Body.role)" | Out-Null
    foreach ($path in @('/api/filters','/api/dashboard','/api/sync-jobs','/api/sync-jobs/status','/api/sync-schedule')) {
        Assert-Status "部门管理员读取 $path" (Invoke-DashboardApi GET $path $null $deptToken) @(200) | Out-Null
    }
    Assert-Status '部门管理员不可读取运营管理' (Invoke-DashboardApi GET '/api/operations/overview' $null $deptToken) @(403) | Out-Null
    Assert-Status '部门管理员不可修改定时同步' (Invoke-DashboardApi PUT '/api/sync-schedule' @{ enabled = $false; intervalMinutes = 60 } $deptToken) @(403) | Out-Null
}

if ($viewerToken) {
    $me = Invoke-DashboardApi GET '/api/auth/me' $null $viewerToken
    Assert-Status '查看者身份查询' $me @(200) | Out-Null
    Assert-Condition '查看者角色正确' ($me.Body.role -eq 'VIEWER') "actual=$($me.Body.role)" | Out-Null
    foreach ($path in @('/api/filters','/api/dashboard','/api/sync-jobs','/api/sync-jobs/status','/api/sync-schedule')) {
        Assert-Status "查看者读取 $path" (Invoke-DashboardApi GET $path $null $viewerToken) @(200) | Out-Null
    }
    Assert-Status '查看者不可新建部门' (Invoke-DashboardApi POST '/api/catalog/departments' @{ name = 'forbidden'; description = 'forbidden' } $viewerToken) @(403) | Out-Null
    Assert-Status '查看者不可触发同步' (Invoke-DashboardApi POST '/api/sync-jobs' @{} $viewerToken) @(403) | Out-Null
    Assert-Status '查看者不可读取运营管理' (Invoke-DashboardApi GET '/api/operations/overview' $null $viewerToken) @(403) | Out-Null
}

if ($IncludeWriteTests -and $superToken) {
    $suffix = Get-Date -Format 'yyyyMMddHHmmss'; $prefix = "E2E-$suffix"
    $originalSchedule = (Invoke-DashboardApi GET '/api/sync-schedule' $null $superToken).Body
    $scheduleWrite = Invoke-DashboardApi PUT '/api/sync-schedule' @{ enabled = [bool]$originalSchedule.enabled; intervalMinutes = [int]$originalSchedule.intervalMinutes } $superToken
    Assert-Status '定时同步设置保存（原值回写）' $scheduleWrite @(200) | Out-Null
    $department = Invoke-DashboardApi POST '/api/catalog/departments' @{ name = "$prefix Department"; description = 'isolated end-to-end test data' } $superToken
    if (Assert-Status '新建部门' $department @(200)) {
        $departmentId = [long]$department.Body.id
        $project = Invoke-DashboardApi POST '/api/catalog/projects' @{ departmentId = $departmentId; name = "$prefix Project"; description = 'isolated end-to-end test data' } $superToken
        if (Assert-Status '新建项目' $project @(200)) {
            $projectId = [long]$project.Body.id
            $group = Invoke-DashboardApi POST '/api/catalog/groups' @{ projectId = $projectId; name = "$prefix Group" } $superToken
            if (Assert-Status '新建仓库分组' $group @(200)) {
                if ([string]::IsNullOrWhiteSpace($RepositoryUrl)) { Add-Result '新建仓库与真实同步' 'SKIP' 'RepositoryUrl not supplied; all non-sync write flows completed' }
                else {
                    $repository = Invoke-DashboardApi POST '/api/catalog/repositories' @{ projectId = $projectId; groupId = [long]$group.Body.id; name = "$prefix Repository"; gitUrl = $RepositoryUrl; defaultBranch = 'main'; mirrorPath = '' } $superToken
                    if (Assert-Status '新建仓库' $repository @(200)) {
                        $repositoryId = [long]$repository.Body.id
                        Assert-Status '多维筛选看板' (Invoke-DashboardApi GET "/api/dashboard?departmentId=$departmentId&projectId=$projectId&groupId=$($group.Body.id)&repositoryId=$repositoryId" $null $superToken) @(200) | Out-Null
                        $job = Invoke-DashboardApi POST "/api/repositories/$repositoryId/sync-jobs" $null $superToken
                        if (Assert-Status '指定仓库触发同步任务' $job @(200)) {
                            $jobId = [long]$job.Body.id; $deadline = (Get-Date).AddSeconds($SyncTimeoutSeconds); $state = $job.Body; $jobDetail = $null
                            do { Start-Sleep -Milliseconds 800; $jobDetail = Invoke-DashboardApi GET "/api/sync-jobs/$jobId" $null $superToken; if ($jobDetail.StatusCode -ne 200) { break }; $state = $jobDetail.Body } while ($state.status -in @('QUEUED','RUNNING') -and (Get-Date) -lt $deadline)
                            Assert-Status '同步任务详情读取' $jobDetail @(200) | Out-Null
                            Assert-Condition '同步任务终态可见' ($state.status -notin @('QUEUED','RUNNING')) "status=$($state.status)" | Out-Null
                            Assert-Condition '同步任务成功' ($state.status -eq 'SUCCESS') "status=$($state.status); error=$($state.error)" | Out-Null
                        }
                    }
                }
            }
        }
    }
    $user = Invoke-DashboardApi POST '/api/operations/users' @{ username = "e2e$($suffix)"; displayName = "$prefix User"; role = 'VIEWER'; departmentId = $null; enabled = $true } $superToken
    if (Assert-Status '运营管理新建账号' $user @(200)) {
        $userId = [long]$user.Body.id
        $disabled = Invoke-DashboardApi PUT "/api/operations/users/$userId" @{ username = $user.Body.username; displayName = "$prefix User Updated"; role = 'VIEWER'; departmentId = $null; enabled = $false } $superToken
        Assert-Status '运营管理编辑并停用账号' $disabled @(200) | Out-Null
        Assert-Status '已停用账号不能登录' (Invoke-DashboardApi POST '/api/auth/login' @{ username = $user.Body.username; password = 'test' }) @(401,403) | Out-Null
        $enabled = Invoke-DashboardApi PUT "/api/operations/users/$userId" @{ username = $user.Body.username; displayName = "$prefix User Updated"; role = 'VIEWER'; departmentId = $null; enabled = $true } $superToken
        Assert-Status '运营管理启用账号' $enabled @(200) | Out-Null
        $temporaryToken = Login $user.Body.username
        if ($temporaryToken) { Assert-Status '临时账号退出登录' (Invoke-DashboardApi POST '/api/auth/logout' $null $temporaryToken) @(200) | Out-Null; Assert-Status '退出后令牌失效' (Invoke-DashboardApi GET '/api/auth/me' $null $temporaryToken) @(401,403) | Out-Null }
    }
    Assert-Status '审计日志记录写操作' (Invoke-DashboardApi GET '/api/operations/audit-logs?limit=300' $null $superToken) @(200) | Out-Null
} elseif (-not $IncludeWriteTests) { Add-Result '会产生数据的写入链路' 'SKIP' 'run again with -IncludeWriteTests against an isolated database' }

if ($superToken) { Assert-Status '超级管理员退出登录' (Invoke-DashboardApi POST '/api/auth/logout' $null $superToken) @(200) | Out-Null; Assert-Status '超级管理员退出后令牌失效' (Invoke-DashboardApi GET '/api/auth/me' $null $superToken) @(401,403) | Out-Null }
$passed = @($results | Where-Object Status -eq 'PASS').Count; $failed = @($results | Where-Object Status -eq 'FAIL').Count; $skipped = @($results | Where-Object Status -eq 'SKIP').Count
Write-Host "`nSummary: PASS=$passed FAIL=$failed SKIP=$skipped"; $results | Format-Table -AutoSize
if ($failed -gt 0) { exit 1 }




