# 可信检索实施计划

> **面向执行者：** 必须使用 `superpowers:subagent-driven-development`（推荐）或 `superpowers:executing-plans` 逐任务执行。

**目标：** 交付权限安全的混合检索，包括可重建 OpenSearch 投影、RRF、重排、来源引用和可重复离线评估。

**架构：** 已发布的统一文档节点从 PostgreSQL 投影到 OpenSearch。Spring Boot 展开当前用户主体，在 BM25/向量检索前加 ACL 和元数据过滤，融合并重排后，返回证据前再用 PostgreSQL 复核。

**技术栈：** Java 21、Spring Boot 4.1.1、Spring AI 2.0.1、PostgreSQL、OpenSearch 3.x、Testcontainers、JUnit 5、Vue 3、Vitest、Playwright。

**设计规格：** `docs/superpowers/specs/2026-09-24-enterprise-rag-solution-generation-design.md`

## 全局约束

- 仅在 `pilot-foundation-v1` 且底座验证全绿后开始。
- PostgreSQL 是权威源；删除并重建 OpenSearch 不得丢失业务数据。
- 仅检索当前、已发布、在有效期内的版本。
- 每个子查询都必须带 ACL；返回前必须批量二次授权。
- 默认参数可配：全文 50、向量 50、融合后 30、最终证据 8–15。
- 引用必须定位到文档版本与页、幻灯片、表格范围或结构节点。

## 评审重点

- ACL 在搜索过程中变更时，旧候选不得泄露（任务 5）。
- 删除、归档、过期和被替代版本必须在修复/重建后消失（任务 2–3）。
- 向量失败时仍可用全文，全文无结果时仍可用语义（任务 4）。
- 重复片段或单一文档不得挤占全部证据（任务 5）。
- 缺页码/坐标/表格范围的引用不得伪装成精确定位（任务 6）。

---

### Task 1｜任务 1：检索领域契约

**文件：** 创建 `RetrievalQuery`、`Candidate`、`Evidence`、`EvidencePack`、`RetrievalService` 及契约测试。

**产出接口：**

```java
public interface RetrievalService {
    EvidencePack retrieve(RetrievalQuery query);
}
public sealed interface SourceLocator permits PageLocator, SlideLocator, TableLocator, MarkdownLocator {}
```

- [ ] **第 1 步：先测试“证据必须有已发布版本和来源定位”及数量上限**
- [ ] **第 2 步：实现不含 OpenSearch/供应商类型的纯领域记录**
- [ ] **第 3 步：验证并提交**

```powershell
.\mvnw.cmd -pl apps/core-api -Dtest=RetrievalContractTest test
git add apps/core-api/src/main/java/com/yunjihai/rag/retrieval apps/core-api/src/test/java/com/yunjihai/rag/retrieval
git commit -m "feat: define trusted retrieval contracts"
```

### Task 2｜任务 2：切分与投影 Outbox

**文件：** 创建 `V002__retrieval_projection.sql`、`ChunkingService`、`ProjectionOutbox`、`ProjectionEventHandler` 及测试。

**产出接口：** 确定性 `RetrievalChunk` 和 `UPSERT_VERSION|DELETE_VERSION|REBUILD_SPACE` 事件。

- [ ] **第 1 步：测试标题与子段落、PPT 单页、带表头行组、Markdown 代码块及稳定 Chunk ID**
- [ ] **第 2 步：测试新版发布在同一事务中写入新版 UPSERT 和旧版 DELETE**
- [ ] **第 3 步：片段携带父节点、标题路径、定位、文档/版本、元数据、有效期和主体权限**
- [ ] **第 4 步：验证并提交**

```powershell
.\mvnw.cmd -pl apps/core-api -Dtest='ChunkingServiceTest,ProjectionOutboxTest' test
git add apps/core-api/src/main/java/com/yunjihai/rag/retrieval/index apps/core-api/src/main/resources/db/migration apps/core-api/src/test/java/com/yunjihai/rag/retrieval/index
git commit -m "feat: create deterministic retrieval projection"
```

### Task 3｜任务 3：OpenSearch 索引、投影器与重建

**文件：** 创建 `infra/opensearch/chunks-v1.json`、`hybrid-search-pipeline-v1.json`、`OpenSearchProjector`、`RebuildIndexService` 及 Testcontainers 测试；更新 Compose。

**产出接口：** 读别名 `rag-chunks-read`、物理索引 `rag-chunks-v1-*`、幂等增删改和零停机别名切换。

- [ ] **第 1 步：测试重复 UPSERT、归档删除、ACL 更新、旧版清除和空集群重建**
- [ ] **第 2 步：显式定义中文文本、关键字段、日期、主体数组、向量和定位 Mapping，禁用领域动态 Mapping**
- [ ] **第 3 步：重建时新建物理索引、全量加载、验证计数/样本查询、原子切别名，最后删旧索引**
- [ ] **第 4 步：验证并提交**

```powershell
.\mvnw.cmd -pl apps/core-api -Dtest=OpenSearchProjectionTest test
git add infra/opensearch infra/compose apps/core-api/src/main/java/com/yunjihai/rag/retrieval/index apps/core-api/src/test/java/com/yunjihai/rag/retrieval/index
git commit -m "feat: project published chunks into OpenSearch"
```

### Task 4｜任务 4：混合召回与可降级行为

**文件：** 创建 `LexicalRetriever`、`VectorRetriever`、`HybridRecallService`、`EmbeddingPort` 及测试。

- [ ] **第 1 步：测试 Embedding 超时时仍返回全文结果，且每个子查询都带权限/发布/有效期过滤**
- [ ] **第 2 步：在有界超时内并行执行两条召回，使用 RRF 融合**
- [ ] **第 3 步：单分支失败返回健康分支并标记降级；双失败返回 `RetrievalUnavailable`**
- [ ] **第 4 步：验证并提交**

```powershell
.\mvnw.cmd -pl apps/core-api -Dtest=HybridRecallServiceTest test
git add apps/core-api/src/main/java/com/yunjihai/rag/retrieval/search apps/core-api/src/main/java/com/yunjihai/rag/model apps/core-api/src/test/java/com/yunjihai/rag/retrieval/search
git commit -m "feat: add permission-filtered hybrid recall"
```

### Task 5｜任务 5：重排、多样性和 PostgreSQL 二次授权

**文件：** 创建 `RerankerPort`、`EvidenceSelectionService`、`TrustedRetrievalService` 及测试。

- [ ] **第 1 步：测试召回后 ACL 被撤销的候选必须被剔除**
- [ ] **第 2 步：测试文本近重去重与单文档默认最多 3 个最终片段**
- [ ] **第 3 步：重排最多 30 个候选，对最终文档/版本做一次批量 PostgreSQL 授权查询**
- [ ] **第 4 步：验证并提交**

```powershell
.\mvnw.cmd -pl apps/core-api -Dtest=TrustedRetrievalServiceTest test
git add apps/core-api/src/main/java/com/yunjihai/rag/retrieval apps/core-api/src/test/java/com/yunjihai/rag/retrieval
git commit -m "feat: select diverse reauthorized evidence"
```

### Task 6｜任务 6：引用 API 与来源查看器

**文件：** 创建 `RetrievalController`、`CitationController`、`retrieval.ts`、`EvidenceList.vue`、`SourceLocatorViewer.vue` 及 API/UI 测试。

**产出接口：** `POST /api/v1/retrieval/search` 和 `GET /api/v1/citations/{evidenceId}`。

- [ ] **第 1 步：拒绝缺页码的页定位、缺范围的表格定位、缺页码的幻灯片，及授权已撤销的引用**
- [ ] **第 2 步：API 返回证据、标题、版本、定位和短时授权预览 URL**
- [ ] **第 3 步：Vue 高亮页面坐标、幻灯片、工作表范围或 Markdown 标题，禁止客户端自行拼对象 URL**
- [ ] **第 4 步：验证并提交**

```powershell
.\mvnw.cmd -pl apps/core-api -Dtest=CitationControllerTest test
corepack pnpm --dir apps/web test --run SourceLocatorViewer
git add apps/core-api/src/main/java/com/yunjihai/rag/retrieval/api apps/core-api/src/test apps/web/src
git commit -m "feat: expose traceable source citations"
```

### Task 7｜任务 7：版本化离线评估与发布门槛

**文件：** 创建 `evaluation/retrieval/golden-v1.jsonl`、说明、`RetrievalEvaluationTest`、`RetrievalMetrics`、`run-retrieval-eval.ps1` 和运维手册。

- [ ] **第 1 步：提交最小黄金集，包含查询、用户样本、相关节点、禁止文档和期望定位类型**

```json
{"caseId":"case-001","query":"查找制造业客户的实施阶段","userFixture":"sales-a","relevantNodeIds":["node-a"],"forbiddenDocumentIds":["finance-secret"],"locatorType":"PAGE"}
```

- [ ] **第 2 步：固定记录 Recall@10、nDCG@10、定位有效率、越权命中数、模型/索引/管线/配置哈希**
- [ ] **第 3 步：CI 门槛要求越权命中 0、定位有效率 100%且质量不低于已批准基线**
- [ ] **第 4 步：整体验证、提交并打标签**

```powershell
.\mvnw.cmd verify
corepack pnpm --dir apps/web test --run
powershell -ExecutionPolicy Bypass -File scripts/run-retrieval-eval.ps1
git add evaluation scripts docs/runbooks apps infra
git commit -m "test: gate releases on trusted retrieval quality"
git tag pilot-retrieval-v1
```
