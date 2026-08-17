# 旧 H2 数据库迁移到 MySQL（不重新同步）

适用于已经使用文件型 H2 数据库、且希望迁移到 MySQL 8.0+ 的 Git AI Insight 安装。该流程**只复制已经统计好的数据库记录**，不会重新读取 Git 仓库，也不会执行 Git AI。

## 迁移时不会做什么

- 不执行 `git clone`、`git fetch`、`git log` 或 Git AI 命令；
- 不解析 Git Note，不重新计算 AI/人工代码行；
- 不创建新的同步任务；
- 不访问仓库远程地址；
- 不修改旧 H2 库（导入连接以只读方式打开）。

因此，已有的组织、项目、仓库、提交归因、每日统计、个人排名、运营审计和历史同步记录都会保留；不需要等待历史仓库再次同步。

## 导入前准备

1. 停止正在使用旧 H2 文件的旧服务，避免数据库文件被锁定。
2. 备份旧文件。例如默认旧库为：`backend/data/git-ai-dashboard.mv.db`。
3. 创建一个**新的、空的** MySQL 8.0+ 数据库，并为应用创建数据库账号。
4. 使用包含本迁移工具的新版 JAR。不要把导入目标指向已有业务数据的 MySQL 库。

MySQL 建库示例：

```sql
CREATE DATABASE git_ai_dashboard
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_0900_ai_ci;
CREATE USER 'git_ai_dashboard'@'localhost' IDENTIFIED BY '请替换为强密码';
GRANT ALL PRIVILEGES ON git_ai_dashboard.* TO 'git_ai_dashboard'@'localhost';
FLUSH PRIVILEGES;
```

## 一次性导入命令

以下命令会先用 MySQL Flyway 建立空表，再把 H2 的业务数据复制过去并退出。确认短语不可省略。

Linux 示例：

```bash
export SPRING_PROFILES_ACTIVE='mysql,h2-import'
export MYSQL_URL='jdbc:mysql://127.0.0.1:3306/git_ai_dashboard?useUnicode=true&characterEncoding=UTF-8&connectionCollation=utf8mb4_0900_ai_ci&useSSL=false&serverTimezone=Asia/Shanghai'
export MYSQL_USER='git_ai_dashboard'
export MYSQL_PASSWORD='请替换为强密码'
export GIT_AI_H2_IMPORT_ENABLED=true
export GIT_AI_H2_IMPORT_CONFIRM='IMPORT_EXISTING_DATA_WITHOUT_SYNC'
export GIT_AI_H2_IMPORT_SOURCE_URL='jdbc:h2:file:/opt/git-ai-dashboard/legacy-data/git-ai-dashboard;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH'
export GIT_AI_H2_IMPORT_SOURCE_USERNAME='sa'
export GIT_AI_H2_IMPORT_SOURCE_PASSWORD=''
export GIT_AI_H2_IMPORT_BATCH_SIZE=1000
java -jar git-ai-dashboard.jar
```

Windows PowerShell 示例：

```powershell
$env:SPRING_PROFILES_ACTIVE = 'mysql,h2-import'
$env:MYSQL_URL = 'jdbc:mysql://127.0.0.1:3306/git_ai_dashboard?useUnicode=true&characterEncoding=UTF-8&connectionCollation=utf8mb4_0900_ai_ci&useSSL=false&serverTimezone=Asia/Shanghai'
$env:MYSQL_USER = 'git_ai_dashboard'
$env:MYSQL_PASSWORD = '请替换为强密码'
$env:GIT_AI_H2_IMPORT_ENABLED = 'true'
$env:GIT_AI_H2_IMPORT_CONFIRM = 'IMPORT_EXISTING_DATA_WITHOUT_SYNC'
$env:GIT_AI_H2_IMPORT_SOURCE_URL = 'jdbc:h2:file:D:/backup/git-ai-dashboard;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH'
$env:GIT_AI_H2_IMPORT_SOURCE_USERNAME = 'sa'
$env:GIT_AI_H2_IMPORT_SOURCE_PASSWORD = ''
java -jar .\git-ai-dashboard.jar
```

> H2 JDBC URL 指向的是数据库基础名，不带 `.mv.db` 后缀。例如旧文件为 `git-ai-dashboard.mv.db`，URL 应写到 `.../git-ai-dashboard`。

## 导入过程的安全规则

- 目标库只能是空库，或刚刚被本系统 MySQL Flyway 初始化、只包含默认部门/账号/计划设置的库；若检测到业务数据，工具会拒绝覆盖。
- 导入按外键顺序复制，保留各表原有 ID。
- 导入结束后会逐表校验源/目标行数，并校验 AI、人工、混合、未知代码行等归因汇总是否一致；任何错误都会回滚本次数据库写入。
- 为防止迁移后意外继续旧任务，自动同步会被关闭，未完成（`QUEUED`、`RUNNING`）的历史任务会改为 `CANCELLED`，且不会重跑；已完成和失败任务作为历史保留。
- 所有 `sync_jobs.active_repository_id` 都会清空，避免历史任务锁住仓库。

镜像路径和仓库 URL 会原样保留，但迁移本身不会验证它们是否在新 Linux 服务器上可用。只要不手动开启同步，统计页面仍可直接使用已迁移的历史数据。

## 导入成功后的正常启动

导入命令结束且日志出现 `H2-to-MySQL import completed successfully` 后，清除所有 `GIT_AI_H2_IMPORT_*` 环境变量，再以普通 MySQL profile 启动：

```bash
export SPRING_PROFILES_ACTIVE=mysql
unset GIT_AI_H2_IMPORT_ENABLED GIT_AI_H2_IMPORT_CONFIRM GIT_AI_H2_IMPORT_SOURCE_URL \
  GIT_AI_H2_IMPORT_SOURCE_USERNAME GIT_AI_H2_IMPORT_SOURCE_PASSWORD GIT_AI_H2_IMPORT_BATCH_SIZE
java -jar git-ai-dashboard.jar
```

系统迁移后默认关闭定时同步。若要把该实例长期作为纯统计查看服务，可在普通启动环境中额外设置 `GIT_AI_SYNC_SCHEDULER_ENABLED=false`；它会禁用定时触发器。确认新服务器的仓库地址、Git 凭据、镜像目录和 Git AI 都已配置完成前，不要在运营管理中开启自动同步或手工创建同步任务。

## 迁移结果核对

导入日志会逐表输出源/目标行数、最大 ID 和耗时，并打印总记录数。建议登录后检查：

1. 组织、项目、分组和仓库树是否齐全；
2. 看板总提交数、AI 代码行、人工代码行与旧系统一致；
3. 个人排名、项目/分组全景和历史同步记录可见；
4. 运营管理中的自动同步状态为关闭。

如导入中断或失败，请保留日志，删除/重建目标 MySQL 空库后再执行；旧 H2 文件不会被写入或删除。
