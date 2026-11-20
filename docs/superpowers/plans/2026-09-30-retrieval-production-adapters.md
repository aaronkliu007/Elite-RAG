# PostgreSQL 批量鉴权与 OpenSearch 检索投影实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**目标：** 交付 PostgreSQL 权威批量鉴权、可恢复 OpenSearch 投影/重建，以及复用既有 Java RRF 内核的 BM25+向量混合召回生产适配层。

**架构：** PostgreSQL 业务事务追加检索投影 Outbox，投影 Worker 始终读取当前权威快照并幂等更新版本化 OpenSearch 索引；查询时 OpenSearch 分别执行 BM25 与 KNN，既有 Java 内核融合，返回前再用一次 PostgreSQL 批量查询失败关闭地复核权限。真实 Embedding 不在本批接入，生产默认以显式向量降级运行全文检索。

**技术栈：** Java 21、Spring Boot 4.1.1、Spring JdbcClient、Flyway、PostgreSQL 17、OpenSearch 3.8.0、OpenSearch Java Client 3.9.0、Apache HttpClient 5、Testcontainers、JUnit 5、Docker Compose。

**规格：** `docs/superpowers/specs/2026-09-30-retrieval-production-adapters-design.md`

## 全局约束

- 在实际检出的项目根目录工作。
- PostgreSQL 是文档、组织、发布状态与 ACL 的唯一权威源；OpenSearch 必须可以删除后重建。
- OpenSearch 服务端固定 `opensearchproject/opensearch:3.8.0`，Java 客户端固定 `org.opensearch.client:opensearch-java:3.9.0`；使用 `ApacheHttpClient5Transport`，不得引入已弃用的 High Level REST Client。
- 中文与混合技术文本使用 OpenSearch 内置 `cjk` analyzer，不要求额外分词插件。
- 向量索引使用 Lucene HNSW；测试维度固定为 4，生产维度仅在 `rag.retrieval.vector.enabled=true` 时强制由配置提供。
- 生产默认 `rag.retrieval.vector.enabled=false`；不得用随机或哈希向量冒充生产 Embedding。
- 所有 OpenSearch 召回都必须过滤空间、有效主体、当前发布投影、ACL 有效期和受控元数据；最终 PostgreSQL 批量复核不可绕过。
- 查询正文、节点正文、向量、密码和访问令牌不得写入普通日志、指标或审计元数据。
- 本批不接入真实模型、真实客户资料，不创建 `pilot-retrieval-v1`，不宣称检索阶段已投产。
- 所有产品代码严格 RED→GREEN；每个任务结束运行该任务完整测试，最终运行整个仓库验收。

## Review Focus

- 同一文档的旧事件在新事件之后执行：`ProjectionWorkerIntegrationTest` 必须证明旧事件不会删除或覆盖较新投影。
- 空间 ACL 事件跨多页处理中途崩溃：`ProjectionWorkerIntegrationTest` 必须证明租约恢复后既不漏文档也不产生越权结果。
- 重建扫描结束与最后一条业务事件并发：`OpenSearchRebuildIntegrationTest` 必须证明排他切换锁内补齐缺口后才切别名。
- ACL 恰好在查询复核时刻过期或成员关系刚被删除：`JdbcRetrievalAuthorizationIntegrationTest` 必须证明候选被拒绝且只执行一次批量 SQL。
- 未启用向量、Embedding 超时或向量维度错误：`OpenSearchRetrieverIntegrationTest` 与装配测试必须证明全文可用、向量明确降级且 readiness 不失败。

---

## 文件结构

- `retrieval/projection`：纯 Java 投影事件、快照、ACL 授权项、Worker 与重建端口；不依赖 Spring、JdbcClient 或 OpenSearch 类型。
- `retrieval/embedding`：`EmbeddingPort`、向量值和未配置异常。
- `infrastructure/postgres`：Outbox、投影源、批量鉴权和咨询锁的 JdbcClient 适配器。
- `infrastructure/opensearch`：客户端装配、索引管理、投影写入、重建及两路召回适配器。
- `resources/opensearch/chunks-v1.json`：唯一 Mapping 模板来源。
- `resources/db/migration/V002__retrieval_projection.sql`：事件、元数据及运行角色授权。

### 任务 1：投影事件 Schema 与可恢复 PostgreSQL 仓储

**文件：**
- 创建：`apps/core-api/src/main/resources/db/migration/V002__retrieval_projection.sql`
- 创建：`apps/core-api/src/main/java/com/yunjihai/rag/retrieval/projection/ProjectionScope.java`
- 创建：`apps/core-api/src/main/java/com/yunjihai/rag/retrieval/projection/ProjectionEventStatus.java`
- 创建：`apps/core-api/src/main/java/com/yunjihai/rag/retrieval/projection/ProjectionEvent.java`
- 创建：`apps/core-api/src/main/java/com/yunjihai/rag/retrieval/projection/ProjectionLease.java`
- 创建：`apps/core-api/src/main/java/com/yunjihai/rag/retrieval/projection/ProjectionLeaseConflictException.java`
- 创建：`apps/core-api/src/main/java/com/yunjihai/rag/retrieval/projection/ProjectionEventRepository.java`
- 创建：`apps/core-api/src/main/java/com/yunjihai/rag/infrastructure/postgres/JdbcProjectionEventRepository.java`
- 修改：`apps/core-api/src/test/java/com/yunjihai/rag/infrastructure/postgres/PostgresMigrationIntegrationTest.java`
- 测试：`apps/core-api/src/test/java/com/yunjihai/rag/infrastructure/postgres/JdbcProjectionEventIntegrationTest.java`

**接口：**
- 产出：`ProjectionEventRepository.enqueueDocument(UUID spaceId, UUID documentId, UUID versionId, long documentRevision, Instant at) -> long sequence`
- 产出：`ProjectionEventRepository.enqueueSpace(UUID spaceId, Instant at) -> long sequence`
- 产出：`ProjectionEventRepository.claimBatch(String workerId, int limit, Instant now, Instant leaseUntil) -> List<ProjectionLease>`
- 产出：`ProjectionEventRepository.checkpoint(UUID eventId, UUID leaseToken, UUID lastDocumentId, Instant at)`、`complete(...)`、`retry(...)`、`fail(...)`
- 产出：`ProjectionEventRepository.currentSequence() -> long`、`eventsAfter(long sequence, int limit) -> List<ProjectionEvent>`

- [ ] **步骤 1：先写迁移与仓储失败测试**

新增测试断言 V002 具备全局 identity `sequence`、scope/status/租约一致性约束、到期领取索引、运行账号权限和 `document_retrieval_metadata(document_id, industry, product, solution_type, year)`；仓储测试覆盖双 Worker `SKIP LOCKED`、租约 Token、空间游标、过期恢复、重复完成和有界退避。

```java
assertEquals(2, firstWorker.size());
assertTrue(secondWorker.stream().noneMatch(firstWorker::contains));
assertThrows(ProjectionLeaseConflictException.class,
        () -> repository.complete(lease.eventId(), staleToken, NOW));
```

- [ ] **步骤 2：运行测试确认 RED**

运行：`.\mvnw.cmd -pl apps/core-api -Dtest=PostgresMigrationIntegrationTest,JdbcProjectionEventIntegrationTest test`

预期：V002 和投影类型不存在导致编译或断言失败；不能以 Docker 未启动之外的错误替代功能 RED。

- [ ] **步骤 3：实现最小 Schema、领域记录与 JdbcClient 仓储**

事件只保存引用和进度，不保存正文/ACL 快照。`claimBatch` 使用 `FOR UPDATE SKIP LOCKED`；所有状态更新匹配事件 ID、租约 Token、`LEASED` 状态和未过期租约。运行角色只获得所需表的最小 DML 权限。

- [ ] **步骤 4：运行任务测试确认 GREEN**

运行：`.\mvnw.cmd -pl apps/core-api -Dtest=PostgresMigrationIntegrationTest,JdbcProjectionEventIntegrationTest test`

预期：全部通过，且并发测试没有重复领取。

- [ ] **步骤 5：提交**

```powershell
git add apps/core-api/src/main/resources/db/migration/V002__retrieval_projection.sql apps/core-api/src/main/java/com/yunjihai/rag/retrieval/projection apps/core-api/src/main/java/com/yunjihai/rag/infrastructure/postgres/JdbcProjectionEventRepository.java apps/core-api/src/test/java/com/yunjihai/rag/infrastructure/postgres
git commit -m "feat: add recoverable retrieval projection outbox"
```

### 任务 2：业务事务内投影事件与并发锁

**文件：**
- 创建：`apps/core-api/src/main/java/com/yunjihai/rag/retrieval/projection/ProjectionChangePublisher.java`
- 创建：`apps/core-api/src/main/java/com/yunjihai/rag/retrieval/projection/ProjectionDocumentLock.java`
- 创建：`apps/core-api/src/main/java/com/yunjihai/rag/retrieval/projection/ProjectionCutoverLock.java`
- 创建：`apps/core-api/src/main/java/com/yunjihai/rag/infrastructure/postgres/JdbcProjectionChangePublisher.java`
- 创建：`apps/core-api/src/main/java/com/yunjihai/rag/infrastructure/postgres/JdbcProjectionDocumentLock.java`
- 创建：`apps/core-api/src/main/java/com/yunjihai/rag/infrastructure/postgres/JdbcProjectionCutoverLock.java`
- 修改：`apps/core-api/src/main/java/com/yunjihai/rag/knowledge/DocumentService.java`
- 修改：`apps/core-api/src/main/java/com/yunjihai/rag/access/AclService.java`
- 修改：`apps/core-api/src/main/java/com/yunjihai/rag/ingestion/IngestionJobService.java`
- 修改：相关构造器单元测试与 `PostgresAdapterConfiguration.java`
- 测试：`apps/core-api/src/test/java/com/yunjihai/rag/infrastructure/postgres/ProjectionEventTransactionIntegrationTest.java`

**接口：**
- 消费：任务 1 的 `ProjectionEventRepository.enqueueDocument/enqueueSpace`
- 产出：`ProjectionChangePublisher.documentChanged(DocumentAggregate document, UUID versionId, Instant at)`
- 产出：`ProjectionChangePublisher.spaceAclChanged(UUID spaceId, Instant at)`、`documentAclChanged(UUID spaceId, UUID documentId, Instant at)`
- 产出：`ProjectionDocumentLock.withDocumentLock(UUID documentId, Supplier<T> action) -> T`
- 产出：`ProjectionCutoverLock.withShared(Supplier<T> action) -> T`、`withExclusive(Duration timeout, Supplier<T> action) -> T`

- [ ] **步骤 1：先写事务与事件范围失败测试**

测试发布、归档、解析完成、空间 ACL 和文档 ACL 分别生成正确 scope；审计或仓储故障时业务变更与事件同时回滚；同一文档变更与 Worker 共享 PostgreSQL advisory lock，不能出现“读旧状态后覆盖新状态”。

```java
assertEquals("DOCUMENT", lastEvent.scope());
assertEquals(document.id(), lastEvent.documentId());
assertThrows(RuntimeException.class, failingPublish);
assertEquals(0L, eventCount());
```

- [ ] **步骤 2：运行测试确认 RED**

运行：`.\mvnw.cmd -pl apps/core-api -Dtest=ProjectionEventTransactionIntegrationTest,DocumentLifecycleTest,AclServiceTest,IngestionJobServiceTest test`

预期：Publisher/Lock 不存在或事件数断言失败。

- [ ] **步骤 3：实现事务内 Publisher 与服务注入**

保留现有便利构造器并显式注入 `ProjectionChangePublisher.noop()` 以维持纯单元测试；`local` 配置必须注入真实 Publisher。Publisher 获取重建共享咨询锁，文档事件再获取稳定文档锁后入队；空间 ACL 使用单个 SPACE 事件，禁止事务内按文档扇出。

- [ ] **步骤 4：运行任务测试确认 GREEN**

运行：`.\mvnw.cmd -pl apps/core-api -Dtest=ProjectionEventTransactionIntegrationTest,DocumentLifecycleTest,AclServiceTest,IngestionJobServiceTest test`

预期：全部通过且回滚后业务表和事件表均无残留。

- [ ] **步骤 5：提交**

```powershell
git add apps/core-api/src/main/java apps/core-api/src/test/java
git commit -m "feat: emit retrieval projection events transactionally"
```

### 任务 3：PostgreSQL 一次批量授权复核

**文件：**
- 创建：`apps/core-api/src/main/java/com/yunjihai/rag/infrastructure/postgres/JdbcRetrievalAuthorizationAdapter.java`
- 修改：`apps/core-api/src/main/java/com/yunjihai/rag/infrastructure/postgres/PostgresAdapterConfiguration.java`
- 测试：`apps/core-api/src/test/java/com/yunjihai/rag/infrastructure/postgres/JdbcRetrievalAuthorizationIntegrationTest.java`

**接口：**
- 产出：实现既有 `RetrievalAuthorizationPort.authorize(UUID userId, UUID spaceId, Set<DocumentVersionRef> requested, Instant checkedAt)`

- [ ] **步骤 1：先写权限负样本与单 SQL 失败测试**

覆盖用户直接授权、祖先部门授权、文档收紧授权、恰好过期、撤权、成员关系删除、归档、跨空间、旧发布版本、重复/空集合；使用计数 DataSource 证明非空请求恰好一次 SQL round-trip，数据库失败不返回部分集合。

```java
assertEquals(Set.of(new DocumentVersionRef(ALLOWED_DOCUMENT, ALLOWED_VERSION)),
        adapter.authorize(USER, SPACE, requested, CHECKED_AT));
assertEquals(1, sqlCounter.statementCount());
```

- [ ] **步骤 2：运行测试确认 RED**

运行：`.\mvnw.cmd -pl apps/core-api -Dtest=JdbcRetrievalAuthorizationIntegrationTest test`

预期：适配器不存在导致编译失败。

- [ ] **步骤 3：实现一次 VALUES CTE 权威查询**

对最多 30 个去重版本构造参数化 `VALUES` CTE；递归解析启用用户的当前部门及祖先；沿用现有语义——任何历史文档级 ACL 记录都会使文档进入收紧模式，只有当前有效文档授权才能通过。所有异常经 `PostgresPersistenceExceptionTranslator` 翻译。

- [ ] **步骤 4：运行任务测试确认 GREEN**

运行：`.\mvnw.cmd -pl apps/core-api -Dtest=JdbcRetrievalAuthorizationIntegrationTest,TrustedRetrievalServiceTest test`

预期：全部通过；空集合零 SQL，非空集合一次 SQL。

- [ ] **步骤 5：提交**

```powershell
git add apps/core-api/src/main/java/com/yunjihai/rag/infrastructure/postgres apps/core-api/src/test/java/com/yunjihai/rag/infrastructure/postgres
git commit -m "feat: add PostgreSQL batch retrieval authorization"
```

### 任务 4：权威投影快照与来源定位转换

**文件：**
- 创建：`apps/core-api/src/main/java/com/yunjihai/rag/retrieval/projection/ProjectionAclGrant.java`
- 创建：`apps/core-api/src/main/java/com/yunjihai/rag/retrieval/projection/ProjectionNode.java`
- 创建：`apps/core-api/src/main/java/com/yunjihai/rag/retrieval/projection/RetrievalProjectionSnapshot.java`
- 创建：`apps/core-api/src/main/java/com/yunjihai/rag/retrieval/projection/RetrievalProjectionSource.java`
- 创建：`apps/core-api/src/main/java/com/yunjihai/rag/infrastructure/postgres/JdbcRetrievalProjectionSource.java`
- 测试：`apps/core-api/src/test/java/com/yunjihai/rag/infrastructure/postgres/JdbcRetrievalProjectionSourceIntegrationTest.java`

**接口：**
- 产出：`RetrievalProjectionSource.load(UUID spaceId, UUID documentId, long projectionSequence) -> RetrievalProjectionSnapshot`
- 产出：`RetrievalProjectionSource.pageSpace(UUID spaceId, UUID afterDocumentId, int limit, long projectionSequence) -> List<RetrievalProjectionSnapshot>`
- 产出：`RetrievalProjectionSource.pageAll(UUID afterDocumentId, int limit, long projectionSequence) -> List<RetrievalProjectionSnapshot>`
- `RetrievalProjectionSnapshot` 明确区分 `indexable` 与 `delete`，并携带文档 revision、ACL grants、限制标志和完整节点。

- [ ] **步骤 1：先写快照完整性失败测试**

覆盖当前发布且解析成功、草稿、归档、缺 Canonical Document、空节点、页码/边界框、表格范围、Markdown nodeId 定位、标题路径、ACL grant expiry 和元数据四个白名单字段；任何非法节点使整个快照失败而不是部分返回。

```java
assertTrue(snapshot.indexable());
assertEquals(List.of("章节", "小节"), snapshot.nodes().getFirst().headingPath());
assertEquals(Set.of(spaceGrant), snapshot.spaceAclGrants());
assertTrue(archivedSnapshot.deleteProjection());
```

- [ ] **步骤 2：运行测试确认 RED**

运行：`.\mvnw.cmd -pl apps/core-api -Dtest=JdbcRetrievalProjectionSourceIntegrationTest test`

预期：投影快照类型/适配器不存在导致编译失败。

- [ ] **步骤 3：实现快照查询与确定性转换**

用有界分页读取文档，按 `stable_order` 加载节点；标题路径只沿合法父节点构建；候选稳定 UUID 使用 `UUID.nameUUIDFromBytes((versionId + ":" + nodeId).getBytes(UTF_8))`，OpenSearch `_id` 使用同一输入字符串。不存在或不可检索状态返回 delete 快照。

- [ ] **步骤 4：运行任务测试确认 GREEN**

运行：`.\mvnw.cmd -pl apps/core-api -Dtest=JdbcRetrievalProjectionSourceIntegrationTest test`

预期：全部通过，分页无重复、无遗漏。

- [ ] **步骤 5：提交**

```powershell
git add apps/core-api/src/main/java/com/yunjihai/rag/retrieval/projection apps/core-api/src/main/java/com/yunjihai/rag/infrastructure/postgres/JdbcRetrievalProjectionSource.java apps/core-api/src/test/java/com/yunjihai/rag/infrastructure/postgres/JdbcRetrievalProjectionSourceIntegrationTest.java
git commit -m "feat: load authoritative retrieval projections"
```

### 任务 5：OpenSearch Mapping、索引管理与幂等 Writer

**文件：**
- 修改：`apps/core-api/pom.xml`
- 创建：`apps/core-api/src/main/resources/opensearch/chunks-v1.json`
- 创建：`apps/core-api/src/main/java/com/yunjihai/rag/infrastructure/opensearch/OpenSearchProperties.java`
- 创建：`apps/core-api/src/main/java/com/yunjihai/rag/infrastructure/opensearch/OpenSearchClientFactory.java`
- 创建：`apps/core-api/src/main/java/com/yunjihai/rag/infrastructure/opensearch/OpenSearchConnection.java`
- 创建：`apps/core-api/src/main/java/com/yunjihai/rag/infrastructure/opensearch/OpenSearchIndexManager.java`
- 创建：`apps/core-api/src/main/java/com/yunjihai/rag/retrieval/projection/SearchProjectionWriter.java`
- 创建：`apps/core-api/src/main/java/com/yunjihai/rag/infrastructure/opensearch/OpenSearchProjectionWriter.java`
- 测试：`apps/core-api/src/test/java/com/yunjihai/rag/infrastructure/opensearch/OpenSearchProjectionWriterIntegrationTest.java`

**接口：**
- 消费：任务 4 的 `RetrievalProjectionSnapshot`
- 产出：`SearchProjectionWriter.apply(String targetIndex, RetrievalProjectionSnapshot snapshot)`
- 产出：`OpenSearchClientFactory.create(OpenSearchProperties properties) -> OpenSearchConnection`，连接持有 `OpenSearchClient` 与 transport 并实现 `AutoCloseable`
- 产出：`OpenSearchIndexManager.createGeneration(String indexName, OptionalInt vectorDimension)`、`switchAliases(String oldIndex, String newIndex)`、`countNodes(String index)`、`refresh(String index)`

- [ ] **步骤 1：先写真实 OpenSearch 容器失败测试**

使用 `GenericContainer<>("opensearchproject/opensearch:3.8.0")` 单节点、关闭 demo security，仅绑定随机本机端口。断言 CJK BM25 Mapping、nested ACL、可选 4 维 `knn_vector`、稳定 `_id`、节点/墓碑 `recordType`、幂等重写、旧序号拒绝、删除残留节点及部分 Bulk 失败不报告成功。

```java
writer.apply(index, sequence10Snapshot);
writer.apply(index, sequence9Snapshot);
assertEquals(10L, indexedNode(index, nodeId).projectionSequence());
```

- [ ] **步骤 2：运行测试确认 RED**

运行：`.\mvnw.cmd -pl apps/core-api -Dtest=OpenSearchProjectionWriterIntegrationTest test`

预期：OpenSearch 依赖与实现不存在导致编译失败。

- [ ] **步骤 3：实现官方 Java Client 与 Writer**

添加 `opensearch-java:3.9.0`，HttpClient 5 版本交给 Spring Boot BOM；工厂支持 HTTP 本地和 HTTPS+Basic Auth 配置，不输出凭据。Mapping 使用内置 `cjk`、Lucene HNSW、nested ACL。Writer 使用 Bulk API，逐项检查错误；先写/校验墓碑序号，再应用完整节点集合并删除同文档的陈旧节点。

- [ ] **步骤 4：运行任务测试确认 GREEN**

运行：`.\mvnw.cmd -pl apps/core-api -Dtest=OpenSearchProjectionWriterIntegrationTest test`

预期：全部通过，客户端和容器关闭后无线程泄漏警告。

- [ ] **步骤 5：提交**

```powershell
git add apps/core-api/pom.xml apps/core-api/src/main/resources/opensearch apps/core-api/src/main/java/com/yunjihai/rag/infrastructure/opensearch apps/core-api/src/main/java/com/yunjihai/rag/retrieval/projection/SearchProjectionWriter.java apps/core-api/src/test/java/com/yunjihai/rag/infrastructure/opensearch
git commit -m "feat: add versioned OpenSearch projection writer"
```

### 任务 6：增量 Projection Worker 与空间分页恢复

**文件：**
- 创建：`apps/core-api/src/main/java/com/yunjihai/rag/retrieval/projection/ProjectionWorkerPolicy.java`
- 创建：`apps/core-api/src/main/java/com/yunjihai/rag/retrieval/projection/ProjectionWorker.java`
- 测试：`apps/core-api/src/test/java/com/yunjihai/rag/retrieval/projection/ProjectionWorkerTest.java`
- 测试：`apps/core-api/src/test/java/com/yunjihai/rag/acceptance/ProjectionWorkerIntegrationTest.java`

**接口：**
- 消费：任务 1 仓储、任务 2 文档锁、任务 4 投影源、任务 5 Writer。
- 产出：`ProjectionWorker.runOnce(String workerId, String targetIndex) -> int completedEvents`

- [ ] **步骤 1：先写 Worker 状态机失败测试**

覆盖 DOCUMENT upsert/delete、SPACE 多页游标、重复事件、乱序事件、陈旧租约、投影源非法、OpenSearch 临时失败退避、结构性失败封存、Worker 在分页中断后恢复；集成测试制造“旧事件晚执行”并断言最终索引仍是当前 PostgreSQL 状态。

```java
assertEquals(1, worker.runOnce("worker-a", WRITE_ALIAS));
assertEquals(ProjectionEventStatus.SUCCEEDED, events.find(eventId).status());
assertEquals(currentVersion, indexedVersion(documentId));
```

- [ ] **步骤 2：运行测试确认 RED**

运行：`.\mvnw.cmd -pl apps/core-api -Dtest=ProjectionWorkerTest,ProjectionWorkerIntegrationTest test`

预期：Worker 不存在导致编译失败。

- [ ] **步骤 3：实现有界 Worker**

每份文档在 `ProjectionDocumentLock` 内重新读取权威状态并写入；SPACE 事件按稳定 UUID 游标逐页处理并在每页后 checkpoint。只有整项事件完成才标记成功；不得吞掉中断信号。错误只保存固定错误码，不保存节点正文或供应商响应。

- [ ] **步骤 4：运行任务测试确认 GREEN**

运行：`.\mvnw.cmd -pl apps/core-api -Dtest=ProjectionWorkerTest,ProjectionWorkerIntegrationTest test`

预期：全部通过，恢复测试索引计数与 PostgreSQL 当前节点计数一致。

- [ ] **步骤 5：提交**

```powershell
git add apps/core-api/src/main/java/com/yunjihai/rag/retrieval/projection apps/core-api/src/test/java/com/yunjihai/rag/retrieval/projection apps/core-api/src/test/java/com/yunjihai/rag/acceptance/ProjectionWorkerIntegrationTest.java
git commit -m "feat: process retrieval projections recoverably"
```

### 任务 7：全量重建、事件追平与原子别名切换

**文件：**
- 创建：`apps/core-api/src/main/java/com/yunjihai/rag/infrastructure/opensearch/OpenSearchRebuildService.java`
- 创建：`apps/core-api/src/main/java/com/yunjihai/rag/infrastructure/opensearch/RebuildResult.java`
- 创建：`apps/core-api/src/main/java/com/yunjihai/rag/infrastructure/opensearch/RebuildValidationException.java`
- 测试：`apps/core-api/src/test/java/com/yunjihai/rag/infrastructure/opensearch/OpenSearchRebuildIntegrationTest.java`

**接口：**
- 消费：`RetrievalProjectionSource.pageAll`、`ProjectionEventRepository.currentSequence/eventsAfter`、Writer 与 IndexManager。
- 产出：`OpenSearchRebuildService.rebuild(String generation, Duration cutoverTimeout) -> RebuildResult`

- [ ] **步骤 1：先写重建竞态失败测试**

测试空索引初始化、全量扫描、扫描期间新增/撤销、排他锁内最终追平、读写别名同一原子请求、计数/抽样校验失败不切换、超时释放锁和保留上一代索引。

```java
assertEquals(oldIndex, aliases.readTarget());
assertThrows(RebuildValidationException.class, rebuildWithInjectedCountMismatch);
assertEquals(oldIndex, aliases.readTarget());
```

- [ ] **步骤 2：运行测试确认 RED**

运行：`.\mvnw.cmd -pl apps/core-api -Dtest=OpenSearchRebuildIntegrationTest test`

预期：重建服务与切换锁不存在导致编译失败。

- [ ] **步骤 3：实现水位重放和短暂排他切换**

事件 Publisher 使用共享事务咨询锁；重建切换使用同一键的排他事务咨询锁。全量扫描前记录起始水位，扫描后循环重放到稳定水位；排他锁内捕获最终水位、补齐缺口、刷新/校验，再以单个 Manage Aliases 请求切换 read/write aliases。任何失败保持旧别名。

- [ ] **步骤 4：运行任务测试确认 GREEN**

运行：`.\mvnw.cmd -pl apps/core-api -Dtest=OpenSearchRebuildIntegrationTest test`

预期：全部通过，竞态测试中新索引包含锁前最后一项业务变化。

- [ ] **步骤 5：提交**

```powershell
git add apps/core-api/src/main/java/com/yunjihai/rag/infrastructure/opensearch/OpenSearchRebuildService.java apps/core-api/src/main/java/com/yunjihai/rag/infrastructure/opensearch/RebuildResult.java apps/core-api/src/main/java/com/yunjihai/rag/infrastructure/opensearch/RebuildValidationException.java apps/core-api/src/test/java/com/yunjihai/rag/infrastructure/opensearch/OpenSearchRebuildIntegrationTest.java
git commit -m "feat: rebuild OpenSearch projections without gaps"
```

### 任务 8：OpenSearch BM25/KNN 召回与 Embedding 降级

**文件：**
- 创建：`apps/core-api/src/main/java/com/yunjihai/rag/retrieval/embedding/EmbeddingPort.java`
- 创建：`apps/core-api/src/main/java/com/yunjihai/rag/retrieval/embedding/EmbeddingVector.java`
- 创建：`apps/core-api/src/main/java/com/yunjihai/rag/retrieval/embedding/EmbeddingUnavailableException.java`
- 创建：`apps/core-api/src/main/java/com/yunjihai/rag/infrastructure/opensearch/OpenSearchRecallQueryFactory.java`
- 创建：`apps/core-api/src/main/java/com/yunjihai/rag/infrastructure/opensearch/OpenSearchCandidateMapper.java`
- 创建：`apps/core-api/src/main/java/com/yunjihai/rag/infrastructure/opensearch/OpenSearchLexicalRetriever.java`
- 创建：`apps/core-api/src/main/java/com/yunjihai/rag/infrastructure/opensearch/OpenSearchVectorRetriever.java`
- 测试：`apps/core-api/src/test/java/com/yunjihai/rag/infrastructure/opensearch/OpenSearchRetrieverIntegrationTest.java`

**接口：**
- 产出：`EmbeddingPort.embed(String text) -> EmbeddingVector`
- 产出：实现既有 `LexicalRetriever.retrieve(...)` 与 `VectorRetriever.retrieve(...)`

- [ ] **步骤 1：先写真实召回失败测试**

索引 4 维确定性测试向量，覆盖中文 BM25、技术英文、KNN、空间隔离、用户/祖先部门主体、空间 ACL 有效期、文档收紧授权、元数据白名单、未知元数据拒绝、稳定 Candidate ID/排名、来源定位和 50 条上限。未配置 Embedding、异常、NaN 和维度错误都必须使向量分支不可用而不污染全文结果。

```java
assertEquals(List.of(EXPECTED_NODE), lexical.retrieve("部署方案", filter, 10)
        .stream().map(Candidate::nodeId).toList());
assertEquals(RecallSource.VECTOR, vector.retrieve("容灾", filter, 10).getFirst().source());
```

- [ ] **步骤 2：运行测试确认 RED**

运行：`.\mvnw.cmd -pl apps/core-api -Dtest=OpenSearchRetrieverIntegrationTest test`

预期：Embedding 与召回适配器不存在导致编译失败。

- [ ] **步骤 3：实现统一安全过滤 Query Factory 与候选映射**

BM25 使用 `multi_match(title, headingPath, bodyText)`；KNN 使用 Lucene engine inline filter。两个分支复用同一个安全过滤工厂，nested ACL 在 `effectiveAt` 检查失效时间，`documentRestricted` 实施空间+文档双授权。只允许 `industry/product/solutionType/year` 元数据键。命中映射只产生领域 `Candidate`，不暴露 OpenSearch 类型。

- [ ] **步骤 4：运行任务测试确认 GREEN**

运行：`.\mvnw.cmd -pl apps/core-api -Dtest=OpenSearchRetrieverIntegrationTest,HybridRecallServiceTest test`

预期：全部通过；禁用向量时既有 `HybridRecallService` 返回 `VECTOR_UNAVAILABLE`。

- [ ] **步骤 5：提交**

```powershell
git add apps/core-api/src/main/java/com/yunjihai/rag/retrieval/embedding apps/core-api/src/main/java/com/yunjihai/rag/infrastructure/opensearch apps/core-api/src/test/java/com/yunjihai/rag/infrastructure/opensearch/OpenSearchRetrieverIntegrationTest.java
git commit -m "feat: add permission-filtered OpenSearch recall"
```

### 任务 9：Spring 装配、健康检查、纵向验收与运行文档

**文件：**
- 创建：`apps/core-api/src/main/java/com/yunjihai/rag/infrastructure/opensearch/OpenSearchAdapterConfiguration.java`
- 创建：`apps/core-api/src/main/java/com/yunjihai/rag/infrastructure/opensearch/OpenSearchReadHealthIndicator.java`
- 创建：`apps/core-api/src/main/java/com/yunjihai/rag/infrastructure/opensearch/OpenSearchProjectionHealthIndicator.java`
- 创建：`apps/core-api/src/main/java/com/yunjihai/rag/infrastructure/opensearch/EmbeddingCapabilityHealthIndicator.java`
- 修改：`apps/core-api/src/main/java/com/yunjihai/rag/infrastructure/postgres/PostgresAdapterConfiguration.java`
- 修改：`apps/core-api/src/main/resources/application.yml`
- 修改：`apps/core-api/src/main/resources/application-local.yml`
- 修改：`infra/compose/compose.yaml`
- 修改：`.gitignore`
- 创建：`docs/runbooks/retrieval-production-adapters.md`
- 修改：`README.md`
- 测试：`apps/core-api/src/test/java/com/yunjihai/rag/acceptance/PostgresOpenSearchRetrievalAcceptanceTest.java`
- 测试：`apps/core-api/src/test/java/com/yunjihai/rag/acceptance/RetrievalProductionWiringTest.java`

**接口：**
- 消费：任务 1–8 全部生产适配器。
- 产出：`local` Profile 下可启动的 `TrustedRetrievalService`、Projection Worker/Rebuild Service Beans，以及分离的 `db`、`opensearchRead`、`opensearchProjection`、`embeddingCapability` 健康详情。

- [ ] **步骤 1：先写装配与纵向验收失败测试**

验收链路：组织/空间/ACL/Canonical Document/发布→事务事件→投影→BM25+确定性向量→Java RRF→一次 PostgreSQL 复核→可定位 Evidence；随后直接撤销 ACL、暂不消费投影事件，证明旧 OpenSearch 命中仍被最终复核剔除。装配测试证明向量默认关闭时应用启动、全文工作、Evidence 标记 `VECTOR_UNAVAILABLE`，readiness 不因允许的向量降级失败。

```java
assertFalse(first.evidence().isEmpty());
revokeAclWithoutRunningProjection();
assertTrue(retrieveAgain().evidence().isEmpty());
assertEquals("UP", health.get("embeddingCapability").getStatus().getCode());
assertEquals("DISABLED", health.get("embeddingCapability").getDetails().get("capability"));
```

- [ ] **步骤 2：运行测试确认 RED**

运行：`.\mvnw.cmd -pl apps/core-api -Dtest=PostgresOpenSearchRetrievalAcceptanceTest,RetrievalProductionWiringTest test`

预期：生产 Beans、配置和健康组件不存在导致失败。

- [ ] **步骤 3：完成 Spring 装配、Compose 和中文文档**

Compose 增加 `opensearchproject/opensearch:3.8.0`，单节点、持久卷、健康检查、仅 `127.0.0.1:9200` 暴露；开发环境关闭 demo security，并在运行手册明确仅限本机。生产属性支持 HTTPS、Basic Auth 与连接/响应超时。Embedding disabled Bean 必须显式失败以触发现有降级，不能返回假向量；其独立健康贡献项报告 `UP` 并携带 `capability=DISABLED`，且不加入 readiness 必需依赖。README 明确本批仍无真实 Embedding/Reranker/黄金集和试点资格。

- [ ] **步骤 4：运行任务验收确认 GREEN**

运行：`.\mvnw.cmd -pl apps/core-api -Dtest=PostgresOpenSearchRetrievalAcceptanceTest,RetrievalProductionWiringTest,DependencyHealthIntegrationTest test`

预期：全部通过，日志不含正文、向量或凭据。

- [ ] **步骤 5：运行全仓库验证**

运行：

```powershell
.\mvnw.cmd clean verify
corepack.cmd pnpm test
corepack.cmd pnpm build
corepack.cmd pnpm lint
corepack.cmd pnpm --dir apps/web exec playwright test
python -m pytest workers/document-parser/tests -q
python -m ruff check workers/document-parser
python -m mypy workers/document-parser
docker compose --env-file infra/compose/.env.example -f infra/compose/compose.yaml config
```

预期：Java、Vue、Playwright、Python 静态检查/测试和 Compose 配置全部通过。若包名与仓库现有 pnpm filter 不同，以根 `package.json`/workspace 的真实脚本为准并在执行账本记录裁决。

- [ ] **步骤 6：执行隔离 Compose 冒烟验收**

使用临时 project name 和临时非敏感测试凭据启动 PostgreSQL、AIStor、OpenSearch；等待三者健康、`minio-init` 成功退出，运行索引初始化/重建/一次检索验收后 `docker compose down -v`。许可证只从 `<仓库外密钥目录>\minio.license` 挂载，不复制进仓库或日志。

预期：三项依赖健康、重建成功、全文检索返回、向量显示允许降级，清理后无本批容器和卷残留。

- [ ] **步骤 7：提交**

```powershell
git add apps/core-api infra/compose docs/runbooks/retrieval-production-adapters.md README.md .gitignore
git commit -m "feat: wire production retrieval adapters"
```

## 最终验收

- 运行 `git diff --check`、`git status --short` 和全仓库验证命令。
- 搜索仓库确认没有 `pilot-retrieval-v1`、真实凭据、客户资料、查询正文日志或测试许可证副本。
- 生成全分支 review package，交由独立 reviewer 重点检查 Review Focus 五类竞态/安全输入。
- Critical/Important 问题只进行一轮 RED→GREEN 修复并重跑全套；Minor 记录到最终交付说明。
- 完成后使用 `superpowers:finishing-a-development-branch`，不得自动 push、合并或创建标签。

## 参考资料

- OpenSearch Java Client 3.9.0 与 Apache HttpClient 5：<https://docs.opensearch.org/latest/clients/java/>
- OpenSearch Java Client 3.x 兼容 OpenSearch 1.x–3.x：<https://github.com/opensearch-project/opensearch-java/blob/main/COMPATIBILITY.md>
- OpenSearch 3.8.0 版本历史：<https://docs.opensearch.org/latest/version-history/>
- CJK analyzer：<https://docs.opensearch.org/latest/analyzers/language-analyzers/cjk/>
- KNN inline filter：<https://docs.opensearch.org/latest/query-dsl/specialized/k-nn/index/>
- 原子 Manage Aliases：<https://docs.opensearch.org/latest/api-reference/alias/aliases-api/>
