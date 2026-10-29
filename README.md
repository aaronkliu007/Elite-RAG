# 云基华海企业级 RAG 知识底座

本仓库用于建设公司级知识资产沉淀、复用与治理底座。当前覆盖 Word、Excel、PDF、PPT、Markdown 和 PNG 的安全接入、统一解析、人工审核、授权发布和审计，并已实现受控的单轮可信问答闭环，为后续报表处理、方案设计与评审提供可信数据基础。

## 当前状态

当前分支已完成知识库核心管理纵向闭环，定位为**受控测试可用、尚未生产准入**，不是可直接投产的系统：

- PostgreSQL 17 + Flyway 管理组织、会话、知识空间、文档版本、ACL、入库租约、Canonical 解析结果、审计和 Blob 清理候选；
- 官方 AIStor 以不可变 SHA-256 内容寻址方式保存原始文件；
- `local` profile 装配 JDBC、MinIO、领域服务和 readiness，普通测试 profile 不连接外部基础设施；
- PostgreSQL 事务投影事件、批量最终鉴权和权威投影读取已经落地；
- OpenSearch 3.8.0 具备版本化映射、BM25/KNN 安全过滤、可恢复 Worker、无缝重建和读写别名；
- 检索链路在 OpenSearch 召回后始终回到 PostgreSQL 做最终授权复核，ACL 紧急撤销不依赖索引先收敛；
- 服务端 Session、本地账号、五类固定角色、知识空间成员管理和统一错误/幂等边界已经落地；
- Vue 已提供中文登录、用户/空间/成员管理、六类文件上传、解析状态、审核发布、全文检索和来源入口；
- Spring Boot 已提供默认覆盖全部有权空间的单轮可信问答、严格拒答、逐项引用、结果恢复和来源实时再次鉴权；
- 方案工作台首条人工纵向闭环已接入 PostgreSQL、Spring Boot API 和 Vue：空间授权、需求及大纲版本、异人确认、来源再次鉴权、事务幂等与审计均可在受控环境验收；
- 方案工作台已接通分章生成、人工采纳、纯文本章节修订、并排版本对比、风险签认、不可变定稿、授权中文 Word 下载及定稿版本反馈；人工改写重新进入确认门禁；
- OpenAI 兼容模型网关、Embedding、Reranker、Chat 适配器、最小证据脱敏、Token/并发/日额度护栏和安全审计已经落地，但默认关闭；
- 50 条虚构脱敏黄金集及泄漏、引用有效率、Recall@10、声明支持率、正确拒答率五项硬门禁已经落地；
- 完整验收覆盖管理员引导、角色分配、上传、解析、驳回再提交、发布、投影、检索、来源下载和撤权即时拒绝；
- 可信问答验收覆盖多空间证据、未授权内容不外发、并发幂等、生成期间撤权、私钥阻断、网关限流/超时和无效引用；
- 真实 PostgreSQL 与官方 AIStor 纵向验收覆盖组织同步、授权、上传、入库、待评审、发布、授权读取和审计；
- Python Worker 仍只负责无状态解析，不持有数据库、AIStor 或模型凭据；
- 真实审批模型联调、真实脱敏黄金集、集中式多副本额度、容量/费用/灾备/渗透测试、丰富的逐条声明编辑与真实钉钉租户仍属后续批次。

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

启动和检查（首次启动解析 Worker 会下载并缓存受控解析依赖，耗时会明显长于其他容器；PyTorch 固定使用 CPU wheel，不下载 CUDA 运行时）：

```powershell
docker compose --env-file infra/compose/.env -f infra/compose/compose.yaml up -d --wait
docker compose --env-file infra/compose/.env -f infra/compose/compose.yaml ps
Invoke-RestMethod http://127.0.0.1:8001/health
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
$env:RAG_PARSER_ENDPOINT = 'http://127.0.0.1:8001'
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

统一脚本会先明确检查 Docker CLI、Docker daemon 和仓库外许可证，校验 Compose，再运行 Java `clean verify`（含真实 PostgreSQL、AIStor、OpenSearch 和受控模型网关验收）、Vue、前端构建与 lint、Python pytest、ruff 和 mypy。核心闭环启动、管理员引导、账号恢复、任务重试、降级、重建和撤权验证见[核心知识管理运行手册](docs/runbooks/core-knowledge-management.md)；问答启用、黄金评测、费用护栏和配置回滚见[可信知识问答运行手册](docs/runbooks/grounded-qa.md)；方案工作台首条人工闭环见[方案工作台运行手册](docs/runbooks/solution-workbench-first-slice.md)，章节修订见[章节人工修订运行说明](docs/runbooks/solution-section-editing.md)，反馈与试运行门槛见[方案反馈运行说明](docs/runbooks/solution-feedback-pilot-gates.md)；底层迁移与检索细节分别见[知识底座运行与恢复手册](docs/runbooks/knowledge-foundation.md)和[检索生产适配器运行手册](docs/runbooks/retrieval-production-adapters.md)。

## 架构边界

```text
钉钉 ──身份/组织──> Java / Spring Boot ──业务状态、ACL、审计──> PostgreSQL
                         │
Web / Vue ──业务 API────┤
                         ├──不可变原始文件──────────────> AIStor（S3 API）
                         │
                         ├──受控字节流 + 版本化任务──────> Python / FastAPI Worker
                         │
                         └──最小脱敏证据 + 受控协议──────> 经审批云模型网关
```

Java 服务是业务事实、权限与事务边界的唯一所有者。PostgreSQL 是权威数据源，AIStor 只保存不可变对象。领域对象不依赖 JDBC、数据库行模型、MinIO SDK 或配置类型。详细原因见 [ADR-0001](docs/adr/0001-java-business-python-parser-boundary.md)。

## 安全底线

- 未发布内容不进入普通用户查询；未授权与资源不存在统一隐藏；
- 所有写操作默认拒绝，固定角色由后端逐动作授权；前端按钮只用于体验，不构成安全边界；
- 日志和审计不得记录正文、节点文本、文件字节、未脱敏文件名、Token、Cookie 或数据库/AIStor 密钥；
- Flyway 校验失败时拒绝启动，不修改已经发布的迁移；
- Blob 数据库事务失败后只登记清理候选，请求线程不得直接删除对象；
- 本批没有物理删除 Worker，清理候选只能检查，不能据此手工删除对象。
