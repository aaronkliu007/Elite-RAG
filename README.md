# 云基华海企业级 RAG 知识底座

本仓库用于建设公司级知识资产沉淀、复用与治理底座。首期覆盖 Word、Excel、PDF、PPT、Markdown 和 PNG 的安全接入、统一解析、人工审核、授权发布和审计，为后续检索问答、报表处理、方案设计与评审提供可信数据基础。

## 当前状态

当前分支已完成知识底座持久化和第一批生产形态检索适配器，但仍是研发联调版本，不是可直接投产的系统：

- PostgreSQL 17 + Flyway 管理组织、会话、知识空间、文档版本、ACL、入库租约、Canonical 解析结果、审计和 Blob 清理候选；
- 官方 AIStor 以不可变 SHA-256 内容寻址方式保存原始文件；
- `local` profile 装配 JDBC、MinIO、领域服务和 readiness，普通测试 profile 不连接外部基础设施；
- PostgreSQL 事务投影事件、批量最终鉴权和权威投影读取已经落地；
- OpenSearch 3.8.0 具备版本化映射、BM25/KNN 安全过滤、可恢复 Worker、无缝重建和读写别名；
- 检索链路在 OpenSearch 召回后始终回到 PostgreSQL 做最终授权复核，ACL 紧急撤销不依赖索引先收敛；
- 真实 PostgreSQL 与官方 AIStor 纵向验收覆盖组织同步、授权、上传、入库、待评审、发布、授权读取和审计；
- Python Worker 仍只负责无状态解析，不持有数据库、AIStor 或模型凭据；
- 真实 Embedding、真实 Reranker、黄金评测集、常驻投影调度/管理面、方案工作台生产持久化、真实钉钉租户、云模型网关和业务 API/UI 仍属后续批次。

不得上传真实客户资料，不得宣称已经投产，也不得创建 `pilot-foundation-v1`、`pilot-retrieval-v1` 或 `pilot-workbench-v1` 标签。

## 固定目录与许可证

项目唯一工作目录：

```text
E:\git_repo\Elite-RAG
```

AIStor Free 许可证放在仓库外：

```text
E:\git_secrets\Elite-RAG\minio.license
```

许可证不得复制进仓库、提交到 Git、粘贴到日志或工单。Compose 和 Testcontainers 只以只读方式挂载该文件。`scripts/verify.ps1` 只检查文件是否存在，不读取或输出内容。需要改路径时只在当前终端设置 `RAG_MINIO_LICENSE_FILE`。

## 本地基础设施

环境要求：JDK 21、Docker Desktop、Node.js/Corepack、Python 3.12、uv 0.12.18 和 PowerShell。

先从示例创建仅供本机使用的环境文件：

```powershell
Set-Location 'E:\git_repo\Elite-RAG'
Copy-Item infra/compose/.env.example infra/compose/.env
```

`.env` 不得提交。示例值只用于隔离开发；部署或共享环境必须从密钥系统注入独立随机凭据。数据库迁移账号与运行账号必须分离；AIStor 根账号只负责初始化，Core API 使用仅限 `rag-documents` 桶的应用账号，禁止把根账号提供给应用。

启动和检查：

```powershell
docker compose --env-file infra/compose/.env -f infra/compose/compose.yaml up -d --wait
docker compose --env-file infra/compose/.env -f infra/compose/compose.yaml ps
```

停止并保留数据卷：

```powershell
docker compose --env-file infra/compose/.env -f infra/compose/compose.yaml down
```

仅在确认可以丢弃本地开发数据时才删除卷：

```powershell
docker compose --env-file infra/compose/.env -f infra/compose/compose.yaml down --volumes
```

## Core API 本地启动与健康检查

基础设施健康后，只把 Core API 所需的迁移/运行账号和应用级 AIStor 变量从 `.env` 导入当前进程，再启动 `local` profile。不要把 `MINIO_ROOT_*` 或许可证变量传给 Core API：

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
    'RAG_MINIO_ACCESS_KEY', 'RAG_MINIO_SECRET_KEY', 'RAG_MINIO_BUCKET',
    'RAG_OPENSEARCH_ENABLED', 'RAG_OPENSEARCH_ENDPOINT',
    'RAG_OPENSEARCH_CONNECT_TIMEOUT', 'RAG_OPENSEARCH_RESPONSE_TIMEOUT',
    'RAG_VECTOR_ENABLED', 'RAG_VECTOR_DIMENSION'
) | ForEach-Object {
    if (-not $localSettings.ContainsKey($_)) { throw "infra/compose/.env 缺少变量：$_" }
    [Environment]::SetEnvironmentVariable($_, $localSettings[$_], 'Process')
}
$env:SPRING_PROFILES_ACTIVE = 'local'
./mvnw.cmd -pl apps/core-api spring-boot:run
```

探针：

```powershell
Invoke-RestMethod http://127.0.0.1:8080/actuator/health/liveness
Invoke-RestMethod http://127.0.0.1:8080/actuator/health/readiness
```

`liveness` 只说明 JVM 可响应；`readiness` 同时检查应用接流量状态、PostgreSQL、AIStor、OpenSearch 读连接和投影写别名。任一必需依赖不可用时 readiness 为 `DOWN`，但 liveness 保持 `UP`。`embeddingCapability=DISABLED` 是允许的能力降级，不阻断 readiness；查询保留全文结果并标记 `VECTOR_UNAVAILABLE`。AIStor 探针只使用 `__system/readiness/` 随机键并在 `finally` 清理，不读取或改写业务对象。

## 统一验证

```powershell
$env:JAVA_HOME = 'C:\Program Files\Zulu\zulu-21'
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/verify.ps1
corepack pnpm --dir apps/web exec playwright test
```

统一脚本会先明确检查 Docker CLI、Docker daemon 和仓库外许可证，再运行 Java（含真实基础设施验收）、Vue、前端构建与 lint、Python pytest、ruff 和 mypy。详细迁移、租约恢复、清理候选和故障排查见[知识底座运行与恢复手册](docs/runbooks/knowledge-foundation.md)；检索投影、别名、最终鉴权与降级处理见[检索生产适配器运行手册](docs/runbooks/retrieval-production-adapters.md)。

## 架构边界

```text
钉钉 ──身份/组织──> Java / Spring Boot ──业务状态、ACL、审计──> PostgreSQL
                         │
Web / Vue ──业务 API────┤
                         ├──不可变原始文件──────────────> AIStor（S3 API）
                         │
                         └──受控字节流 + 版本化任务──────> Python / FastAPI Worker
```

Java 服务是业务事实、权限与事务边界的唯一所有者。PostgreSQL 是权威数据源，AIStor 只保存不可变对象。领域对象不依赖 JDBC、数据库行模型、MinIO SDK 或配置类型。详细原因见 [ADR-0001](docs/adr/0001-java-business-python-parser-boundary.md)。

## 安全底线

- 未发布内容不进入普通用户查询；未授权与资源不存在统一隐藏；
- 所有写操作默认拒绝，正式管理员授权边界必须在后续 API/身份批次明确实现；
- 日志和审计不得记录正文、节点文本、文件字节、未脱敏文件名、Token、Cookie 或数据库/AIStor 密钥；
- Flyway 校验失败时拒绝启动，不修改已经发布的迁移；
- Blob 数据库事务失败后只登记清理候选，请求线程不得直接删除对象；
- 本批没有物理删除 Worker，清理候选只能检查，不能据此手工删除对象。
