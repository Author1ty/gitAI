# Git AI Insight Dashboard

基于 **Git AI Note** 的代码归因看板。它以“部门 → 项目 →（可选）仓库分组 → 仓库”的方式组织数据，直观展示 AI、人工、同一行混合归因和未知/未追踪代码，并支持按时间和组织层级筛选。

> 已提供最高权限、部门权限和查看权限三种角色；认证适配器当前为三方认证占位实现，不保存密码。后端由单个 Spring Boot JAR 提供 React 页面和 API，并直接执行服务器上的 `git` 与 `git ai`；不需要 Node.js 服务端，也不使用 Docker。

## 已实现

- React 看板：统计卡片、图表、仓库树、趋势、AI 工具排行、仓库明细、个人排名和项目/仓库分组全景。
- 权限与运营：最高权限、部门权限和查看权限；运营概览、账号授权与审计日志。
- 定时同步：可配置全仓库自动同步开关与执行间隔。
- 过滤：日期范围、部门、项目、仓库分组、仓库。
- Web 管理入口：可新增部门、项目、仓库分组和仓库。
- 后台同步任务：点击“同步 Git AI 数据”只负责入队，Git / clone / 解析在后台 worker 中执行，不占用 HTTP 请求线程。每个仓库同时只会有一个活跃任务，可在“同步任务”中查看进度、错误和历史。
- 同步来源：`refs/notes/ai` 和 `git -C <mirror> ai stats <commit> --json`。
- 持久化：按提交保存 SHA、提交日期、Note 对象 SHA、AI/人工/混合/未知行数及 AI 工具统计；再生成每日聚合。
- 默认本地 H2 数据库和 MySQL 8 Profile；Flyway 自动建表和迁移。
- Linux 原生部署：Nginx + systemd。

## 数据口径

| 指标 | 口径 |
| --- | --- |
| AI 归因 | Git AI 明确标注为 AI 的新增行。 |
| 人工确认 | Git AI 明确标注为人工的新增行。 |
| 混合归因 | 同一个文件行号同时存在 AI 和人工归因时计入；一个提交同时含有不同的 AI 行、人工行，不会重复计为混合行。 |
| 未知 / 未追踪 | Git AI 没有给出足够归因证据的新增行；**不能等同于人工代码**。 |

## 本地直接运行

### 前置条件

- JDK 21
- Node.js 20+
- Git
- Git AI 已安装，并且其 `bin` 目录可被后端进程访问。

本机测试环境的 Git AI 目录是：

```text
C:\Users\96313\.git-ai\bin
```

### 1. 启动后端

在 `D:\code\skill\git-ai\backend`：

```powershell
$jdk = Get-ChildItem 'C:\Program Files\Eclipse Adoptium' -Directory |
  Sort-Object Name -Descending | Select-Object -First 1
$env:JAVA_HOME = $jdk.FullName
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
$env:GIT_AI_BIN_DIR = "$HOME\.git-ai\bin"

.\mvnw.cmd spring-boot:run
```

后端运行在 `http://localhost:8080`。

默认 H2 中预置了真实测试仓库：

```text
D:\code\skill\git-ai\test-repos\git-ai-attribution-sample-remote.git
```

首次启动后，在页面点击 **“同步 Git AI 数据”**将创建后台任务，再打开 **“同步任务”** 查看运行结果。测试仓库会读入 5 个真实提交：纯 AI、纯人工、人机混合提交以及 1 个未追踪提交。

### 2. 启动前端

另开一个终端，在 `D:\code\skill\git-ai\frontend`：

```powershell
npm install
npm run dev
```

打开 `http://localhost:5173`。开发服务会将 `/api` 自动转发到 `8080`。

### 3. 生产构建

```powershell
cd D:\code\skill\git-ai\backend
.\mvnw.cmd package

cd ..\frontend
npm run build
```

后端 JAR：`backend\target\git-ai-dashboard-0.0.1-SNAPSHOT.jar`  
前端静态文件：`frontend\dist`

## API

```text
GET  /api/health
GET  /api/filters
GET  /api/dashboard?from=2026-08-01&to=2026-08-14
POST /api/sync                         # 兼容旧接口，现在仅创建后台任务
POST /api/repositories/{repositoryId}/sync # 兼容旧接口，现在仅创建后台任务
POST /api/sync-jobs                     # body 可选 { "repositoryId": 1 }；空 body 为所有仓库入队
POST /api/repositories/{repositoryId}/sync-jobs
GET  /api/sync-jobs?activeOnly=false&limit=30
GET  /api/sync-jobs/{jobId}
POST /api/sync-jobs/{jobId}/cancel       # 仅能取消 QUEUED 任务，不强杀正在运行的 Git 进程

POST /api/catalog/departments
POST /api/catalog/projects
POST /api/catalog/groups
POST /api/catalog/repositories
```

`POST /api/catalog/repositories` 示例：

```json
{
  "projectId": 1,
  "groupId": 1,
  "name": "payment-api",
  "gitUrl": "ssh://git.example.internal/platform/payment-api.git",
  "defaultBranch": "main"
}
```

`mirrorPath` 可选。留空时，服务端会在 `GIT_AI_MIRROR_ROOT`（默认 `./data/mirrors`）下创建 bare mirror。

## 大仓库同步策略

- 同步任务会持久化到 `sync_jobs`，服务重启后残留的 `RUNNING` 任务会恢复为排队状态。
- 单个仓库同一时间仅允许一个 `QUEUED` / `RUNNING` 任务；重复点击会返回已有任务，不会重复 clone 或解析。
- 首次历史同步按 `GIT_AI_MAX_COMMITS_PER_SYNC` 分批回溯（默认 500 个提交）。本批完成后，界面会显示历史 offset 和是否已完成；以后再创建同步任务即可继续。
- 默认只运行 1 个 Git worker，这是对多年历史和数百万行仓库的保守配置。在测量 CPU、磁盘 IO、网络与 MySQL 容量之前，不建议提高并发。

可选环境变量：

```bash
# 默认值分别为 1、10、500
export GIT_AI_SYNC_WORKER_COUNT=1
export GIT_AI_SYNC_QUEUE_CAPACITY=10
export GIT_AI_MAX_COMMITS_PER_SYNC=500
# 定时同步检查间隔（ISO-8601 时长，例如 PT1M 表示每分钟检查一次）
export GIT_AI_SYNC_SCHEDULE_CHECK_DELAY=PT1M
```

> `CANCELLED` 仅适用于尚未开始的排队任务。为了避免在正在写入 mirror 或 MySQL 时破坏数据，第一版本不会强行终止正在运行的 Git 子进程。

## MySQL 生产配置

```sql
CREATE DATABASE git_ai_dashboard DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE USER 'git_ai_dashboard'@'localhost' IDENTIFIED BY '替换为强密码';
GRANT ALL PRIVILEGES ON git_ai_dashboard.* TO 'git_ai_dashboard'@'localhost';
FLUSH PRIVILEGES;
```

启动参数：

```bash
export SPRING_PROFILES_ACTIVE=mysql
export MYSQL_URL='jdbc:mysql://127.0.0.1:3306/git_ai_dashboard?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai'
export MYSQL_USER=git_ai_dashboard
export MYSQL_PASSWORD='替换为强密码'
export GIT_AI_BIN_DIR='/home/gitai/.git-ai/bin'
export GIT_AI_MIRROR_ROOT='/opt/git-ai-dashboard/mirrors'
# 大仓库默认保守地使用一个后台 Git worker
export GIT_AI_SYNC_WORKER_COUNT=1
export GIT_AI_SYNC_QUEUE_CAPACITY=10
export GIT_AI_MAX_COMMITS_PER_SYNC=500
# 定时同步检查间隔（ISO-8601 时长，例如 PT1M 表示每分钟检查一次）
export GIT_AI_SYNC_SCHEDULE_CHECK_DELAY=PT1M
java -jar git-ai-dashboard-0.0.1-SNAPSHOT.jar
```

## Linux 原生部署（无 Docker）

目录建议：

```text
/opt/git-ai-dashboard/
  backend/git-ai-dashboard.jar
  frontend/                 # dist 内容
  mirrors/                  # bare Git mirror
```

`/etc/systemd/system/git-ai-dashboard.service`：

```ini
[Unit]
Description=Git AI Insight Dashboard
After=network.target mysql.service

[Service]
Type=simple
User=gitai
WorkingDirectory=/opt/git-ai-dashboard/backend
Environment=SPRING_PROFILES_ACTIVE=mysql
Environment=MYSQL_URL=jdbc:mysql://127.0.0.1:3306/git_ai_dashboard?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai
Environment=MYSQL_USER=git_ai_dashboard
Environment=MYSQL_PASSWORD=替换为强密码
Environment=GIT_AI_BIN_DIR=/home/gitai/.git-ai/bin
Environment=GIT_AI_MIRROR_ROOT=/opt/git-ai-dashboard/mirrors
Environment=GIT_AI_SYNC_WORKER_COUNT=1
Environment=GIT_AI_SYNC_QUEUE_CAPACITY=10
Environment=GIT_AI_MAX_COMMITS_PER_SYNC=500
ExecStart=/usr/bin/java -jar /opt/git-ai-dashboard/backend/git-ai-dashboard.jar
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
```

Nginx 配置：

```nginx
server {
    listen 80;
    server_name git-ai.example.internal;
    root /opt/git-ai-dashboard/frontend;
    index index.html;

    location /api/ {
        proxy_pass http://127.0.0.1:8080/api/;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }

    location / {
        try_files $uri $uri/ /index.html;
    }
}
```

完成后执行：

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now git-ai-dashboard
sudo systemctl status git-ai-dashboard
```

## 测试仓库

真实验证仓库位于：

```text
D:\code\skill\git-ai\test-repos\git-ai-attribution-sample
```

已验证 `refs/notes/ai` 的 `authorship/3.0.0` 格式、bare remote/mirror 拉取、`git ai stats --json`，以及 AI、人工、混合提交、未追踪提交四类场景。

## 同步任务阶段

同步任务会按以下阶段执行，页面会实时展示当前仓库所处阶段：

```text
QUEUED → PREPARING_MIRROR → READING_COMMITS → READING_ATTRIBUTION
       → WRITING_STATS → FINALIZING → COMPLETED
```

- `GET /api/sync-jobs/status` 返回 Git worker 的整体状态和当前排队情况。
- `GET /api/sync-jobs` 返回 `phase`、`phaseUpdatedAt`、`processedCommits`、`batchCommitCount` 等字段，用于 UI 展示每个仓库的同步进度。
- 每批同步默认最多处理 500 个提交，可通过 `GIT_AI_MAX_COMMITS_PER_SYNC` 调整；大仓库建议保持小批量并观察资源使用情况。
- 服务重启后，残留的 `RUNNING` 任务会恢复为 `QUEUED`，随后由 worker 继续执行；mirror 目录会复用，不会每次重新 clone。


## 大仓库注意事项

- **建议从小批次开始**：先使用 5 到 7 个提交验证远程仓库、权限和归因格式，再逐步提高批次；`QUEUED` / `RUNNING` 任务不会重复 clone，并默认只启用 1 个 Git worker。
