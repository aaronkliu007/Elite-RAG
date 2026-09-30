# PostgreSQL 批量鉴权与 OpenSearch 检索投影设计

**日期：** 2026-09-30
**状态：** 已确认，待规格审核
**适用阶段：** 可信检索生产适配器第一批

## 1. 背景与目标

仓库已经具备可信检索的基础设施中立内核，包括双路召回端口、RRF 融合、单路降级、重排降级、返回前授权复核、证据筛选和来源定位。知识底座已经以 PostgreSQL、Flyway 和 AIStor/MinIO 落地组织、ACL、文档版本、Canonical Document 与审计事实，但检索仍缺少真实 PostgreSQL 批量鉴权和 OpenSearch 投影。

本批目标是在不改变既有检索业务规则的前提下，交付可恢复、可重建、默认拒绝的生产适配层：PostgreSQL 继续作为业务与权限权威源，OpenSearch 只保存可删除重建的检索投影；BM25 与向量分支由 OpenSearch 分别召回，现有 Java 内核完成 RRF、降级与最终筛选，返回证据前通过一次 PostgreSQL 批量查询复核所有候选。

真实 Embedding 模型仍属于后续经审批的模型网关批次。本批定义稳定的 `EmbeddingPort`，用确定性测试向量验证混合链路；生产环境未配置 Embedding 时默认关闭向量分支，全文分支继续服务并明确记录降级。

## 2. 已确认约束

- 固定工作目录为 `E:\git_repo\Elite-RAG`。
- 采用“OpenSearch 双路召回 + Java 应用层 RRF”方案，不使用 OpenSearch Hybrid Search Pipeline 代替现有业务内核。
- Java 21 / Spring Boot 是投影编排、检索和事务边界所有者。
- PostgreSQL 是文档、发布状态、组织关系和 ACL 的唯一权威源。
- OpenSearch 是可重建投影，不得成为最终授权事实源。
- 数据留在内网；本批不调用未经审批的云模型，不接入真实客户资料。
- 生产默认不提供伪造、哈希或低质量向量；没有获批 Embedding 时明确降级为全文检索。
- 权限默认拒绝；授权复核异常时整体失败关闭，不返回部分证据。
- 本批不创建 `pilot-retrieval-v1` 或其他试点标签，不宣称检索阶段已投产。

## 3. 范围

### 3.1 本批包含

- Flyway `V002` 检索投影事件、租约、重试和顺序控制数据结构；
- 文档发布、撤销、归档、解析正文变化和 ACL 变化的事务内投影事件；
- PostgreSQL 权威投影读取与一次 SQL 批量授权复核适配器；
- OpenSearch 版本化索引、Mapping、读写别名、幂等投影和按文档删除；
- OpenSearch BM25 与向量召回适配器；
- `EmbeddingPort` 及未配置时的显式降级装配；
- 全量重建、校验、原子别名切换和失败回退边界；
- PostgreSQL/OpenSearch Testcontainers 测试、纵向验收、Compose、健康检查与中文运行手册。

### 3.2 本批不包含

- 真实云端或本地生产 Embedding、Reranker、聊天模型及其凭据；
- OpenSearch Hybrid Search Pipeline 排名配置；
- HTTP 检索 API、Vue 来源查看器和预览 URL；
- 检索黄金集、相关性发布阈值和 `pilot-retrieval-v1` 标签；
- 方案工作台生产持久化、Word 导出或 Agent 工具；
- 真实钉钉租户和真实客户资料。

## 4. 总体架构

```text
PostgreSQL 业务事务
    ├── 文档/版本/ACL/Canonical Node 权威事实
    └── retrieval_projection_event（同事务追加）
                        │
                        ▼
              Projection Worker
              ├── 租约领取与重试
              ├── 读取当前权威快照
              ├── 可检索：幂等 upsert
              └── 不可检索：按文档删除
                        │
                        ▼
              OpenSearch 版本化索引
              ├── BM25 召回 ──────┐
              └── 向量召回 ──────┤
                                  ▼
                         Java 可信检索内核
                         RRF → 可选重排
                                  │
                                  ▼
                    PostgreSQL 一次批量复核
                                  │
                                  ▼
                           可定位 Evidence
```

投影采用事务 Outbox 语义。业务事务只负责提交权威状态和事件，不在事务内调用 OpenSearch。投影 Worker 可以重复执行；OpenSearch 写入以稳定文档 ID 和全局投影序号实现幂等，旧事件不得覆盖新投影。

## 5. PostgreSQL 模型与事务

### 5.1 `retrieval_projection_event`

Flyway `V002__retrieval_projection.sql` 新增投影事件表，至少保存：

- `id`：UUIDv7 兼容事件 ID；
- `sequence`：数据库生成的全局单调递增序号，既是重建水位，也是投影乱序保护版本；
- `scope_type`：`DOCUMENT` 或 `SPACE`，以及 `space_id`、可选 `document_id`、可选 `version_id`；
- `event_type`：`REFRESH_SCOPE`；Worker 始终读取当前权威状态并决定 upsert 或 delete；
- `aggregate_revision`：文档事件保存文档聚合修订号，用于诊断和审计，不作为 ACL 事件的顺序来源；
- `status`：`PENDING`、`LEASED`、`RETRY_WAIT`、`SUCCEEDED`、`FAILED`；
- `attempts`、`next_attempt_at`；
- `lease_token`、`lease_worker`、`lease_expires_at`；
- 固定错误码及创建、更新时间。

同一业务事务内，以下变化必须追加或合并投影事件：

- 当前发布版本建立或替换；
- 发布版本撤销、文档归档或恢复；
- 当前发布版本的 Canonical 内容完成或被替换；
- 影响空间或文档读取范围的 ACL 新增、更新、撤销或过期策略变化。

文档变化追加 `DOCUMENT` 事件。文档级 ACL 变化追加对应 `DOCUMENT` 事件；空间级 ACL 变化追加一个 `SPACE` 事件，由 Worker 使用稳定文档 ID 游标分页重投影该空间全部当前文档，避免在业务事务中无界扇出。空间事件每完成一页保存游标，重试时允许从头幂等执行或从已确认游标继续。

同一文档可以被多个事件覆盖，但 Worker 始终读取当前权威快照。事件自身不携带正文或完整 ACL 快照，避免把 Outbox 变成第二事实源。写入 OpenSearch 时以事件 `sequence` 作为 `projectionSequence`；序号较小的事件即使更晚执行，也不得覆盖序号更大的投影。

### 5.2 领取与恢复

`ProjectionEventRepository` 使用 `FOR UPDATE SKIP LOCKED` 有界批量领取到期事件，并分配不可猜测的租约 Token。完成、重试和失败更新必须同时匹配事件 ID、租约 Token 与当前 `LEASED` 状态。

Worker 崩溃后，租约到期事件可重新领取。可重试失败使用有界指数退避；结构性非法数据进入 `FAILED`，必须由人工修复权威数据后重新排队。重复完成和陈旧租约不得改变已被新 Worker 接管的状态。

### 5.3 权威投影读取

`RetrievalProjectionSource` 按文档读取单个一致性快照，只有满足以下全部条件才返回可索引内容：

- 文档存在、属于目标空间且未归档；
- 当前发布版本存在并仍属于该文档；
- 版本状态为 `PUBLISHED`，解析状态为 `SUCCEEDED`；
- Canonical Document 与节点完整存在；
- 来源定位字段满足既有领域契约。

不满足条件时返回“应删除投影”，而不是猜测或保留旧索引。

## 6. PostgreSQL 批量授权适配器

`JdbcRetrievalAuthorizationAdapter` 实现现有 `RetrievalAuthorizationPort`，一个调用只执行一次权威批量查询，不允许按候选逐条查询。

查询输入是服务端用户 ID、空间 ID、去重后的 `(document_id, version_id)` 集合和服务端复核时间。查询在数据库内：

1. 验证用户存在、启用并重新展开当前有效部门及祖先部门；
2. 构造用户与部门有效主体集合；
3. 批量连接请求的文档版本；
4. 检查空间归属、未归档、当前发布版本及 `PUBLISHED` 状态；
5. 检查空间 ACL、文档收紧 ACL、撤销和有效期；
6. 只返回仍获准读取的 `DocumentVersionRef` 集合。

空输入直接返回空集合，不访问 OpenSearch。不存在、已撤权和越权候选对外均表现为未授权。SQL、映射或连接异常统一翻译为持久化失败，由检索服务转换为 `RetrievalAuthorizationUnavailableException`，整体失败关闭。

## 7. OpenSearch 投影模型

### 7.1 索引与别名

- 物理索引采用版本化名称，例如 `rag-chunks-v1-<generation>`；
- 稳定读取别名为 `rag-chunks-read`；
- 稳定写入别名为 `rag-chunks-write`；
- 运行时查询只能使用读取别名，投影 Worker 只能使用写入别名；
- 全量重建写入新物理索引，校验完成后原子切换两个别名。

OpenSearch 索引可以整体删除并从 PostgreSQL 重建。任何业务流程都不得只把 OpenSearch 中的数据当作恢复来源。

### 7.2 检索文档

每个可检索 Canonical Node 对应一个 OpenSearch 文档，稳定 `_id` 为 `versionId:nodeId`。字段至少包括：

- `spaceId`、`documentId`、`versionId`、`nodeId`；
- `title`、`headingPath`、`bodyText`；
- 类型、稳定顺序和来源定位结构；
- 空间 ACL 与文档 ACL 授权项；每项包含主体键 `<TYPE>:<UUID>` 和可选失效时间；
- `documentRestricted`，表示文档级 ACL 是否收紧空间授权；
- 受控业务元数据；
- `projectionSequence`、`documentRevision`、发布时间和投影时间；
- 可选固定维度向量字段。

精确标识、主体键和过滤字段使用 `keyword`；正文使用适合中文与混合技术文本的 analyzer；向量字段使用 OpenSearch 支持的 KNN Mapping。索引不保存 Blob 对象键、签名 URL、用户查询正文或供应商响应。

ACL 授权项使用 nested Mapping，确保主体键与失效时间属于同一条授权。查询必须在服务端查询时刻验证空间授权未失效，并在 `documentRestricted=true` 时同时验证文档授权未失效。ACL 字段用于召回阶段第一道过滤和性能优化，不替代返回前 PostgreSQL 复核。ACL 新增后可能在事件处理前短暂少召回，但 ACL 撤销后即使旧索引仍命中，也必须被最终复核剔除。

### 7.3 幂等与乱序保护

`SearchProjectionWriter` 提供批量 upsert 和按文档删除。写入脚本仅允许 `projectionSequence` 不小于索引中现有版本时覆盖。删除必须通过同一事件序号保护：先以 `_id=documentId:tombstone` 写入文档级投影墓碑，再删除序号不大于本事件的节点，避免旧删除事件移除新发布内容。所有召回查询固定过滤 `recordType=NODE`，墓碑不参与搜索，并可在安全保留期后清理。

一份文档中的任何节点非法或批量写入部分失败时，整份文档事件不得标记成功。重试必须先恢复到确定状态，再幂等写入完整节点集合并清除已不再存在的旧节点。

## 8. 召回与融合

`OpenSearchLexicalRetriever` 和 `OpenSearchVectorRetriever` 分别实现现有端口。两者接收同一个不可变 `RetrievalFilter`，并应用：

- 固定 `spaceId`；
- 服务端解析的有效主体键；
- 当前发布约束；
- 查询时刻有效性约束；
- 白名单业务元数据条件；
- 端口要求的候选数量上限。

全文分支执行 BM25 查询；向量分支先调用 `EmbeddingPort` 生成查询向量，再执行 KNN 查询。两路只返回现有 `Candidate` 领域对象，不泄露 OpenSearch 响应类型。

融合继续由 `HybridRecallService` 和现有 RRF 规则负责。OpenSearch 不负责跨分支最终排序，因此升级 OpenSearch 不应改变业务层的 `k=60`、稳定排序和单路降级语义。

生产环境未配置 `EmbeddingPort` 时，向量端口必须以结构化“向量不可用”失败；全文健康时返回全文结果并在 `EvidencePack` 中记录降级。禁止使用随机或哈希向量冒充生产 Embedding。

## 9. 全量重建

`OpenSearchRebuildService` 执行以下状态机：

1. 创建新的版本化物理索引并安装固定 Mapping；
2. 使用稳定游标从 PostgreSQL 批量读取全部当前可检索文档；
3. 通过与增量投影相同的转换器和 Writer 写入；
4. 校验文档/节点数量、空间边界、无孤立版本，并执行固定抽样查询；
5. 进入短暂切换阶段：获取 PostgreSQL 投影切换排他咨询锁，阻塞新的事件追加，捕获最终事件水位并将缺口事件重放到新索引；
6. 在仍持有切换锁时原子切换读写别名，然后释放锁；
7. 保留上一代索引供受控回退，后续按保留策略清理。

所有追加投影事件的业务事务先获取同一锁键的共享事务级咨询锁。重建失败不得切换别名。重建期间产生的增量事件在新索引追平前不能丢失；实现采用“记录重建起始事件水位，完成全量扫描后重放该水位之后的事件，排他锁内补齐最终缺口并切换别名”的方式闭合竞态。排他阶段必须有严格超时，失败则保持原别名并释放锁。

## 10. 失败、降级与可观察性

- OpenSearch 写入、超时或临时不可用：事件进入重试，不回滚已提交 PostgreSQL 事实；
- 重复事件：幂等成功；
- 乱序事件：较小投影序号的陈旧事件不得覆盖较大序号投影；
- Worker 崩溃：租约到期后恢复；
- 非法 Canonical Node：整份文档投影失败，不产生残缺索引；
- 未配置 Embedding 或 Embedding 失败：向量分支降级，全文继续；
- OpenSearch 两路均不可用：抛出明确检索不可用异常；
- PostgreSQL 主体解析或批量复核失败：授权失败关闭，不返回任何证据；
- 全量重建失败：保持现有别名与服务不变。

日志和指标只记录请求 ID、空间 ID、事件 ID、文档/版本 ID、分支状态、候选数量、耗时、重试次数和固定错误码。普通日志、指标和审计元数据不得写查询正文、节点正文、向量或访问令牌。

健康检查区分：PostgreSQL 权威依赖、OpenSearch 读取能力、OpenSearch 投影能力和 Embedding 配置状态。Embedding 未配置是允许的降级状态，不得让仅全文部署整体 readiness 失败。

## 11. 测试与验收

### 11.1 PostgreSQL 集成测试

- 一次批量调用复核多个文档版本；
- 空请求、重复请求和不存在版本；
- ACL 撤销、有效期到达、部门成员关系变化；
- 文档归档、空间不匹配和当前发布版本替换；
- 数据库异常时整体失败关闭；
- 多 Worker 并发领取、陈旧租约、租约恢复、重复完成和指数退避；
- 业务变更与投影事件同事务提交或回滚。

### 11.2 OpenSearch 集成测试

- Mapping、稳定 `_id`、中文与技术词 BM25；
- 固定测试向量的 KNN 召回；
- 空间、主体、发布状态和业务元数据过滤；
- 幂等 upsert、旧投影序号拒绝、按文档删除和残留节点清理；
- 版本化索引创建、重建校验、别名原子切换及失败不切换；
- 重建期间增量事件追平。

### 11.3 纵向验收

使用 PostgreSQL 与 OpenSearch Testcontainers 跑通：

1. 创建组织、空间、文档和 ACL；
2. 保存 Canonical Document 并发布版本；
3. 事务内产生事件并完成投影；
4. BM25 与确定性测试向量分别召回；
5. Java 内核执行 RRF；
6. PostgreSQL 一次批量复核；
7. 返回具有正确来源定位的 Evidence；
8. 撤销 ACL 后，即使旧索引仍命中也不返回证据。

另有生产默认装配验收：不配置 Embedding 时应用可启动、全文召回成功、向量分支显式降级，且 readiness 不把允许的降级误报为整体不可用。

## 12. 配置与运行文档

Docker Compose 增加固定版本 OpenSearch 服务、持久卷、仅本机暴露端口和健康检查。本地配置通过环境变量提供端点、用户名、密码、索引前缀、批量大小、租约时长、重试参数和向量开关；密码不得进入仓库。

中文运行手册必须覆盖：启动依赖、初始化索引、触发增量投影、全量重建、检查积压与失败事件、别名回退、未配置 Embedding 的预期降级以及常见故障定位。README 明确本批能力与尚未完成的投产门槛。

## 13. 完成标准与后续门槛

本批完成需同时满足：

- PostgreSQL 和 OpenSearch 集成测试稳定通过；
- 批量授权没有 N+1 查询且异常失败关闭；
- 增量投影可重试、幂等并能从租约中断恢复；
- 全量重建不依赖旧 OpenSearch 数据且可安全切换/回退；
- 混合召回复用现有 RRF 和安全筛选规则；
- 无 Embedding 的生产默认装配可以全文降级运行；
- Compose、健康检查、README 和中文运行手册完成；
- 不含真实凭据、客户资料或未经审批的模型调用。

完成本批仍不能创建 `pilot-retrieval-v1`。后续至少还需交付经审批的生产 Embedding/Reranker、脱敏黄金集与质量阈值、引用 API/来源查看器，以及真实环境的容量、安全和恢复验收。
