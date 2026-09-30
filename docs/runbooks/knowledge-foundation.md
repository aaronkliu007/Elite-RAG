# 知识底座本地运行与恢复手册

## 1. 适用范围

本手册用于第一批 PostgreSQL、Flyway、官方 AIStor 和生产适配器的研发联调与故障定位。当前实现已具备真实基础设施验收，但不代表真实钉钉、恶意软件扫描、备份恢复、云模型和投产准入已经完成。禁止接入真实客户资料和创建任何试点标签。

## 2. 固定目录与密钥规则

唯一项目目录：

```text
E:\git_repo\Elite-RAG
```

AIStor Free 许可证：

```text
E:\git_secrets\Elite-RAG\minio.license
```

注意 `git_repo` 和 `git_secrets` 都是单个目录名。许可证只允许仓库外保存并只读挂载；任何脚本、测试、日志和报告都不得读取或输出其内容。

```powershell
Set-Location 'E:\git_repo\Elite-RAG'
Copy-Item infra/compose/.env.example infra/compose/.env
```

本机 `.env` 已由 Git 忽略。示例口令不是生产密钥，不得复用到共享环境。生产和共享环境必须通过密钥系统注入，并至少满足：

- PostgreSQL 迁移账号拥有迁移权限，运行账号只有业务 DML 和必要 TEMP 权限；
- `MINIO_ROOT_USER`/`MINIO_ROOT_PASSWORD` 只供一次性初始化使用；
- `RAG_MINIO_ACCESS_KEY`/`RAG_MINIO_SECRET_KEY` 是独立应用身份，只能访问 `rag-documents`；
- Core API 不得接收 AIStor 根凭据，Python Worker 不得接收数据库或对象存储凭据；
- 工单、截图和日志只能记录密钥版本或指纹，不能记录明文。

若许可证不在默认位置，可只在当前终端设置路径：

```powershell
$env:RAG_MINIO_LICENSE_FILE = 'E:\git_secrets\Elite-RAG\minio.license'
```

## 3. 启动与停止

先校验配置，再启动隔离开发栈：

```powershell
docker compose --env-file infra/compose/.env -f infra/compose/compose.yaml config --quiet
docker compose --env-file infra/compose/.env -f infra/compose/compose.yaml up -d --wait
docker compose --env-file infra/compose/.env -f infra/compose/compose.yaml ps
```

正常停止并保留数据卷：

```powershell
docker compose --env-file infra/compose/.env -f infra/compose/compose.yaml down
```

只有确认本地开发数据可以全部丢弃时，才执行：

```powershell
docker compose --env-file infra/compose/.env -f infra/compose/compose.yaml down --volumes
```

禁止在生产或共享环境使用 `--volumes` 作为回滚手段。

## 4. Flyway 与账号边界

Core API 的 `local` profile 使用独立迁移和运行凭据。Flyway 必须先校验再迁移；缺失迁移、乱序迁移或校验和变化都会拒绝启动。运行账号不能创建/修改 Schema，也不能更新或删除 `audit_event`。

本功能分支发布前曾因约束加强调整 `V001__foundation.sql`。已经运行过旧 V001 的本地开发库不能直接继续使用：

1. 首选做法是停止 Compose，删除仅供开发的卷，再从空库重建；
2. 若本地数据必须保留，只能由迁移负责人先备份、人工核对差异、补齐列/约束，再在受控变更中执行 Flyway repair；
3. 禁止为“让应用启动”而直接忽略校验、改历史记录或对共享/生产库 repair。

检查迁移状态时使用迁移账号，且不要把连接串和口令写进命令历史。空库启动后应看到 `flyway_schema_history` 的版本为 `001`。

## 5. Core API 与健康检查

只把 Core API 所需的迁移/运行账号和应用级 AIStor 变量从 `.env` 导入当前进程后启动。不得把 `MINIO_ROOT_*` 或 `RAG_MINIO_LICENSE_FILE` 传给 Core API：

```powershell
$localSettings = @{}
Get-Content infra/compose/.env | ForEach-Object {
    $line = $_.Trim()
    if ($line -and -not $line.StartsWith('#')) {
        $name, $value = $line -split '=', 2
        $localSettings[$name.Trim()] = $value.Trim()
    }
}
@(
    'RAG_DB_MIGRATION_USER', 'RAG_DB_MIGRATION_PASSWORD',
    'RAG_DB_RUNTIME_USER', 'RAG_DB_RUNTIME_PASSWORD',
    'RAG_MINIO_ACCESS_KEY', 'RAG_MINIO_SECRET_KEY', 'RAG_MINIO_BUCKET'
) | ForEach-Object {
    if (-not $localSettings.ContainsKey($_)) { throw "infra/compose/.env 缺少变量：$_" }
    [Environment]::SetEnvironmentVariable($_, $localSettings[$_], 'Process')
}
$env:SPRING_PROFILES_ACTIVE = 'local'
./mvnw.cmd -pl apps/core-api spring-boot:run
```

检查：

```powershell
Invoke-RestMethod http://127.0.0.1:8080/actuator/health/liveness
Invoke-RestMethod http://127.0.0.1:8080/actuator/health/readiness
```

- `liveness=UP`：JVM 可以响应；临时依赖故障不能把 JVM 判死；
- `readiness=UP`：应用可接流量、PostgreSQL 只读探针成功、AIStor 桶存在且随机探针写读删成功；
- PostgreSQL 或 AIStor 任一不可用时 readiness 必须为 `DOWN`，liveness 仍为 `UP`；
- AIStor 探针使用 `__system/readiness/` 随机键，不列举、不读取、不修改业务对象，并在异常路径尽力清理；
- 健康响应只返回固定状态，不返回连接串、账号、口令或供应商异常。

普通测试 profile 不装配 DataSource、Flyway、JDBC 适配器或 MinIO 客户端，因此不需要外部 PostgreSQL/AIStor。

## 6. 入库租约恢复

领取使用 `FOR UPDATE SKIP LOCKED`，多个 Worker 不会领取同一任务。恢复必须遵守：

1. Worker 崩溃后等待 `lease_expires_at` 到期，由新 Worker 获取新 token；
2. 旧 token 不得完成、重试或失败任务，也不得手工复制给新 Worker；
3. `next_attempt_at` 未到的任务不可领取；可重试错误按退避时间重试；
4. 达到尝试上限、不可重试、恶意、加密、不支持格式或超限错误进入人工处理；
5. Canonical 文档、节点、文档状态、任务状态和 PARSE 审计必须在同一事务成功或全部回滚；
6. 解析成功只能进入 `IN_REVIEW`，必须经人工发布，不能自动发布。

当前没有管理端重放 API。禁止直接改 `lease_token`、`attempt_count` 或把任务手工置为成功。需要恢复时先停止领取入口、保留审计和错误码，再由两人复核的修复脚本执行。

## 7. Blob 清理候选检查

数据库事务失败或对象写入结果不确定时，只登记 `blob_cleanup_candidate`。本批没有物理删除 Worker，也没有人工删除授权。

只读检查示例（使用受控只读会话，不在命令中写明文口令）：

```sql
SELECT id, bucket, sha256, object_key, registered_at, status, last_checked_at
FROM blob_cleanup_candidate
WHERE status IN ('PENDING', 'DEFERRED', 'FAILED')
ORDER BY registered_at;
```

候选不等于可删除。任何 `document_version.source_blob_id` 引用或 `active_blob_reservation.sha256` 预留存在时都不可清理；归档版本仍是历史引用。即使资格查询返回可清理，本批也只能记录和复核，禁止从控制台、脚本或请求线程物理删除 AIStor 对象。

## 8. 常见故障排查

### 8.1 Docker 检查失败

运行 `docker info`。若 CLI 不存在，安装 Docker Desktop 并加入 PATH；若 daemon 不可用，启动 Docker Desktop。统一门禁会明确失败，不会静默跳过真实基础设施测试。

### 8.2 AIStor 启动失败

确认许可证文件路径准确、文件存在且挂载为只读；不要输出文件内容。检查镜像是否为仓库固定的官方版本/摘要。许可证、镜像或网络问题未解决时，不得回退到未审批的第三方镜像。

### 8.3 readiness 中数据库为 DOWN

分别核对运行连接和迁移连接、容器健康、数据库名以及账号分离。运行账号不需要 Schema 变更权限，但必须能连接并执行只读探针。不要把驱动错误原文复制到对外接口或审计。

### 8.4 readiness 中 AIStor 为 DOWN

确认应用凭据能访问指定桶、桶名为 `rag-documents`，并有列举、读取、写入、分段上传和探针删除所需权限。应用凭据必须与根账号不同。检查 `__system/readiness/` 下没有探针残留，但不得检查业务前缀内容。

### 8.5 Flyway 校验失败

停止 Core API，不要设置忽略校验。对于可丢弃的本地旧 V001 库，重建卷；对于必须保留的数据，按第 4 节执行备份、差异核对和受控 repair。

### 8.6 租约长期不恢复

核对数据库时间、`lease_expires_at`、`next_attempt_at` 和 attempt 上限。禁止复用旧 token；检查是否有 Worker 持续崩溃或数据库事务反复回滚。

## 9. 验证与退出检查

统一门禁：

```powershell
$env:JAVA_HOME = 'C:\Program Files\Zulu\zulu-21'
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/verify.ps1
corepack pnpm --dir apps/web exec playwright test
```

独立 Compose 验收必须使用隔离项目名，并保证异常时也清理：

```powershell
try {
    docker compose --env-file infra/compose/.env.example -p elite-rag-verify `
      -f infra/compose/compose.yaml up -d --wait
}
finally {
    docker compose --env-file infra/compose/.env.example -p elite-rag-verify `
      -f infra/compose/compose.yaml down --volumes
}
```

退出前确认：没有 `elite-rag-verify` 容器或卷、Git 工作树不含 `.env`/许可证/真实资料、健康输出和日志不含凭据，也没有创建禁止的试点标签。
