# 可信检索内核实施计划

> **面向智能执行者：** 必须使用 `superpowers:subagent-driven-development`（推荐）或 `superpowers:executing-plans` 逐任务执行本计划。所有步骤使用复选框跟踪。

**执行方式：** 用户已选择 Native；执行时固定使用 `superpowers:executing-plans`，不切换为子代理开发。

**目标：** 交付不依赖 PostgreSQL、OpenSearch 或云模型 SDK 的可信检索内核，用纯内存端口证明混合召回、降级、重排、多样性、当前权限复核和来源证据链。

**架构：** Java 领域层定义查询、候选、定位和证据契约；同步的全文/向量端口由有界执行器并行调用并通过 RRF 融合；重排失败退回融合排名，最终结果在批量权威授权后去重和限额。`TrustedRetrievalService` 是唯一业务入口，具体搜索引擎、模型和数据库以后作为端口适配器接入。

**技术栈：** Java 21、Spring Boot 4.1.1、JUnit 5、AssertJ、Maven；不新增产品依赖。

**规格：** `docs/superpowers/specs/2026-09-28-trusted-retrieval-core-design.md`

## 全局约束

- 代码只放在 `apps/core-api`，包根为 `com.yunjihai.rag.retrieval`。
- 业务类型不得依赖 OpenSearch、Spring AI、供应商 SDK、HTTP DTO 或数据库实体。
- 默认全文/向量各最多 50 条、融合后最多 30 条、RRF `k=60`、单文档最多 3 条、近重复阈值 `0.85`、最终证据请求只能为 8–15 条。
- 默认召回分支超时 1200 ms，重排超时 800 ms；全部参数集中在不可变 `RetrievalPolicy`。
- 查询正文、候选正文和证据正文不得进入审计元数据或异常消息。
- 主体集合和安全判断时间必须由服务端产生，调用方不能提交。
- 最终授权必须重新解析当前主体，并验证空间、当前发布版本、归档与有效期；异常时整体失败关闭。
- 本计划不得创建数据库迁移、OpenSearch 配置、HTTP API、Vue 页面、黄金集或 `pilot-retrieval-v1` 标签。

## 评审重点

- 两个分支用同一候选 ID 返回不同文档、版本、节点或正文时，整体检索必须失败，不能静默合并（任务 2）。
- 召回端口返回超限、重复 ID、非法排名或非有限分数时，该分支必须按非法结果降级；两路都非法则检索不可用（任务 2）。
- 主体解析缺少当前用户、抛错或返回空集时，必须在调用检索端口前失败关闭（任务 4）。
- 重排返回未知、重复、缺失候选 ID，或 NaN/无限分数时，必须完整退回 RRF 顺序（任务 3）。
- 授权端口返回未知文档版本、执行期间撤权/换版/归档/过期或抛错时，不得泄漏候选；未知返回与异常整体失败关闭（任务 4）。

---

### 任务 1：领域契约、来源定位与策略

**文件：**

- 创建：`apps/core-api/src/main/java/com/yunjihai/rag/retrieval/domain/RetrievalQuery.java`
- 创建：`apps/core-api/src/main/java/com/yunjihai/rag/retrieval/domain/RetrievalContext.java`
- 创建：`apps/core-api/src/main/java/com/yunjihai/rag/retrieval/domain/RetrievalFilter.java`
- 创建：`apps/core-api/src/main/java/com/yunjihai/rag/retrieval/domain/RetrievalPolicy.java`
- 创建：`apps/core-api/src/main/java/com/yunjihai/rag/retrieval/domain/RecallSource.java`
- 创建：`apps/core-api/src/main/java/com/yunjihai/rag/retrieval/domain/DegradationReason.java`
- 创建：`apps/core-api/src/main/java/com/yunjihai/rag/retrieval/domain/NormalizedBoundingBox.java`
- 创建：`apps/core-api/src/main/java/com/yunjihai/rag/retrieval/domain/SourceLocator.java`
- 创建：`apps/core-api/src/main/java/com/yunjihai/rag/retrieval/domain/PageLocator.java`
- 创建：`apps/core-api/src/main/java/com/yunjihai/rag/retrieval/domain/SlideLocator.java`
- 创建：`apps/core-api/src/main/java/com/yunjihai/rag/retrieval/domain/TableLocator.java`
- 创建：`apps/core-api/src/main/java/com/yunjihai/rag/retrieval/domain/MarkdownLocator.java`
- 创建：`apps/core-api/src/main/java/com/yunjihai/rag/retrieval/domain/Candidate.java`
- 创建：`apps/core-api/src/main/java/com/yunjihai/rag/retrieval/domain/ScoredCandidate.java`
- 创建：`apps/core-api/src/main/java/com/yunjihai/rag/retrieval/domain/Evidence.java`
- 创建：`apps/core-api/src/main/java/com/yunjihai/rag/retrieval/domain/RetrievalStats.java`
- 创建：`apps/core-api/src/main/java/com/yunjihai/rag/retrieval/domain/EvidencePack.java`
- 测试：`apps/core-api/src/test/java/com/yunjihai/rag/retrieval/domain/RetrievalContractTest.java`

**接口：**

- 产出：`RetrievalQuery(UUID requestId, UUID userId, UUID spaceId, String queryText, Map<String,String> metadataFilter, int requestedEvidenceCount)`。
- 产出：`RetrievalContext(Instant startedAt, Set<PrincipalRef> effectivePrincipals)` 与 `RetrievalFilter(UUID spaceId, Set<PrincipalRef> effectivePrincipals, boolean currentPublishedOnly, Instant effectiveAt, Map<String,String> metadataFilter)`。
- 产出：密封 `SourceLocator permits PageLocator, SlideLocator, TableLocator, MarkdownLocator`。
- 产出：`RetrievalPolicy.defaults()`，默认值严格使用全局约束。
- 产出：后续任务共享的 `Candidate`、`ScoredCandidate`、`Evidence`、`RetrievalStats`、`EvidencePack`。

- [ ] **步骤 1：编写失败的查询与策略契约测试**

测试名称与断言：

- `rejectsBlankOrOverlongQueryText`：空白和超过 2000 个 Unicode code point 的查询被拒绝。
- `rejectsUnsafeMetadataAndEvidenceLimits`：元数据最多 20 项，键 1–64、值 1–256 个 code point；证据数量只允许 8–15。
- `defaultsPinAllRetrievalLimits`：断言 50、50、30、60、3、0.85、1200 ms、800 ms。
- `queryDoesNotExposePrincipalOrSecurityTimeFields`：反射检查 `RetrievalQuery` 不含 principals、startedAt、requestedAt 或 authorizationAt。

- [ ] **步骤 2：运行查询契约测试并确认失败**

运行：`.\mvnw.cmd -q -pl apps/core-api -Dtest=RetrievalContractTest test`

预期：测试编译失败，提示领域类型不存在。

- [ ] **步骤 3：实现查询、上下文、过滤器、枚举和 RetrievalPolicy**

查询文本先执行 Unicode NFKC、首尾去空白和连续空白折叠，再使用 `String.codePointCount` 校验长度；后续召回与审计哈希使用同一规范化值。所有集合与 Map 防御性复制并拒绝 null 元素。`RetrievalPolicy` 构造器同时拒绝非正超时、RRF `k<=0`、阈值不在 `(0,1]`、最终数量范围越界或融合上限大于两路上限之和。

- [ ] **步骤 4：编写失败的来源定位测试**

覆盖页码从 1 开始、边界框坐标必须在 `[0,1]` 且右下大于左上、幻灯片页码从 1 开始、表格名称非空且起止行列有效、Markdown 标题路径与节点 ID 至少一个非空。

- [ ] **步骤 5：实现 SourceLocator 及四种定位记录**

`TableLocator` 签名固定为 `TableLocator(String tableName, int startRow, int endRow, int startColumn, int endColumn)`；`MarkdownLocator` 为 `MarkdownLocator(List<String> headingPath, String nodeId)`。

- [ ] **步骤 6：编写失败的候选与证据契约测试**

断言候选 ID、文档/版本/节点、标题、正文、定位、来源、排名必填；排名从 1 开始，原始分数如存在必须有限；标题最多 500、节点 ID 最多 200、正文最多 20000 个 code point；Evidence 摘要最多 1200 个 code point；EvidencePack 最多 15 条且列表不可变。

- [ ] **步骤 7：实现 Candidate、ScoredCandidate、Evidence、RetrievalStats 和 EvidencePack**

`ScoredCandidate` 保存 `Candidate candidate`、`double score`、`int bestSourceRank`、`Set<RecallSource> sources`；分数必须有限。Evidence 不包含 blob key、对象 URL、用户主体或供应商字段。

- [ ] **步骤 8：运行领域测试与全量 Java 测试**

运行：`.\mvnw.cmd -q -pl apps/core-api -Dtest=RetrievalContractTest test`

运行：`.\mvnw.cmd -q -pl apps/core-api test`

预期：领域契约测试与现有 81 项测试全部通过。

- [ ] **步骤 9：提交任务 1**

```powershell
git add apps/core-api/src/main/java/com/yunjihai/rag/retrieval/domain apps/core-api/src/test/java/com/yunjihai/rag/retrieval/domain
git commit -m "feat: define trusted retrieval domain contracts"
```

### 任务 2：有界混合召回与确定性 RRF

**文件：**

- 创建：`apps/core-api/src/main/java/com/yunjihai/rag/retrieval/search/LexicalRetriever.java`
- 创建：`apps/core-api/src/main/java/com/yunjihai/rag/retrieval/search/VectorRetriever.java`
- 创建：`apps/core-api/src/main/java/com/yunjihai/rag/retrieval/search/HybridRecallResult.java`
- 创建：`apps/core-api/src/main/java/com/yunjihai/rag/retrieval/search/HybridRecallService.java`
- 创建：`apps/core-api/src/main/java/com/yunjihai/rag/retrieval/search/RetrievalUnavailableException.java`
- 创建：`apps/core-api/src/main/java/com/yunjihai/rag/retrieval/search/InvalidRecallResultException.java`
- 测试：`apps/core-api/src/test/java/com/yunjihai/rag/retrieval/search/HybridRecallServiceTest.java`

**接口：**

- 消费：任务 1 的 `RetrievalFilter`、`RetrievalPolicy`、`Candidate`、`ScoredCandidate`、`DegradationReason`。
- 产出：`LexicalRetriever.retrieve(String queryText, RetrievalFilter filter, int limit)` 和 `VectorRetriever.retrieve(...)`，均返回 `List<Candidate>`。
- 产出：`HybridRecallService(LexicalRetriever, VectorRetriever, ExecutorService, RetrievalPolicy)` 与 `HybridRecallResult recall(String queryText, RetrievalFilter filter)`。

- [ ] **步骤 1：编写失败的 RRF 与过滤器传播测试**

`fusesBothBranchesWithDeterministicRrf` 使用两个有重叠候选的固定列表，断言公式为 `sum(1/(60+rank))`，同分时按最佳排名、候选 UUID 升序；断言两个端口收到同一个 `RetrievalFilter` 实例和 limit 50。

- [ ] **步骤 2：运行测试并确认 HybridRecallService 不存在**

运行：`.\mvnw.cmd -q -pl apps/core-api -Dtest=HybridRecallServiceTest test`

- [ ] **步骤 3：实现召回端口、并行调度与 RRF**

使用注入的 `ExecutorService` 同时提交两个同步端口调用，并以 `System.nanoTime()` 计算同一个 1200 ms 批次截止点；依次取结果时只等待剩余预算，避免最坏耗时叠加为 2400 ms。服务不关闭执行器。融合只保留前 30 条；同一候选跨分支时，除 `source`、`sourceRank`、`sourceScore` 外的身份与内容字段必须相同。

- [ ] **步骤 4：编写失败的单路降级、双路失败和超时测试**

分别覆盖全文异常、向量异常、阻塞分支超时、双异常和双超时。单路问题返回健康分支并包含对应 `LEXICAL_UNAVAILABLE` 或 `VECTOR_UNAVAILABLE`；双路问题抛出 `RetrievalUnavailableException`。阻塞测试使用 `CountDownLatch`，不使用任意 sleep 等待成功条件，并在 `finally` 中释放闩锁，避免测试执行器残留阻塞任务。

- [ ] **步骤 5：实现结构化降级与异常归一化**

异常消息只包含分支和结构化原因，不拼接查询正文或候选正文；被超时 Future 必须取消。

- [ ] **步骤 6：编写并通过高风险非法结果测试**

覆盖单分支返回 51 条、重复候选 ID、重复/跳跃排名、非有限分数；该分支降级。两路相同候选 ID 对应不同文档、版本、节点、正文或定位时整体抛出 `RetrievalUnavailableException`。两路均非法时同样不可用。

- [ ] **步骤 7：运行任务测试与全量 Java 测试**

运行：`.\mvnw.cmd -q -pl apps/core-api -Dtest=HybridRecallServiceTest test`

运行：`.\mvnw.cmd -q -pl apps/core-api test`

- [ ] **步骤 8：提交任务 2**

```powershell
git add apps/core-api/src/main/java/com/yunjihai/rag/retrieval/search apps/core-api/src/test/java/com/yunjihai/rag/retrieval/search
git commit -m "feat: add bounded hybrid recall and rrf"
```

### 任务 3：可降级重排与多样性选择

**文件：**

- 创建：`apps/core-api/src/main/java/com/yunjihai/rag/retrieval/selection/RerankerPort.java`
- 创建：`apps/core-api/src/main/java/com/yunjihai/rag/retrieval/selection/RerankScore.java`
- 创建：`apps/core-api/src/main/java/com/yunjihai/rag/retrieval/selection/RerankResult.java`
- 创建：`apps/core-api/src/main/java/com/yunjihai/rag/retrieval/selection/RerankingService.java`
- 创建：`apps/core-api/src/main/java/com/yunjihai/rag/retrieval/selection/EvidenceSelectionService.java`
- 测试：`apps/core-api/src/test/java/com/yunjihai/rag/retrieval/selection/RerankingServiceTest.java`
- 测试：`apps/core-api/src/test/java/com/yunjihai/rag/retrieval/selection/EvidenceSelectionServiceTest.java`

**接口：**

- 消费：任务 1 的 `RetrievalPolicy`、`ScoredCandidate`、`DegradationReason`。
- 产出：`RerankerPort.rerank(String queryText, List<ScoredCandidate> candidates)` 返回 `List<RerankScore>`。
- 产出：`RerankingService(RerankerPort, ExecutorService, RetrievalPolicy)` 与 `RerankResult rerank(String queryText, List<ScoredCandidate>)`。
- 产出：`EvidenceSelectionService(RetrievalPolicy)` 与 `List<ScoredCandidate> select(List<ScoredCandidate> authorizedCandidates, int limit)`。

- [ ] **步骤 1：编写失败的重排成功与输入上限测试**

断言重排端口最多接收前 30 条；合法 `RerankScore(UUID candidateId, double score)` 必须覆盖每个输入候选且恰好一次；结果按分数降序、原 RRF 顺序、候选 ID 排序。

- [ ] **步骤 2：实现 RerankerPort、RerankingService 与 RerankResult**

重排在注入执行器中执行，使用 800 ms 上限，不关闭执行器。

- [ ] **步骤 3：编写失败的重排降级测试**

覆盖异常、超时、未知 ID、重复 ID、缺失 ID、NaN 和无限分数；全部退回原 RRF 列表并仅增加 `RERANKER_UNAVAILABLE`，不得部分采用结果。

- [ ] **步骤 4：实现重排验证、取消和降级**

异常与降级原因不得包含查询正文、候选正文或供应商原始错误正文。

- [ ] **步骤 5：编写失败的中文近重复与多样性测试**

使用中文标点、空白和大小写变化构造相似文本，断言字符 3-gram Jaccard `>=0.85` 时只保留排名更高者；短于 3 字符按规范化全文比较；单文档最多 3 条；请求 8 与 15 均严格限制；合法不足时不补齐。

- [ ] **步骤 6：实现 EvidenceSelectionService**

规范化顺序固定为 Unicode NFKC、转小写、将空白合并为单空格、移除仅用于排版的 Unicode 标点。按输入顺序贪心选择，先检查近重复，再检查文档计数。

- [ ] **步骤 7：运行任务测试与全量 Java 测试**

运行：`.\mvnw.cmd -q -pl apps/core-api -Dtest='RerankingServiceTest,EvidenceSelectionServiceTest' test`

运行：`.\mvnw.cmd -q -pl apps/core-api test`

- [ ] **步骤 8：提交任务 3**

```powershell
git add apps/core-api/src/main/java/com/yunjihai/rag/retrieval/selection apps/core-api/src/test/java/com/yunjihai/rag/retrieval/selection
git commit -m "feat: add degradable reranking and diverse selection"
```

### 任务 4：当前主体复核、可信编排与无正文审计

**文件：**

- 创建：`apps/core-api/src/main/java/com/yunjihai/rag/retrieval/PrincipalResolverPort.java`
- 创建：`apps/core-api/src/main/java/com/yunjihai/rag/retrieval/selection/RetrievalAuthorizationPort.java`
- 创建：`apps/core-api/src/main/java/com/yunjihai/rag/retrieval/EvidenceIdFactory.java`
- 创建：`apps/core-api/src/main/java/com/yunjihai/rag/retrieval/RetrievalService.java`
- 创建：`apps/core-api/src/main/java/com/yunjihai/rag/retrieval/RetrievalAuthorizationUnavailableException.java`
- 创建：`apps/core-api/src/main/java/com/yunjihai/rag/retrieval/TrustedRetrievalService.java`
- 修改：`apps/core-api/src/main/java/com/yunjihai/rag/audit/AuditAction.java`
- 修改：`apps/core-api/src/main/java/com/yunjihai/rag/audit/AuditService.java`
- 测试：`apps/core-api/src/test/java/com/yunjihai/rag/audit/AuditServiceTest.java`
- 测试：`apps/core-api/src/test/java/com/yunjihai/rag/retrieval/TrustedRetrievalServiceTest.java`

**接口：**

- 消费：任务 1–3 的全部公开结果与服务。
- 产出：`PrincipalResolverPort.resolve(UUID userId, Instant at)` 返回 `Set<PrincipalRef>`。
- 产出：`RetrievalAuthorizationPort.authorize(UUID userId, UUID spaceId, Set<DocumentVersionRef> requested, Instant checkedAt)` 返回允许的 `Set<DocumentVersionRef>`。
- 产出：`EvidenceIdFactory.next(Instant at)` 返回 UUID。
- 产出：`RetrievalService.retrieve(RetrievalQuery query)` 返回 `EvidencePack`。
- 产出：`AuditAction.RETRIEVE`。

- [ ] **步骤 1：编写失败的完整健康链路测试**

测试固定 Clock、内存主体解析器、混合召回、重排、授权和确定性 Evidence ID。断言调用顺序为主体解析→两路召回→重排→授权→选择；授权接收去重后的文档版本集合；Evidence 摘要按 code point 安全截断到 1200；最终统计数量正确。

- [ ] **步骤 2：实现端口、RetrievalService 与 TrustedRetrievalService 健康路径**

`TrustedRetrievalService` 构造器注入 `PrincipalResolverPort`、`HybridRecallService`、`RerankingService`、`RetrievalAuthorizationPort`、`EvidenceSelectionService`、`EvidenceIdFactory`、`RetrievalPolicy`、`Clock` 和 `AuditSink`。

- [ ] **步骤 3：编写失败的主体安全测试**

主体解析器返回空集、缺少 `new PrincipalRef(PrincipalType.USER, userId)`、null、含 null 或抛错时，抛出 `RetrievalAuthorizationUnavailableException`；全文、向量和重排端口调用次数均为 0。

- [ ] **步骤 4：实现主体校验与安全过滤器构造**

捕获第一次 `Clock.instant()` 作为召回过滤时间。元数据过滤器只能附加，空间、主体、发布和时间字段由服务覆盖且无外部 setter。

- [ ] **步骤 5：编写失败的返回前授权测试**

覆盖召回后撤销 ACL、当前发布版本替换、归档、过期和组织成员变化；授权端口用第二次 `Clock.instant()` 判定并剔除。断言授权只批量调用一次，明确拒绝可返回空包。

- [ ] **步骤 6：编写并实现授权端口非法结果与异常的失败关闭**

授权返回请求集合之外的文档版本、null 或抛错时，抛出 `RetrievalAuthorizationUnavailableException`，不生成 Evidence、不调用 Evidence ID 工厂、不写成功审计。

- [ ] **步骤 7：编写失败的审计脱敏测试**

健康、空结果和降级路径写 `RETRIEVE/SUCCEEDED`；双召回失败和授权失败写 `RETRIEVE/FAILED`。`AuditService` 白名单只允许 `requestId`、`queryCodePoints`、`lexicalCount`、`vectorCount`、`fusedCount`、`reauthorizedCount`、`selectedCount`、`degradationReasons` 和 `durationMs`；沿用已有 `spaceId`。`queryCodePoints` 只记录查询长度，避免低熵原始查询摘要被离线碰撞。断言审计不含 query、text、content、candidate、evidence、异常正文或主体列表。

- [ ] **步骤 8：实现审计并保证审计失败不产生未审计成功结果**

沿用 `AuditSink`；成功路径先完成所有计算，再记录成功审计，随后返回。审计存储失败时向调用方传播异常。失败路径记录结构化失败审计后重新抛出原业务异常；若失败审计也失败，将其作为 suppressed exception，不能覆盖原异常。

- [ ] **步骤 9：运行任务测试与全量 Java 测试**

运行：`.\mvnw.cmd -q -pl apps/core-api -Dtest='AuditServiceTest,TrustedRetrievalServiceTest' test`

运行：`.\mvnw.cmd -q -pl apps/core-api test`

- [ ] **步骤 10：提交任务 4**

```powershell
git add apps/core-api/src/main/java/com/yunjihai/rag/retrieval apps/core-api/src/main/java/com/yunjihai/rag/audit/AuditAction.java apps/core-api/src/main/java/com/yunjihai/rag/audit/AuditService.java apps/core-api/src/test/java/com/yunjihai/rag/audit/AuditServiceTest.java apps/core-api/src/test/java/com/yunjihai/rag/retrieval
git commit -m "feat: orchestrate reauthorized trusted retrieval"
```

### 任务 5：内存纵向验收、运行说明与阶段边界

**文件：**

- 创建：`apps/core-api/src/test/java/com/yunjihai/rag/acceptance/TrustedRetrievalAcceptanceTest.java`
- 创建：`docs/runbooks/trusted-retrieval-core.md`
- 修改：`README.md`

**接口：**

- 消费：任务 1–4 的公开入口与端口。
- 产出：可重复的内存纵向验收，以及明确说明尚未交付的基础设施和阶段标签。

- [ ] **步骤 1：编写失败的内存纵向验收测试**

一个测试场景依次验证：

- 两路召回产生重叠候选并正确融合；
- 排名第一候选在召回后撤权，不出现在 EvidencePack；
- 旧发布版本被新版本替换，不出现在 EvidencePack；
- 中文近重复只留一条，单文档不超过 3 条；
- 每条 Evidence 有合法定位和确定性不透明 ID；
- 向量分支故障时仍返回全文证据并标记降级；
- 双分支故障时明确失败；
- 相同输入、固定时钟和固定端口得到相同顺序与统计。

- [ ] **步骤 2：运行验收测试并确认失败**

运行：`.\mvnw.cmd -q -pl apps/core-api -Dtest=TrustedRetrievalAcceptanceTest test`

- [ ] **步骤 3：只补齐纵向装配所需的内存测试夹具**

夹具必须位于测试源码内，不新增生产内存仓储或演示后门。不得为验收测试绕过主体解析、最终授权或定位验证。

- [ ] **步骤 4：运行验收与全量 Java 验证**

运行：`.\mvnw.cmd -q -pl apps/core-api -Dtest=TrustedRetrievalAcceptanceTest test`

运行：`.\mvnw.cmd -q -pl apps/core-api verify`

预期：现有测试与新增可信检索测试全部通过并生成 JAR。

- [ ] **步骤 5：编写中文运行说明并更新 README**

运行手册说明内存验收命令、策略默认值、降级语义、审计字段和故障定位；README 明确本批只完成基础设施中立内核，未接 PostgreSQL/OpenSearch/模型、未建立质量基线、不得创建 `pilot-retrieval-v1`。

- [ ] **步骤 6：运行仓库全量门禁**

先为当前 Windows 环境固定已验证的 Python 解释器，再运行统一门禁：

```powershell
$env:UV_PYTHON = 'C:\Users\NO.1\AppData\Roaming\uv\python\cpython-3.12.14-windows-x86_64-none\python.exe'
powershell -ExecutionPolicy Bypass -File scripts/verify.ps1
```

若统一脚本未覆盖真实 Docling extra，则另行运行：

```powershell
$python312 = 'C:\Users\NO.1\AppData\Roaming\uv\python\cpython-3.12.14-windows-x86_64-none\python.exe'
uv sync --python $python312 --project workers/document-parser --extra parsing --frozen
uv run --python $python312 --project workers/document-parser pytest -q -W error
uv run --python $python312 --project workers/document-parser ruff check .
uv run --python $python312 --project workers/document-parser mypy workers/document-parser/src
```

运行：`corepack pnpm --dir apps/web exec playwright test`

预期：Java、前端、Python、静态检查和现有 Playwright 全部通过。

- [ ] **步骤 7：检查变更与阶段标签**

运行：`git diff --check`

运行：`git tag --list pilot-retrieval-v1`

预期：无格式错误；标签不存在。本计划明确禁止创建该标签。

- [ ] **步骤 8：提交任务 5**

```powershell
git add apps/core-api/src/test/java/com/yunjihai/rag/acceptance/TrustedRetrievalAcceptanceTest.java docs/runbooks/trusted-retrieval-core.md README.md
git commit -m "test: accept infrastructure-neutral trusted retrieval"
```

- [ ] **步骤 9：请求全分支代码审查**

审查范围从规格提交 `f68ae13` 的父提交到当前 HEAD，重点检查全局约束和五项评审重点。必须修复所有 Critical/Important 后重新运行任务 5 的全量门禁；保持 `pilot-retrieval-v1` 不存在。
