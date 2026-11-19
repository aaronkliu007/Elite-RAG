# PostgreSQL 鉴权与 OpenSearch 检索投影运行手册

## 1. 适用范围

本手册覆盖当前批次交付的生产形态适配器：PostgreSQL 批量最终鉴权、事务投影事件、OpenSearch 版本化索引、BM25/KNN 混合召回、无缝重建，以及 Spring `local` Profile 装配。

当前能力仍属于研发联调版本。常驻投影调度器已经落地，但没有对外开放重建管理 API；真实 Embedding、真实 Reranker 和黄金评测框架已具备默认关闭的生产适配器与工程验收，尚未完成真实供应商审批、真实业务黄金集和试点资格。不得创建 `pilot-retrieval-v1` 标签，不得用本批结果宣称已投产。

## 2. 数据权威与安全边界

- PostgreSQL 是组织、文档状态、当前发布版本、ACL 和审计的唯一权威数据源。
- OpenSearch 只保存可随时重建的检索投影，绝不能反向覆盖 PostgreSQL。
- OpenSearch 召回先按空间、投影 ACL、失效时间、文档收紧标记和元数据做前置过滤；召回结果仍必须在 PostgreSQL 做一次批量最终鉴权。
- 因投影延迟而残留的旧授权命中，会被 PostgreSQL 最终鉴权剔除。
- 普通检索响应不读取或返回向量、空间 ACL、文档 ACL 字段。
- 日志不得记录查询正文、文档正文、节点文本、向量、ACL 明细、密码、Token、Cookie 或 AIStor 许可证内容。

## 3. 本地依赖启动

唯一项目目录：

```text
E:\git_repo\Elite-RAG
```

AIStor 许可证保持在仓库外：

```text
E:\git_secrets\Elite-RAG\minio.license
```

准备并启动：

```powershell
Set-Location 'E:\git_repo\Elite-RAG'
Copy-Item infra/compose/.env.example infra/compose/.env
docker compose --env-file infra/compose/.env -f infra/compose/compose.yaml up -d --wait
docker compose --env-file infra/compose/.env -f infra/compose/compose.yaml ps
```

本地 Compose 使用 `opensearchproject/opensearch:3.8.0`，关闭 demo security 并只绑定 `127.0.0.1:9200`。该配置只能用于开发机，禁止复制到共享、测试或生产网络。生产环境必须使用 HTTPS、身份认证、网络访问控制和独立密钥管理。

## 4. 首次索引引导

向量默认关闭。首次启动若尚无别名，先创建无向量字段的第一代索引并一次性挂载读写别名：

```powershell
$mapping = Get-Content apps/core-api/src/main/resources/opensearch/chunks-v1.json -Raw
Invoke-RestMethod -Method Put `
  -Uri 'http://127.0.0.1:9200/rag-chunks-v1-bootstrap' `
  -ContentType 'application/json' -Body $mapping

$aliases = @{
  actions = @(
    @{ add = @{ index = 'rag-chunks-v1-bootstrap'; alias = 'rag-chunks-read' } }
    @{ add = @{ index = 'rag-chunks-v1-bootstrap'; alias = 'rag-chunks-write' } }
  )
} | ConvertTo-Json -Depth 6
Invoke-RestMethod -Method Post `
  -Uri 'http://127.0.0.1:9200/_aliases' `
  -ContentType 'application/json' -Body $aliases
```

已有权威文档时，不应把空索引直接作为正式切换结果；应由内部运维任务调用 `OpenSearchRebuildService.rebuild(...)`，完成全量扫描、水位重放、排他锁内最终追平、计数/抽样校验和读写别名原子切换。本批没有暴露公共重建端点，避免形成未授权管理面。

## 5. Core API 配置与启动

从 `.env` 向当前进程导入应用实际需要的变量。不要把 `MINIO_ROOT_*` 或许可证变量传入 Core API：

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

生产连接可设置：

- `RAG_OPENSEARCH_ENDPOINT=https://...`
- `RAG_OPENSEARCH_USERNAME` 与 `RAG_OPENSEARCH_PASSWORD`，必须成对提供；Basic Auth 只允许 HTTPS。
- `RAG_OPENSEARCH_CONNECT_TIMEOUT` 与 `RAG_OPENSEARCH_RESPONSE_TIMEOUT`，使用 ISO-8601 Duration，例如 `PT3S`、`PT10S`。
- `RAG_VECTOR_ENABLED=false` 为当前安全默认值。没有经过审批的 Embedding 配置和完整向量索引重建时不得改为 `true`；模型或维度变更也必须新建代际并重建，具体见 `docs/runbooks/grounded-qa.md`。

## 6. 健康检查

```powershell
Invoke-RestMethod http://127.0.0.1:8080/actuator/health/liveness
Invoke-RestMethod http://127.0.0.1:8080/actuator/health/readiness
```

健康项含义：

- `db`：PostgreSQL 可用。
- `minio`：AIStor 专用随机探针可写、可读、可清理。
- `opensearchRead`：OpenSearch 集群读连接可用。
- `opensearchProjection`：`rag-chunks-write` 恰好指向一个物理索引。
- `embeddingCapability`：能力信息。默认报告 `UP` 和 `capability=DISABLED`，不加入 readiness；查询会保留 BM25 结果并标记 `VECTOR_UNAVAILABLE`。

缺少投影别名时 `opensearchProjection=DOWN`，应用仍能启动但 readiness 为 `DOWN`，不得接入业务流量。

## 7. 投影消费与无缝重建

领域发布、归档、解析完成和 ACL 变化会在同一数据库事务内写入 `retrieval_projection_event`。`ProjectionWorker.runOnce(workerId, targetIndex)` 领取带租约的批次，并按文档咨询锁重新读取权威快照后写入索引。生产调度必须保证：

- `workerId` 稳定且可区分实例；目标使用 `rag-chunks-write`。
- 失败按策略退避，达到上限进入 `FAILED`；不得绕过租约 Token 手工改成完成。
- Worker 被中断时保留线程中断标记，租约到期后可由其他实例恢复。
- 监控 `PENDING/LEASED/FAILED` 数量、最老事件年龄和连续失败码，但不要记录正文。

重建流程创建新的物理代际索引，记录起始事件水位，全量扫描后重放增量；短暂排他锁内捕获最终水位、追平、刷新和校验，最后以单个 Manage Aliases 请求同时切换 `rag-chunks-read` 与 `rag-chunks-write`。校验失败或锁超时必须保留旧索引和旧别名。

## 8. 故障恢复

### OpenSearch 暂时不可用

1. 保持 Core API liveness；readiness 会变为 `DOWN`。
2. 恢复集群后确认 `/_cluster/health`。
3. 确认读写别名仍指向同一代索引。
4. 恢复 Worker，让过期租约自动回收；不要清空事件表。

### 投影事件永久失败

1. 只依据错误码判断类别，不查看或输出正文。
2. `INVALID_RETRIEVAL_PROJECTION` 先修复 PostgreSQL 权威数据或解析产物。
3. 修复后通过受控运维流程重新产生文档/空间事件，或执行完整重建。

### 重建失败

1. 验证读写别名仍在旧索引。
2. 保留失败代际用于技术排查，但不得对其接流量。
3. 修复后使用新的 generation 名重新执行，不复用半成品索引名。
4. 只有确认不再被别名引用后，才按变更流程清理旧代际。

### ACL 紧急撤销

先在 PostgreSQL 撤销。最终鉴权立即生效，即使 OpenSearch 仍有旧投影也不会返回 Evidence；随后恢复事件消费以收敛索引。不得把“索引尚未更新”等同于“撤销尚未生效”。

### 本地重排降级兜底

`local` profile 在外部 Reranker 不可用、超时或返回无效结果时，默认对已授权候选启用本地词项排序，再选择提供给问答模型的片段；成功使用外部 Reranker 时不使用本地兜底。兜底不删除或新增筛查候选、不改变候选分数，仍报告 `RERANKER_UNAVAILABLE`，并保留冲突筛查及最终鉴权。可设置 `RAG_LOCAL_LEXICAL_RERANK_ENABLED=false` 在重启后恢复原始召回顺序。

词项匹配不理解同义表达和业务语义，排序改变可能影响不同问题的证据选取；默认值仅供内部受控测试，尚需用真实业务样本评价，不能据此宣称检索或问答质量达标。完整候选及严格拒答策略保持不变。

## 9. 当前未完成项

- 真实云端 Embedding/Reranker 供应商审批、密钥托管和受控环境联调。
- 真实业务脱敏黄金集、人工抽验和在线质量监控；当前 50 条虚构集只作为工程发布门禁。
- 内部重建控制面、完整指标告警与值班流程。
- 面向试点的容量、性能、灾备、安全测试和正式发布审批。
