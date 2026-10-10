# 企业知识库可信问答纵向闭环实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在现有 Spring Boot 核心应用内交付默认覆盖全部有权知识空间、只依据已发布授权证据、带可定位引用且证据不足时严格拒答的单轮可信问答闭环。

**Architecture:** 复用现有 OpenSearch 双路召回和 PostgreSQL 最终鉴权，将检索契约扩展为服务端解析的多空间范围；Spring Boot 依次编排召回、授权、重排、脱敏、OpenAI 兼容模型调用、引用校验和持久化。Python Worker 保持无状态解析边界，Vue 只提交问题和幂等键，模型供应商、空间集合、ACL、提示词和安全策略全部由服务端决定。

**Tech Stack:** Java 21、Spring Boot 4.1.1、Spring Security Session/CSRF、Spring JDBC/Flyway、PostgreSQL 17、OpenSearch 3.8/3.9 Java Client、OkHttp 5、WireMock 3、Vue 3、TypeScript、Vitest、Playwright、Docker Compose。

**Spec:** `docs/superpowers/specs/2026-10-10-grounded-qa-vertical-slice-design.md`

## Global Constraints

- 固定工作目录：`E:\git_repo\Elite-RAG`；文档、界面、运行手册和交付说明使用中文。
- 首版只做单轮问答，默认检索服务端解析出的全部有权知识空间；浏览器不能提交空间白名单、用户、ACL、模型、端点、提示词或安全策略。
- PostgreSQL 是身份、空间、发布状态和 ACL 的唯一权威源；OpenSearch 只保存可重建候选投影，最终鉴权失败时整次请求失败关闭。
- 云模型只接收当前请求所需、已授权且已脱敏的最小文本片段；不得发送原始文件、完整文档、ACL 或用户身份信息。
- 模型调用审计不得保存完整提示词、原始问题、证据正文、原始模型响应或凭据。
- 证据不足、证据冲突、无法安全脱敏、模型失败、未知引用或支持性校验失败时严格拒答或失败，不得用模型通用知识补充。
- 生产模型能力默认关闭；没有真实凭据也必须能够构建、测试和以 BM25 降级运行。
- Python Worker 只负责文件解析，不新增数据库、OpenSearch、对象存储或模型凭据访问。
- 不接入真实敏感资料，不创建 `pilot-retrieval-v1` 或其他正式试点标签。
- 所有产品代码按 TDD 实施；每个任务只提交自身文件，不夹带无关变更。

## Review Focus

- 混合 Unicode、连续空白、仅空白和超过 2,000 码点的问题：Task 2 的 `QaDomainContractTest` 必须证明规范化稳定、非法输入在模型调用前拒绝。
- 含“忽略系统规则”、伪造证据 ID、密钥或个人信息的恶意证据：Task 3 和 Task 6 必须证明其只被当作不可信数据、敏感内容被屏蔽且未知引用导致拒答。
- 同一用户并发提交相同 `Idempotency-Key`：Task 8 的控制器集成测试必须证明只发生一次模型调用；不同载荷复用同一键返回幂等冲突。
- 检索后、生成中或查看来源前发生撤权/归档：Task 6、Task 8 和 Task 12 必须证明未授权内容不进入模型请求，迟到结果不泄露，来源再次鉴权失败。
- 模型端点重定向到未审批主机、返回畸形 JSON、超时、限流或超 Token：Task 4 必须证明不跟随重定向、错误被归一化、凭据不出现在异常/日志中且最多一次受控重试。

## Planned File Map

- `apps/core-api/src/main/java/com/yunjihai/rag/retrieval/**`：把现有单空间查询扩展为服务端授权的多空间查询，同时保持现有搜索 API 使用单元素空间集合。
- `apps/core-api/src/main/java/com/yunjihai/rag/qa/domain/**`：单轮问题、证据包、声明、引用、状态、拒答原因和策略等不可变领域对象。
- `apps/core-api/src/main/java/com/yunjihai/rag/qa/model/**`：聊天模型端口、结构化输入/输出和归一化模型故障。
- `apps/core-api/src/main/java/com/yunjihai/rag/qa/sanitization/**`：敏感内容检测、脱敏结果和禁止外发规则。
- `apps/core-api/src/main/java/com/yunjihai/rag/qa/**`：问答应用编排、持久化端口、来源查询端口和支持性校验。
- `apps/core-api/src/main/java/com/yunjihai/rag/infrastructure/model/**`：审批配置、无重定向 HTTP 客户端和 OpenAI 兼容 Embedding/Reranker/Chat 适配器。
- `apps/core-api/src/main/java/com/yunjihai/rag/infrastructure/postgres/JdbcQa*.java`：问答、证据、模型调用审计和评测 JDBC 适配器。
- `apps/core-api/src/main/java/com/yunjihai/rag/qa/web/**`：问题提交、本人结果恢复、来源再次鉴权及评测管理 HTTP 边界。
- `apps/core-api/src/main/java/com/yunjihai/rag/qa/evaluation/**`：黄金集、指标和硬门禁。
- `apps/core-api/src/main/resources/db/migration/V004__grounded_qa.sql`：问答、证据与模型调用审计表、约束和索引。
- `apps/core-api/src/main/resources/db/migration/V005__grounded_qa_evaluation.sql`：版本化黄金集、运行、逐案例结果和质量门禁事实。
- `apps/web/src/api/qa.ts`、`apps/web/src/views/QaView.vue`、`apps/web/src/components/Qa*.vue`：中文单轮问答与引用界面。
- `contracts/qa/golden-dataset-v1.json`：不少于 50 条的脱敏、虚构黄金案例；不得含真实资料。
- `docs/runbooks/grounded-qa.md`：配置、密钥、降级、费用、评测和故障恢复手册。

---

### Task 1: 多空间安全检索契约

**Files:**
- Modify: `apps/core-api/src/main/java/com/yunjihai/rag/retrieval/domain/RetrievalQuery.java`
- Modify: `apps/core-api/src/main/java/com/yunjihai/rag/retrieval/domain/RetrievalFilter.java`
- Modify: `apps/core-api/src/main/java/com/yunjihai/rag/retrieval/domain/Candidate.java`
- Modify: `apps/core-api/src/main/java/com/yunjihai/rag/retrieval/domain/Evidence.java`
- Modify: `apps/core-api/src/main/java/com/yunjihai/rag/retrieval/selection/RetrievalAuthorizationPort.java`
- Modify: `apps/core-api/src/main/java/com/yunjihai/rag/retrieval/TrustedRetrievalService.java`
- Modify: `apps/core-api/src/main/java/com/yunjihai/rag/infrastructure/opensearch/OpenSearchRecallQueryFactory.java`
- Modify: `apps/core-api/src/main/java/com/yunjihai/rag/infrastructure/opensearch/OpenSearchCandidateMapper.java`
- Modify: `apps/core-api/src/main/java/com/yunjihai/rag/infrastructure/postgres/JdbcRetrievalAuthorizationAdapter.java`
- Test: `apps/core-api/src/test/java/com/yunjihai/rag/retrieval/domain/RetrievalContractTest.java`
- Test: `apps/core-api/src/test/java/com/yunjihai/rag/retrieval/TrustedRetrievalServiceTest.java`
- Test: `apps/core-api/src/test/java/com/yunjihai/rag/infrastructure/opensearch/OpenSearchRecallQueryFactoryTest.java`
- Test: `apps/core-api/src/test/java/com/yunjihai/rag/infrastructure/postgres/JdbcRetrievalAuthorizationAdapterIntegrationTest.java`

**Interfaces:**
- Consumes: 现有 `PrincipalResolverPort`、OpenSearch 召回、RRF、重排和 PostgreSQL ACL 数据。
- Produces: `RetrievalQuery(..., Set<UUID> spaceIds, ...)`、包含 `spaceId` 的 `Candidate/Evidence`，以及 `RetrievalAuthorizationPort.authorize(UUID userId, Map<UUID, Set<DocumentVersionRef>> versionsBySpace, Instant at)`。

- [ ] **Step 1: 写失败测试，固定多空间安全语义**

  在四个测试类中增加：空空间集合和超过 100 个空间被拒绝；OpenSearch 使用 `terms spaceId` 而不是客户端字符串拼接；映射结果必须包含 `spaceId`；最终鉴权按空间分组且返回请求外版本时整次失败；单空间现有行为仍成立。

- [ ] **Step 2: 运行定向测试并确认失败**

  Run: `.\mvnw.cmd -q -pl apps/core-api "-Dtest=RetrievalContractTest,TrustedRetrievalServiceTest,OpenSearchRecallQueryFactoryTest,JdbcRetrievalAuthorizationAdapterIntegrationTest" test`

  Expected: FAIL，原因是现有契约只接受单个 `spaceId`。

- [ ] **Step 3: 实现最小多空间契约**

  将空间范围改为不可变、非空、最多 100 个 UUID 的集合；候选和证据携带所属空间；OpenSearch `_source` 增加 `spaceId` 并使用 `terms` 过滤；最终鉴权按空间分组批量执行，任何空间授权查询异常都失败关闭。`SearchController` 用 `Set.of(request.spaceId())` 保持现有 API 兼容。

- [ ] **Step 4: 运行检索模块测试**

  Run: `.\mvnw.cmd -q -pl apps/core-api "-Dtest=*Retrieval*,OpenSearch*Retriever*,OpenSearchRecallQueryFactoryTest" test`

  Expected: PASS，原有单空间用例与新增多空间用例全部通过。

- [ ] **Step 5: 提交**

  ```powershell
  git add apps/core-api/src/main/java/com/yunjihai/rag/retrieval apps/core-api/src/main/java/com/yunjihai/rag/infrastructure/opensearch apps/core-api/src/main/java/com/yunjihai/rag/infrastructure/postgres/JdbcRetrievalAuthorizationAdapter.java apps/core-api/src/test/java/com/yunjihai/rag/retrieval apps/core-api/src/test/java/com/yunjihai/rag/infrastructure
  git commit -m "feat: support authorized multi-space retrieval"
  ```

### Task 2: 可信问答领域契约与状态机

**Files:**
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/qa/domain/QaStatus.java`
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/qa/domain/RefusalReason.java`
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/qa/domain/QaQuestion.java`
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/qa/domain/QaEvidence.java`
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/qa/domain/EvidenceBundle.java`
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/qa/domain/EvidenceSupport.java`
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/qa/domain/GroundedClaim.java`
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/qa/domain/CandidateAnswer.java`
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/qa/domain/QaOutcome.java`
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/qa/QaPolicy.java`
- Test: `apps/core-api/src/test/java/com/yunjihai/rag/qa/domain/QaDomainContractTest.java`
- Test: `apps/core-api/src/test/java/com/yunjihai/rag/qa/QaPolicyTest.java`

**Interfaces:**
- Consumes: Task 1 的多空间 `Evidence`。
- Produces: `QaQuestion.normalize(String)`、`EvidenceBundle.from(List<Evidence>, QaPolicy)`、`CandidateAnswer(decision, answer, claims)`、sealed `QaOutcome.Answered/Refused/Failed` 和状态迁移规则。

- [ ] **Step 1: 写领域失败测试**

  测试名称至少包括 `normalizes_nfkc_and_collapses_unicode_whitespace`、`rejects_blank_or_more_than_2000_code_points`、`copies_all_collections`、`rejects_unknown_or_duplicate_evidence_support`、`terminal_state_cannot_advance`。断言 NFKC 后问题、8–15 条证据范围、每条支持引用必须包含非空原文引句、终态不可变。

- [ ] **Step 2: 运行测试并确认类型尚不存在**

  Run: `.\mvnw.cmd -q -pl apps/core-api "-Dtest=QaDomainContractTest,QaPolicyTest" test`

  Expected: FAIL，缺少 `qa` 领域类型。

- [ ] **Step 3: 实现不可变领域类型和 `QaPolicy.defaults()`**

  默认值固定为：问题最多 2,000 码点、授权空间最多 100、请求证据 10、单证据最多 1,200 码点、模型上下文最多 12,000 码点、模型输出最多 2,000 码点、总超时 30 秒、临时故障最多重试 1 次。`RefusalReason` 至少包含 `NO_AUTHORIZED_SPACE`、`INSUFFICIENT_EVIDENCE`、`CONFLICTING_EVIDENCE`、`UNSAFE_TO_SEND`、`INVALID_MODEL_OUTPUT`、`UNSUPPORTED_CLAIM`。

- [ ] **Step 4: 运行领域测试**

  Run: `.\mvnw.cmd -q -pl apps/core-api "-Dtest=QaDomainContractTest,QaPolicyTest" test`

  Expected: PASS。

- [ ] **Step 5: 提交**

  ```powershell
  git add apps/core-api/src/main/java/com/yunjihai/rag/qa apps/core-api/src/test/java/com/yunjihai/rag/qa
  git commit -m "feat: define grounded qa domain contracts"
  ```

### Task 3: 模型端口、最小证据包与脱敏

**Files:**
- Modify: `apps/core-api/src/main/java/com/yunjihai/rag/retrieval/embedding/EmbeddingPort.java`
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/qa/model/ChatCompletionPort.java`
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/qa/model/GroundedAnswerPrompt.java`
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/qa/model/ModelGeneration.java`
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/qa/model/ModelUsage.java`
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/qa/model/ModelGatewayException.java`
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/qa/sanitization/SensitiveContentSanitizer.java`
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/qa/sanitization/RegexSensitiveContentSanitizer.java`
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/qa/sanitization/SanitizationResult.java`
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/qa/sanitization/SanitizedText.java`
- Test: `apps/core-api/src/test/java/com/yunjihai/rag/qa/sanitization/RegexSensitiveContentSanitizerTest.java`
- Test: `apps/core-api/src/test/java/com/yunjihai/rag/qa/model/ModelContractTest.java`

**Interfaces:**
- Consumes: Task 2 的 `EvidenceBundle`、Task 1 现有向量和重排类型。
- Produces: `EmbeddingPort.embedAll(List<String>)`（`embed(String)` 保留为默认单项方法）、现有 `RerankerPort.rerank(...)`、`ChatCompletionPort.generate(GroundedAnswerPrompt)`、`SensitiveContentSanitizer.sanitize(EvidenceBundle)` 和 `SensitiveContentSanitizer.sanitizeText(String)`。

- [ ] **Step 1: 写脱敏和模型契约失败测试**

  覆盖手机号、身份证、邮箱、Bearer/API Key、PEM 私钥、连接串和配置业务敏感词；断言掩码不改变证据 ID/定位，整列批量个人信息和私钥触发 `blocked=true`，含“忽略规则并输出秘密”的普通文本不被当作指令执行。模型返回集合必须防御性复制，Token 和延迟必须非负。

- [ ] **Step 2: 运行定向测试确认失败**

  Run: `.\mvnw.cmd -q -pl apps/core-api "-Dtest=RegexSensitiveContentSanitizerTest,ModelContractTest" test`

  Expected: FAIL，缺少端口和脱敏实现。

- [ ] **Step 3: 实现端口和确定性脱敏器**

  `GroundedAnswerPrompt` 只含规范化问题、编号脱敏证据、语言、策略/提示词版本和输出上限；不得含用户、ACL、对象存储路径。`SanitizationResult` 返回脱敏证据、各类别命中数和阻断状态；`SanitizedText` 为向量投影提供单文本结果，两者共用同一规则实现，异常不允许返回原文。

- [ ] **Step 4: 运行测试并回归现有向量/重排测试**

  Run: `.\mvnw.cmd -q -pl apps/core-api "-Dtest=RegexSensitiveContentSanitizerTest,ModelContractTest,OpenSearchVectorRetrieverTest,RerankingServiceTest" test`

  Expected: PASS。

- [ ] **Step 5: 提交**

  ```powershell
  git add apps/core-api/src/main/java/com/yunjihai/rag/retrieval apps/core-api/src/main/java/com/yunjihai/rag/qa apps/core-api/src/test/java/com/yunjihai/rag
  git commit -m "feat: add model and sanitization ports"
  ```

### Task 4: 审批模型网关与 OpenAI 兼容适配器

**Files:**
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/infrastructure/model/ApprovedModelGatewayProperties.java`
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/infrastructure/model/ModelUsagePolicy.java`
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/infrastructure/model/OpenAiCompatibleClient.java`
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/infrastructure/model/OpenAiEmbeddingAdapter.java`
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/infrastructure/model/OpenAiRerankerAdapter.java`
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/infrastructure/model/OpenAiChatCompletionAdapter.java`
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/infrastructure/model/ModelGatewayConfiguration.java`
- Test: `apps/core-api/src/test/java/com/yunjihai/rag/infrastructure/model/ApprovedModelGatewayPropertiesTest.java`
- Test: `apps/core-api/src/test/java/com/yunjihai/rag/infrastructure/model/OpenAiCompatibleClientTest.java`
- Test: `apps/core-api/src/test/java/com/yunjihai/rag/infrastructure/model/OpenAiAdaptersTest.java`

**Interfaces:**
- Consumes: Task 3 的三个模型端口和 `ModelGatewayException`。
- Produces: 在 `rag.ai.enabled=true` 且配置完整时装配的生产适配器；默认关闭时提供明确不可用端口，使 BM25 可降级但 Chat 不伪造答案。

- [ ] **Step 1: 用 WireMock 写安全配置和协议失败测试**

  覆盖：生产端点必须为 HTTPS 且主机在 `approved-hosts`；只有显式测试开关允许 loopback HTTP；HTTP 3xx 不跟随；认证、超时、429、5xx、畸形 JSON、维度错误、重复/未知重排 ID、未知证据 ID 和超 Token 被归一化；异常、`toString()` 和日志捕获不含 API Key、问题或证据正文。

- [ ] **Step 2: 运行测试并确认失败**

  Run: `.\mvnw.cmd -q -pl apps/core-api "-Dtest=ApprovedModelGatewayPropertiesTest,OpenAiCompatibleClientTest,OpenAiAdaptersTest" test`

  Expected: FAIL，适配器尚不存在。

- [ ] **Step 3: 实现无自动重定向客户端和三类适配器**

  固定路径为 `/v1/embeddings`、`/v1/rerank`、`/v1/chat/completions`；聊天设置非流式和结构化 JSON Schema。配置包括端点、审批主机、三个模型名、API Key、连接/响应/总超时、最大输入/输出 Token、最大并发和部署级日 Token 上限；客户端日志只记录请求 ID、端点标识、状态、耗时和 Token。

- [ ] **Step 4: 运行适配器测试**

  Run: `.\mvnw.cmd -q -pl apps/core-api "-Dtest=ApprovedModelGatewayPropertiesTest,OpenAiCompatibleClientTest,OpenAiAdaptersTest" test`

  Expected: PASS，WireMock 收到的请求仅含协议所需字段。

- [ ] **Step 5: 提交**

  ```powershell
  git add apps/core-api/src/main/java/com/yunjihai/rag/infrastructure/model apps/core-api/src/test/java/com/yunjihai/rag/infrastructure/model
  git commit -m "feat: add approved openai compatible gateway"
  ```

### Task 5: 真实向量投影与重排装配

**Files:**
- Modify: `apps/core-api/src/main/java/com/yunjihai/rag/infrastructure/opensearch/OpenSearchProjectionWriter.java`
- Modify: `apps/core-api/src/main/java/com/yunjihai/rag/infrastructure/opensearch/OpenSearchAdapterConfiguration.java`
- Modify: `apps/core-api/src/main/java/com/yunjihai/rag/infrastructure/opensearch/EmbeddingCapabilityHealthIndicator.java`
- Modify: `apps/core-api/src/main/java/com/yunjihai/rag/runtime/WorkerRuntimeConfiguration.java`
- Modify: `apps/core-api/src/main/resources/application-local.yml`
- Test: `apps/core-api/src/test/java/com/yunjihai/rag/infrastructure/opensearch/OpenSearchProjectionWriterTest.java`
- Test: `apps/core-api/src/test/java/com/yunjihai/rag/acceptance/RetrievalProductionWiringTest.java`
- Test: `apps/core-api/src/test/java/com/yunjihai/rag/acceptance/ProjectionWorkerIntegrationTest.java`

**Interfaces:**
- Consumes: Task 3 的批量 `EmbeddingPort` 和文本级脱敏能力、Task 4 的生产 Embedding/Reranker Bean。
- Produces: 启用向量时写入与索引维度一致的 `embedding`；未启用时完全省略向量并保持 BM25；真实 `RerankerPort` 取代固定禁用 Bean。

- [ ] **Step 1: 写投影和装配失败测试**

  断言批量 Embedding 的输入只包含经过脱敏的单个 Canonical 节点正文，原始敏感值不出现在模型请求；节点触发禁止外发时整个投影事件失败关闭；返回数量/维度不符时整个投影事件失败并可重试；禁用时不调用模型且 JSON 无 `embedding`；开启后重建索引和查询使用相同维度；Reranker 不可用时保留现有降级原因。

- [ ] **Step 2: 运行测试并确认失败**

  Run: `.\mvnw.cmd -q -pl apps/core-api "-Dtest=OpenSearchProjectionWriterTest,RetrievalProductionWiringTest,ProjectionWorkerIntegrationTest" test`

  Expected: FAIL，投影尚未生成向量且配置仍装配禁用端口。

- [ ] **Step 3: 实现批量向量写入和条件装配**

  `OpenSearchProjectionWriter` 在构造 bulk NDJSON 前逐节点执行与问答相同的敏感内容策略，再一次批量取向量、校验数量/有限值/维度并将每个向量写入对应节点；任何节点阻断或批次异常都不得部分写入。`OpenSearchAdapterConfiguration` 复用 Task 4 Bean，AI 关闭时保留原有显式降级；健康指标反映真实配置而不是固定 `false`。

- [ ] **Step 4: 运行 OpenSearch 与生产装配测试**

  Run: `.\mvnw.cmd -q -pl apps/core-api "-Dtest=OpenSearch*Test,ProjectionWorkerIntegrationTest,RetrievalProductionWiringTest" test`

  Expected: PASS。

- [ ] **Step 5: 提交**

  ```powershell
  git add apps/core-api/src/main/java/com/yunjihai/rag/infrastructure/opensearch apps/core-api/src/main/java/com/yunjihai/rag/runtime apps/core-api/src/main/resources/application-local.yml apps/core-api/src/test/java/com/yunjihai/rag/infrastructure/opensearch apps/core-api/src/test/java/com/yunjihai/rag/acceptance
  git commit -m "feat: enable production embeddings and reranking"
  ```

### Task 6: 严格拒答的问答应用编排

**Files:**
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/qa/AuthorizedSpaceScopePort.java`
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/qa/QaPersistencePort.java`
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/qa/ModelCallAuditPort.java`
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/qa/QuestionDigestPort.java`
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/qa/QaSourceAccessPort.java`
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/qa/AskQuestionCommand.java`
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/qa/GroundedQaService.java`
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/qa/CitationSupportVerifier.java`
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/qa/ExactQuoteCitationSupportVerifier.java`
- Test: `apps/core-api/src/test/java/com/yunjihai/rag/qa/GroundedQaServiceTest.java`
- Test: `apps/core-api/src/test/java/com/yunjihai/rag/qa/ExactQuoteCitationSupportVerifierTest.java`

**Interfaces:**
- Consumes: `AuthorizedSpaceScopePort.resolve(UUID userId, Instant at)`、Task 1 `RetrievalService`、Task 3 脱敏与 Chat 端口、Task 2 领域模型。
- Produces: `GroundedQaService.ask(AskQuestionCommand)`、`GroundedQaService.find(UUID requestId, UUID actorId)` 和供 JDBC/HMAC 实现的持久化、审计、问题摘要端口。

- [ ] **Step 1: 写应用服务失败测试**

  覆盖完整状态顺序；无授权空间、超过 100 个空间、无证据、低分证据、冲突、脱敏阻断、Chat 故障、非法 JSON、未知证据 ID、伪造支持引句、未支持声明和迟到模型响应。断言最终鉴权后的证据才进入脱敏器/Chat，包含提示词注入文字的证据仍作为普通数据，且任何拒答都不会拼接通用知识。

- [ ] **Step 2: 运行测试并确认失败**

  Run: `.\mvnw.cmd -q -pl apps/core-api "-Dtest=GroundedQaServiceTest,ExactQuoteCitationSupportVerifierTest" test`

  Expected: FAIL，问答编排尚不存在。

- [ ] **Step 3: 实现问答编排和引用支持校验**

  使用同一服务端时刻解析空间和主体；以全部授权空间构造 `RetrievalQuery`；通过 `QuestionDigestPort.digest(String)` 生成带服务端密钥的摘要，先持久化证据快照，再脱敏和调用模型。每个 `GroundedClaim` 必须至少有一个 `EvidenceSupport(evidenceId, exactQuote)`，`exactQuote` 经 NFKC/空白规范化后必须是对应脱敏证据的连续子串；答案中出现的引用集合必须与声明支持闭包一致。临时模型故障最多重试一次，终态由带 revision 的条件更新保护；模型调用审计写入失败时不返回答案。

- [ ] **Step 4: 运行问答服务测试**

  Run: `.\mvnw.cmd -q -pl apps/core-api "-Dtest=GroundedQaServiceTest,ExactQuoteCitationSupportVerifierTest" test`

  Expected: PASS，并验证模型请求捕获中不含未授权候选。

- [ ] **Step 5: 提交**

  ```powershell
  git add apps/core-api/src/main/java/com/yunjihai/rag/qa apps/core-api/src/test/java/com/yunjihai/rag/qa
  git commit -m "feat: orchestrate strictly grounded answers"
  ```

### Task 7: PostgreSQL 问答、证据与模型审计持久化

**Files:**
- Create: `apps/core-api/src/main/resources/db/migration/V004__grounded_qa.sql`
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/infrastructure/postgres/JdbcAuthorizedSpaceScope.java`
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/infrastructure/postgres/JdbcQaPersistenceAdapter.java`
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/infrastructure/postgres/JdbcModelCallAuditAdapter.java`
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/infrastructure/postgres/JdbcQaSourceAccessAdapter.java`
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/infrastructure/crypto/HmacQuestionDigest.java`
- Modify: `apps/core-api/src/main/java/com/yunjihai/rag/infrastructure/postgres/PostgresAdapterConfiguration.java`
- Test: `apps/core-api/src/test/java/com/yunjihai/rag/infrastructure/postgres/JdbcQaPersistenceIntegrationTest.java`
- Test: `apps/core-api/src/test/java/com/yunjihai/rag/infrastructure/postgres/JdbcQaSourceAccessIntegrationTest.java`
- Test: `apps/core-api/src/test/java/com/yunjihai/rag/infrastructure/postgres/GroundedQaMigrationIntegrationTest.java`
- Test: `apps/core-api/src/test/java/com/yunjihai/rag/infrastructure/crypto/HmacQuestionDigestTest.java`

**Interfaces:**
- Consumes: Task 6 的五个端口和 Task 2 的状态/结果类型。
- Produces: 事务化 JDBC 适配器；精确版本证据快照；不保存原始问题、证据正文、完整提示词或原始模型响应。

- [ ] **Step 1: 写迁移与仓储失败测试**

  断言表、外键、唯一键、状态 CHECK、终态字段约束和索引；原始问题列不存在；问题只保存 HMAC-SHA-256 摘要；同一 actor/幂等范围不会创建重复请求；revision 条件更新拒绝迟到结果；模型审计只保存批准字段；来源查询把 evidence 绑定到 request/actor/document/version/node；写入问答记录不会创建检索投影事件，问答历史不能进入知识索引。

- [ ] **Step 2: 运行集成测试并确认迁移缺失**

  Run: `.\mvnw.cmd -q -pl apps/core-api "-Dtest=GroundedQaMigrationIntegrationTest,JdbcQaPersistenceIntegrationTest,JdbcQaSourceAccessIntegrationTest,HmacQuestionDigestTest" test`

  Expected: FAIL，`V004` 和 JDBC 适配器尚不存在。

- [ ] **Step 3: 实现 `V004` 和 JDBC 适配器**

  创建 `qa_request`、`qa_evidence_snapshot`、`qa_answer`、`qa_model_call_audit`；回答正文可保存，但问题和证据正文不可保存。`HmacQuestionDigest` 使用 HmacSHA256 和仓库外至少 32 字节密钥，`toString()` 不暴露密钥。`JdbcAuthorizedSpaceScope` 一次查询当前用户全部可读空间且不静默截断；超过策略上限返回范围过大结果。所有问答状态推进、证据和最终结果使用 Spring 事务与 revision 条件更新。

- [ ] **Step 4: 运行 PostgreSQL 集成测试**

  Run: `.\mvnw.cmd -q -pl apps/core-api "-Dtest=GroundedQaMigrationIntegrationTest,JdbcQaPersistenceIntegrationTest,JdbcQaSourceAccessIntegrationTest,HmacQuestionDigestTest" test`

  Expected: PASS。

- [ ] **Step 5: 提交**

  ```powershell
  git add apps/core-api/src/main/resources/db/migration/V004__grounded_qa.sql apps/core-api/src/main/java/com/yunjihai/rag/infrastructure/postgres apps/core-api/src/main/java/com/yunjihai/rag/infrastructure/crypto apps/core-api/src/test/java/com/yunjihai/rag/infrastructure
  git commit -m "feat: persist grounded qa audit trail"
  ```

### Task 8: 问答、结果恢复与来源再次鉴权 API

**Files:**
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/qa/web/QaController.java`
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/qa/web/QaDtos.java`
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/qa/web/QaSourceController.java`
- Modify: `apps/core-api/src/main/java/com/yunjihai/rag/api/ApiExceptionHandler.java`
- Modify: `apps/core-api/src/main/java/com/yunjihai/rag/runtime/WorkerRuntimeConfiguration.java`
- Test: `apps/core-api/src/test/java/com/yunjihai/rag/qa/web/QaControllerTest.java`
- Test: `apps/core-api/src/test/java/com/yunjihai/rag/qa/web/QaSourceControllerTest.java`
- Test: `apps/core-api/src/test/java/com/yunjihai/rag/qa/web/QaIdempotencyIntegrationTest.java`

**Interfaces:**
- Consumes: Task 6 `GroundedQaService`、Task 7 JDBC 端口、现有 `IdempotencyService`、Session/CSRF 和文档读取授权。
- Produces: `POST /api/qa/questions`、`GET /api/qa/questions/{requestId}`、`GET /api/qa/questions/{requestId}/sources/{evidenceId}`。

- [ ] **Step 1: 写 HTTP 边界失败测试**

  断言 POST 请求体只接受 `question`，必须有 `Idempotency-Key` 和 CSRF；认证用户来自 Session；回答、拒答、失败响应结构不同；GET 只允许本人；不存在与无权读取统一不泄露；来源在每次打开时重新鉴权。并发相同键/相同载荷只调用一次服务，不同载荷复用键返回 `IDEMPOTENCY_REQUEST_MISMATCH`。

- [ ] **Step 2: 运行控制器测试并确认 404/缺类**

  Run: `.\mvnw.cmd -q -pl apps/core-api "-Dtest=QaControllerTest,QaSourceControllerTest,QaIdempotencyIntegrationTest" test`

  Expected: FAIL，端点尚不存在。

- [ ] **Step 3: 实现控制器、DTO 和统一错误映射**

  POST 用 actor + `qa.question.ask` + 幂等键建立 scope，请求哈希只在内存计算；返回 `requestId/status/answer/refusal/degraded/citations/completedAt`。来源响应只含当前仍可读的文档 ID、显示名、版本和定位，以及现有预览/下载相对 URL；授权失败不返回任何元数据。

- [ ] **Step 4: 运行 API 与安全测试**

  Run: `.\mvnw.cmd -q -pl apps/core-api "-Dtest=Qa*Test,SessionControllerTest,SecurityConfigurationTest,ApiExceptionHandlerTest" test`

  Expected: PASS。

- [ ] **Step 5: 提交**

  ```powershell
  git add apps/core-api/src/main/java/com/yunjihai/rag/qa/web apps/core-api/src/main/java/com/yunjihai/rag/api/ApiExceptionHandler.java apps/core-api/src/main/java/com/yunjihai/rag/runtime/WorkerRuntimeConfiguration.java apps/core-api/src/test/java/com/yunjihai/rag/qa/web
  git commit -m "feat: expose secure grounded qa api"
  ```

### Task 9: 模型配置、健康状态、指标与安全日志

**Files:**
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/infrastructure/model/ModelGatewayHealthIndicator.java`
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/qa/QaMetrics.java`
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/qa/MicrometerQaMetrics.java`
- Modify: `apps/core-api/src/main/java/com/yunjihai/rag/qa/GroundedQaService.java`
- Modify: `apps/core-api/src/main/resources/application-local.yml`
- Modify: `infra/compose/.env.example`
- Modify: `infra/compose/compose.yaml`
- Test: `apps/core-api/src/test/java/com/yunjihai/rag/infrastructure/model/ModelGatewayHealthIndicatorTest.java`
- Test: `apps/core-api/src/test/java/com/yunjihai/rag/qa/QaMetricsTest.java`
- Test: `apps/core-api/src/test/java/com/yunjihai/rag/acceptance/DependencyHealthIntegrationTest.java`

**Interfaces:**
- Consumes: Tasks 4–8 的生产 Bean 和结果代码。
- Produces: `modelGateway` readiness 组件、问答计数/阶段延迟/降级/Token/费用估算指标，以及仓库外环境变量配置。

- [ ] **Step 1: 写健康和指标失败测试**

  断言 AI 关闭时 readiness 明确为 `DISABLED` 而非伪健康；开启但配置缺失时启动失败；健康详情不含端点凭据；指标标签只使用有限枚举，不能含用户、问题、文档或模型响应；日志捕获不含问题、证据、回答、提示词和 API Key。

- [ ] **Step 2: 运行测试并确认失败**

  Run: `.\mvnw.cmd -q -pl apps/core-api "-Dtest=ModelGatewayHealthIndicatorTest,QaMetricsTest,DependencyHealthIntegrationTest" test`

  Expected: FAIL，健康与指标 Bean 尚不存在。

- [ ] **Step 3: 实现配置和可观测性**

  增加 `RAG_AI_ENABLED`、审批端点/主机、三个模型名、API Key、`RAG_QA_QUESTION_HMAC_KEY`、超时、Token、并发和日上限环境变量；示例文件只放空值/非秘密默认值。指标记录回答/拒答/失败率、各阶段耗时、降级、Token、脱敏数量和模型错误类别，日志只用 `requestId` 与标准化代码。

- [ ] **Step 4: 运行健康、Compose 配置和日志测试**

  Run: `.\mvnw.cmd -q -pl apps/core-api "-Dtest=ModelGatewayHealthIndicatorTest,QaMetricsTest,DependencyHealthIntegrationTest" test`

  Run: `docker compose --env-file infra/compose/.env.example -f infra/compose/compose.yaml config --quiet`

  Expected: 两条命令均 PASS，Compose 输出中不存在真实凭据。

- [ ] **Step 5: 提交**

  ```powershell
  git add apps/core-api/src/main/java/com/yunjihai/rag/infrastructure/model apps/core-api/src/main/java/com/yunjihai/rag/qa apps/core-api/src/main/resources/application-local.yml infra/compose
  git commit -m "feat: add qa operational safeguards"
  ```

### Task 10: Vue 单轮问答与引用界面

**Files:**
- Create: `apps/web/src/api/qa.ts`
- Create: `apps/web/src/components/QaAnswerPanel.vue`
- Create: `apps/web/src/components/QaCitationList.vue`
- Create: `apps/web/src/views/QaView.vue`
- Create: `apps/web/src/views/QaView.test.ts`
- Create: `apps/web/src/components/QaCitationList.test.ts`
- Modify: `apps/web/src/router/index.ts`
- Modify: `apps/web/src/App.vue`
- Modify: `apps/web/src/App.test.ts`
- Test: `apps/web/e2e/knowledge-foundation.spec.ts`

**Interfaces:**
- Consumes: Task 8 的三个 API；只发送 `{ question }` 和 `Idempotency-Key`。
- Produces: `/qa` 中文页面、主导航“知识问答”、回答/拒答/故障/降级状态和再次鉴权的来源入口。

- [ ] **Step 1: 使用 `web-ui-quality-gate` 检查现有页面惯例，并写 UI 失败测试**

  测试提交期间防重复、幂等键每次新问题生成一次、回答关键声明显示引用、降级提示、严格拒答与系统故障分开、刷新按 requestId 恢复、来源 404 不泄露信息、长文/长标题不横向溢出、窄屏引用卡片单列、加载与空状态可访问。

- [ ] **Step 2: 运行 Vitest 确认失败**

  Run: `corepack.cmd pnpm --dir apps/web test --run src/views/QaView.test.ts src/components/QaCitationList.test.ts src/App.test.ts`

  Expected: FAIL，页面和组件尚不存在。

- [ ] **Step 3: 实现 API 类型、页面、引用组件、路由和导航**

  页面文案明确“只依据已发布且你有权访问的全部知识空间”；不展示模型选择、空间白名单、提示词或被权限过滤的候选。来源按钮先调用再次鉴权 API，成功后才打开现有文档预览路由。

- [ ] **Step 4: 运行前端单测、构建与 lint**

  Run: `corepack.cmd pnpm --dir apps/web test --run`

  Run: `corepack.cmd pnpm --dir apps/web build`

  Run: `corepack.cmd pnpm --dir apps/web lint`

  Expected: 全部 PASS，ESLint 零警告。

- [ ] **Step 5: 增加并运行 Playwright 问答场景**

  在现有 E2E 中增加：成功回答、拒答、模型故障、降级、来源撤权和移动宽度布局。Run: `corepack.cmd pnpm --dir apps/web exec playwright test`

  Expected: PASS。

- [ ] **Step 6: 提交**

  ```powershell
  git add apps/web
  git commit -m "feat: add grounded knowledge qa interface"
  ```

### Task 11: 黄金集、离线评测与硬门禁

**Files:**
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/qa/evaluation/EvaluationCase.java`
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/qa/evaluation/EvaluationDataset.java`
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/qa/evaluation/EvaluationMetrics.java`
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/qa/evaluation/EvaluationGate.java`
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/qa/evaluation/EvaluationRepository.java`
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/qa/evaluation/GroundedQaEvaluationService.java`
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/qa/web/QaEvaluationAdminController.java`
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/infrastructure/postgres/JdbcEvaluationRepository.java`
- Create: `apps/core-api/src/main/resources/db/migration/V005__grounded_qa_evaluation.sql`
- Create: `contracts/qa/golden-dataset-v1.json`
- Test: `apps/core-api/src/test/java/com/yunjihai/rag/qa/evaluation/EvaluationGateTest.java`
- Test: `apps/core-api/src/test/java/com/yunjihai/rag/qa/evaluation/GroundedQaEvaluationServiceTest.java`
- Test: `apps/core-api/src/test/java/com/yunjihai/rag/qa/evaluation/GoldenDatasetContractTest.java`
- Test: `apps/core-api/src/test/java/com/yunjihai/rag/infrastructure/postgres/JdbcEvaluationRepositoryIntegrationTest.java`
- Test: `apps/core-api/src/test/java/com/yunjihai/rag/qa/web/QaEvaluationAdminControllerTest.java`

**Interfaces:**
- Consumes: Task 6 的生产 `GroundedQaService`，不得建立绕过授权/脱敏的评测链路。
- Produces: `GroundedQaEvaluationService.run(UUID datasetId, String codeVersion, String policyVersion, String modelConfigVersion)`、`EvaluationGate.assess(EvaluationMetrics)`，以及仅系统管理员可用的 `POST /api/admin/qa/evaluation-runs` 和 `GET /api/admin/qa/evaluation-runs/{runId}`。

- [ ] **Step 1: 写指标、版本和硬门禁失败测试**

  断言：数据集少于 50 条不可激活；每例必须有评测身份、期望证据/禁止文档或明确 `shouldRefuse`；除零安全；泄露大于 0 或引用有效率小于 100% 立即失败；Recall@10 < 85%、支持率 < 90% 或正确拒答率 < 90% 失败；边界值通过；数据集/代码/策略/提示词/脱敏/模型版本完整记录。评测管理 API 对非系统管理员统一隐藏，且请求不能提交任意评测用户 ID、跳过授权或降低门槛。

- [ ] **Step 2: 运行评测测试并确认失败**

  Run: `.\mvnw.cmd -q -pl apps/core-api "-Dtest=EvaluationGateTest,GroundedQaEvaluationServiceTest,JdbcEvaluationRepositoryIntegrationTest,QaEvaluationAdminControllerTest" test`

  Expected: FAIL，评测模块和 `V005` 缺失。

- [ ] **Step 3: 实现评测领域、JDBC 和同链路运行器**

  Recall@10 按期望节点集合计算；引用有效率逐引用计算；支持率按有答案案例中通过 `CitationSupportVerifier` 的声明计算；拒答率只统计 `shouldRefuse=true` 案例；泄露检查任何禁止文档/节点是否出现在证据、模型请求捕获或响应。

- [ ] **Step 4: 创建 50 条虚构脱敏黄金案例**

  `golden-dataset-v1.json` 覆盖 Word/PDF 页码、PPT 幻灯片、Excel 单元格、Markdown 标题、PNG OCR、跨文档、同义词、缩写、冲突、应拒答和权限陷阱；所有公司、人名、号码和文档均为明显虚构值。添加加载测试断言恰好或不少于 50 条且 schema/ID 唯一。

- [ ] **Step 5: 运行评测和契约测试**

  Run: `.\mvnw.cmd -q -pl apps/core-api "-Dtest=*Evaluation*,*GoldenDataset*" test`

  Expected: PASS，阈值边界和 50 条数据集校验通过。

- [ ] **Step 6: 提交**

  ```powershell
  git add apps/core-api/src/main/java/com/yunjihai/rag/qa/evaluation apps/core-api/src/main/java/com/yunjihai/rag/qa/web/QaEvaluationAdminController.java apps/core-api/src/main/java/com/yunjihai/rag/infrastructure/postgres/JdbcEvaluationRepository.java apps/core-api/src/main/resources/db/migration/V005__grounded_qa_evaluation.sql apps/core-api/src/test contracts/qa
  git commit -m "feat: add grounded qa quality gates"
  ```

### Task 12: 真实基础设施纵向验收与撤权竞态

**Files:**
- Create: `apps/core-api/src/test/java/com/yunjihai/rag/acceptance/GroundedQaAcceptanceTest.java`
- Create: `apps/core-api/src/test/java/com/yunjihai/rag/acceptance/GroundedQaSecurityIntegrationTest.java`
- Create: `apps/core-api/src/test/java/com/yunjihai/rag/acceptance/GroundedQaModelGatewayIntegrationTest.java`
- Modify: `apps/core-api/src/test/java/com/yunjihai/rag/acceptance/CoreKnowledgeManagementAcceptanceTest.java`
- Modify: `scripts/verify.ps1`

**Interfaces:**
- Consumes: Tasks 1–11 的完整生产装配；WireMock 充当审批模型网关，PostgreSQL/OpenSearch/AIStor 使用现有真实容器。
- Produces: 可重复证明“全部有权空间—最终鉴权—脱敏—回答—引用—撤权”的自动化证据。

- [ ] **Step 1: 写纵向验收失败测试**

  创建两个空间和有权/无权/撤权用户，发布至少两种格式文档并投影；断言问题在全部有权空间召回、无权空间从不进入 WireMock 请求；回答引用可定位；检索后撤权使生成终止或结果拒答；回答后撤权使来源接口统一拒绝；并发幂等只有一次 Chat 调用；模型超时、限流、非法引用、脱敏阻断均关闭。

- [ ] **Step 2: 运行验收测试并确认存在集成缺口**

  Run: `.\mvnw.cmd -q -pl apps/core-api "-Dtest=GroundedQaAcceptanceTest,GroundedQaSecurityIntegrationTest,GroundedQaModelGatewayIntegrationTest" test`

  Expected: 首次 FAIL，暴露生产装配或事务边界缺口，而不是跳过容器测试。

- [ ] **Step 3: 只修复验收暴露的装配与竞态问题**

  不放宽严格拒答、授权、脱敏或质量阈值；如需改接口，先补对应较低层回归测试。确保模型返回前再次确认请求仍非终态，并在保存答案前按证据涉及版本进行最后一次批量授权复核。

- [ ] **Step 4: 运行完整 Java 验证**

  Run: `.\mvnw.cmd -q clean verify`

  Expected: 所有 Java 单元、集成和验收测试 PASS，且没有真实云端调用。

- [ ] **Step 5: 提交**

  ```powershell
  git add apps/core-api/src/test scripts/verify.ps1
  git add -u apps/core-api/src/main
  git commit -m "test: verify grounded qa security flow"
  ```

### Task 13: 中文运行手册、配置说明与整库验收

**Files:**
- Create: `docs/runbooks/grounded-qa.md`
- Modify: `README.md`
- Modify: `docs/runbooks/core-knowledge-management.md`
- Modify: `docs/runbooks/retrieval-production-adapters.md`
- Modify: `docs/superpowers/specs/2026-10-10-grounded-qa-vertical-slice-design.md`
- Modify: `docs/superpowers/plans/2026-10-10-grounded-qa-vertical-slice.md`

**Interfaces:**
- Consumes: 所有前置任务的真实命令、配置名、状态码和运维边界。
- Produces: 不依赖对话上下文即可启动、验证、降级、评测、排障和回滚的中文手册。

- [ ] **Step 1: 写运行手册和 README 状态**

  覆盖：默认关闭；审批端点/模型/密钥的仓库外注入；禁止重定向和出站白名单；启用 Embedding 后必须重建向量索引；BM25/RRF 降级；严格拒答与系统故障；Token/并发/日上限；50 条黄金集与五项门槛；模型配置回滚；来源撤权；日志禁项；仍未完成的真实审批、容量、安全、灾备和钉钉事项。

- [ ] **Step 2: 更新规格和计划状态并扫描敏感内容**

  将规格状态更新为“已实施，待最终验收”，勾选实际完成的计划项。Run: `rg -n "sk-[A-Za-z0-9]|api[_-]?key\s*[:=]\s*[^${]|password\s*[:=]\s*[^${]" . -g "!node_modules/**" -g "!.git/**"`

  Expected: 不出现真实凭据；示例变量只引用环境变量或明确占位值。

- [ ] **Step 3: 运行整库验证**

  Run: `powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/verify.ps1`

  Expected: Compose config、Java `clean verify`、Vue 单测/构建/lint、Python pytest/ruff/mypy 全部 PASS。

- [ ] **Step 4: 运行前端 E2E**

  Run: `corepack.cmd pnpm --dir apps/web exec playwright test`

  Expected: 全部 PASS，包括知识问答与撤权来源场景。

- [ ] **Step 5: 检查提交内容并提交文档**

  Run: `git diff --check`

  Expected: 无空白错误；`git status --short` 只含本任务文档变更。

  ```powershell
  git add README.md docs
  git commit -m "docs: document grounded qa operations"
  ```

- [ ] **Step 6: 进入分支完成流程**

  使用 `superpowers:verification-before-completion` 复核刚运行的证据，再使用 `superpowers:requesting-code-review` 做整分支评审；所有 Critical/Important 问题关闭后才使用 `superpowers:finishing-a-development-branch`。不得自行推送、合并或创建试点标签。
