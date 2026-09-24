# 知识底座实施计划

> **面向执行者：** 必须使用 `superpowers:subagent-driven-development`（推荐）或 `superpowers:executing-plans` 逐任务执行。使用 `- [ ]` 跟踪每一步。

**目标：** 交付可发布的知识底座：授权用户能完成文档上传、解析、编目、授权、发布、查看、归档和审计。

**架构：** Spring Boot 模块化单体负责身份、ACL、知识生命周期、存储编排和审计。无状态 Python Worker 将文件转为版本化统一 JSON。PostgreSQL 是事实源，MinIO 保存不可变文件，Vue 只呈现服务端已授权操作。

**技术栈：** Java 21、Spring Boot 4.1.1、Maven、Spring Security、JPA、Flyway、PostgreSQL、MinIO、Testcontainers、Python 3.12+、FastAPI、Docling、PaddleOCR、pytest、Vue 3、TypeScript、Vitest、Playwright、Docker Compose。

**设计规格：** `docs/superpowers/specs/2026-09-24-enterprise-rag-solution-generation-design.md`

## 全局约束

- Java 包根路径为 `com.yunjihai.rag`；修改前必须先写 ADR。
- REST API 以 `/api/v1` 为根，错误使用 RFC 9457 `ProblemDetail`。
- ID 使用兼容 UUIDv7 的 UUID，时间使用 UTC `Instant`。
- PostgreSQL 是权威源；MinIO 对象不可变，以对象键和 SHA-256 标识。
- 权限默认拒绝；知识空间是授权上限，文档只能收紧权限。
- Python 只接收对象流和任务元数据，不拥有数据库凭据和用户权限上下文。
- 只有当前 `PUBLISHED` 版本对用户可见。

## 评审重点

- 相同字节重复上传必须复用 Blob，不得创建重复活跃版本（任务 6）。
- 解析崩溃或返回非法结果时，文档不得发布，原文必须保留（任务 7–8）。
- 用户移出部门后，下次组织同步与缓存失效后必须失去访问权（任务 4）。
- 被停用用户的旧会话必须立即失效（任务 4）。
- 超大、不支持、加密或恶意文件必须在发布前拒绝，不留孤儿对象（任务 6）。

---

### 任务 0：试点章程与数据准入

**文件：**
- 创建：`docs/pilot/charter.md`
- 创建：`docs/pilot/baseline.md`
- 创建：`docs/pilot/knowledge-inventory.csv`
- 创建：`docs/pilot/model-provider-approval.md`
- 创建：`evaluation/fixtures/manifest-v1.json`

**产出接口：** 签字确认的范围/指标基线、300–1000 份资料清单、脱敏评估样本清单和云模型数据边界。

- [ ] **第 1 步：记录试点章程**

写明真实负责人、试点用户、范围、排除项、日期、周复盘时间和停止条件。不允许用角色占位代替真实责任人。

- [ ] **第 2 步：测量当前流程**

对至少 10 份近期方案记录需求接收、初稿、定稿时间、参与人、返工次数和查找资料时长。只提交汇总数据和脱敏方法。

- [ ] **第 3 步：盘点可信知识**

CSV 列固定为 `inventory_id,owner,source_location,format,industry,product,year,classification,quality_status,permission_scope,expiry_date,selected_for_pilot`。排除无负责人、过期、重复或法务禁止的资料。

- [ ] **第 4 步：批准模型/数据边界**

记录已批准供应商/模型、地域、保留期、训练使用政策、删除、禁止数据级别和应急联系人。未批准时设为 `external_model_allowed=false`。

- [ ] **第 5 步：提交准入包**

```powershell
git add docs/pilot evaluation/fixtures/manifest-v1.json
git commit -m "docs: establish RAG pilot baseline and data gate"
```

如业务负责人、基线、可信资料清单或供应商决策任一缺失，必须停止。

### 任务 1：仓库与构建骨架

**文件：** 创建根 `pom.xml`、Maven Wrapper、`apps/core-api`、`apps/web`、`workers/document-parser`、`pnpm-workspace.yaml` 和 `.github/workflows/verify.yml`。

**产出接口：** Maven 模块 `apps/core-api`、pnpm 包 `@yunjihai/rag-web`、Python 包 `parser_worker` 和统一验证命令。

- [ ] **第 1 步：先写失败的 Spring 启动测试**

```java
@SpringBootTest
class ArchitectureSmokeTest { @Test void applicationStarts() {} }
```

- [ ] **第 2 步：创建工程**

锁定 Spring Boot `4.1.1`、Spring AI BOM `2.0.1`和 Java `21`；加入 Web、Validation、Security、JPA、Actuator、Flyway、PostgreSQL 和 Testcontainers。Vue 启用 TypeScript、Router、Pinia、Vitest、ESLint、Playwright；Python 启用 `src` 布局、Ruff、mypy、pytest、FastAPI、Docling 和 PaddleOCR，提交锁文件。

- [ ] **第 3 步：验证并提交**

```powershell
.\mvnw.cmd -pl apps/core-api test
corepack pnpm --dir apps/web test --run
corepack pnpm --dir apps/web build
uv run --project workers/document-parser pytest
uv run --project workers/document-parser ruff check .
git add pom.xml .mvn mvnw mvnw.cmd apps workers pnpm-workspace.yaml .github
git commit -m "build: scaffold enterprise RAG workspace"
```

### 任务 2：本地基础设施与数据库基线

**文件：** 创建 `infra/compose/compose.yaml`、应用配置、`V001__foundation.sql` 和 `PostgresIntegrationTest.java`。

**产出接口：** `app_user`、`org_unit`、`org_membership`、`knowledge_space`、`acl_entry`、`document`、`document_version`、`ingestion_job`、`audit_event` 表与 `rag-documents` 存储桶。

- [ ] **第 1 步：写 Testcontainers 迁移测试，确认表不存在时失败**
- [ ] **第 2 步：实现 UUID 主键、`timestamptz`、唯一约束、外键、状态检查和乐观版本列**
- [ ] **第 3 步：加入 PostgreSQL/MinIO Compose 健康检查和一次性建桶任务**
- [ ] **第 4 步：验证并提交**

```powershell
docker compose -f infra/compose/compose.yaml up -d postgres minio minio-init
.\mvnw.cmd -pl apps/core-api -Dtest=PostgresIntegrationTest test
git add infra/compose apps/core-api/src/main/resources apps/core-api/src/test
git commit -m "feat: add foundation data infrastructure"
```

### 任务 3：主体与 ACL 领域

**文件：** 创建 `PrincipalRef`、`EffectivePrincipalSet`、`AccessDecisionService` 及对应测试。

**产出接口：**

```java
boolean canRead(UUID userId, UUID spaceId, UUID documentId, Instant at);
void requireRead(UUID userId, UUID spaceId, UUID documentId, Instant at);
Set<PrincipalRef> forUser(UUID userId);
```

- [ ] **第 1 步：先写默认拒绝、空间/文档交集、归档/过期拒绝测试**
- [ ] **第 2 步：实现用户、部门及祖先部门、角色和项目组展开**
- [ ] **第 3 步：使用单次仓库查询完成交集判定，验证并提交**

```powershell
.\mvnw.cmd -pl apps/core-api -Dtest='*Access*Test' test
git add apps/core-api/src/main/java/com/yunjihai/rag/access apps/core-api/src/test/java/com/yunjihai/rag/access
git commit -m "feat: enforce default-deny knowledge ACL"
```

### 任务 4：身份边界与钉钉组织同步

**文件：** 创建 `DingTalkClient`、`DingTalkIdentityAdapter`、`OrganizationSyncService`、`SessionRevocationService` 及 WireMock/安全测试。

**产出接口：**

```java
ExternalIdentity exchangeAuthorizationCode(String code);
List<ExternalDepartment> listDepartments();
List<ExternalUser> listUsers(String departmentExternalId);
void synchronize(OrganizationSnapshot snapshot);
```

- [ ] **第 1 步：写移出部门后失权、停用用户旧会话失效测试**
- [ ] **第 2 步：实现带超时和类型错误的钉钉适配器；凭据只来自密钥配置**
- [ ] **第 3 步：实现事务性快照对账；不完整上游快照不得删除现有成员关系**
- [ ] **第 4 步：测试超时与分页不完整，然后提交**

```powershell
.\mvnw.cmd -pl apps/core-api -Dtest='*Identity*Test,*Organization*Test,*DisabledUser*Test' test
git add apps/core-api/src/main/java/com/yunjihai/rag/identity apps/core-api/src/test/java/com/yunjihai/rag/identity
git commit -m "feat: synchronize DingTalk identity and membership"
```

### 任务 5：知识空间与不可变文档生命周期

**文件：** 创建 `KnowledgeSpaceService`、`DocumentService`、`DocumentStatus`、`KnowledgeController` 及生命周期测试。

**产出接口：** `createSpace`、`createDraft`、`submitForReview`、`publish`、`archive`、`getAuthorizedDocument` 和 `/api/v1/knowledge-spaces/**`。

- [ ] **第 1 步：测试 `DRAFT -> IN_REVIEW -> PUBLISHED -> ARCHIVED` 及未解析不得发布**
- [ ] **第 2 步：实现不可覆盖版本与原子切换当前发布版本**
- [ ] **第 3 步：未授权的列表、详情、解析内容、下载统一返回 404，不泄露存在性**
- [ ] **第 4 步：验证并提交**

```powershell
.\mvnw.cmd -pl apps/core-api -Dtest='*Document*Test,*Knowledge*Test' test
git add apps/core-api/src/main/java/com/yunjihai/rag/knowledge apps/core-api/src/test/java/com/yunjihai/rag/knowledge
git commit -m "feat: add immutable knowledge lifecycle"
```

### 任务 6：安全上传、Blob 去重与对象存储

**文件：** 创建 `UploadPolicy`、`BlobStore`、`MinioBlobStore`、`UploadService` 及对应测试。

**产出接口：**

```java
DocumentVersionId accept(UploadCommand command);
StoredBlob putIfAbsent(String sha256, InputStream stream, long length, MediaType mediaType);
```

- [ ] **第 1 步：测试相同字节复用 Blob；超大、扩展名/MIME 不一致、空、加密 PDF、ZIP 炸弹和恶意文件不留 Blob**
- [ ] **第 2 步：流式写入隔离区，同时计算 SHA-256 和限制字节数**
- [ ] **第 3 步：通过 MIME/恶意扫描后才移入内容寻址键；失败必须补偿删除隔离对象**
- [ ] **第 4 步：测试上传后数据库失败的补偿，验证并提交**

```powershell
.\mvnw.cmd -pl apps/core-api -Dtest='*Upload*Test,*BlobStore*Test' test
git add apps/core-api/src/main/java/com/yunjihai/rag/ingestion apps/core-api/src/test/java/com/yunjihai/rag/ingestion
git commit -m "feat: store validated immutable document blobs"
```

### 任务 7：统一解析契约与无状态 Worker

**文件：** 创建 `parser-job-v1.schema.json`、`canonical-document-v1.schema.json`、FastAPI/Docling 适配器、样本文件、Java `ParserClient` 及双端契约测试。

**产出接口：** `CanonicalDocumentV1`，节点包含 `nodeId`、`kind`、`text`、`parentNodeId`、`page`、`bbox`、`tableCells`、`confidence` 和结构化错误。

- [ ] **第 1 步：在 Java/Python 两端先写 JSON Schema 契约测试**
- [ ] **第 2 步：实现 `/v1/parse`，禁止远程拉取，严格限制页数/大小，日志不得记录正文**
- [ ] **第 3 步：测试非法、加密、超时和复杂表格；错误固定为 `UNSUPPORTED|ENCRYPTED|LIMIT_EXCEEDED|CONVERSION_FAILED|TIMEOUT`**
- [ ] **第 4 步：Java 客户端校验成功响应的 Schema，然后提交**

```powershell
uv run --project workers/document-parser pytest
uv run --project workers/document-parser ruff check .
.\mvnw.cmd -pl apps/core-api -Dtest='ParserContractTest,*ParserClientTest' test
git add contracts/parser workers/document-parser apps/core-api/src/main/java/com/yunjihai/rag/ingestion apps/core-api/src/test/java/com/yunjihai/rag/ingestion
git commit -m "feat: add versioned document parser contract"
```

### 任务 8：可恢复入库编排与审计

**文件：** 创建 `IngestionJobService`、`IngestionWorker`、`IngestionState`、`AuditService` 及崩溃恢复/审计测试。

**产出接口：** `enqueue`、`claimNext`、`complete`、`fail` 和只追加审计事件。

- [ ] **第 1 步：测试租约过期恢复和非法解析结果不得发布**
- [ ] **第 2 步：用 `FOR UPDATE SKIP LOCKED` 实现 PostgreSQL 租约队列，持久化尝试次数和租约截止时间**
- [ ] **第 3 步：`complete` 事务性保存节点并转为 `IN_REVIEW`，绝不直接发布**
- [ ] **第 4 步：审计上传、解析、编辑、审核、发布、归档、ACL、查看和下载，不写敏感正文**
- [ ] **第 5 步：验证并提交**

```powershell
.\mvnw.cmd -pl apps/core-api -Dtest='*Ingestion*Test,*Audit*Test' test
git add apps/core-api/src/main/java/com/yunjihai/rag/ingestion apps/core-api/src/main/java/com/yunjihai/rag/audit apps/core-api/src/test
git commit -m "feat: orchestrate recoverable document ingestion"
```

### 任务 9：知识管理 Web 流程

**文件：** 创建前端路由、API、会话 Store、知识空间、上传、审核、ACL 组件及 Vitest/Playwright 测试。

- [ ] **第 1 步：先测试“解析未成功或元数据不完整时禁用发布”**
- [ ] **第 2 步：UI 仅使用服务端 `canEdit|canPublish|canManageAcl`，不复制 ACL 规则**
- [ ] **第 3 步：展示解析进度、类型化错误、原文预览、必填元数据、权限范围和不可变版本历史**
- [ ] **第 4 步：Playwright 验证未授权用户不能发现文档，然后提交**

```powershell
corepack pnpm --dir apps/web test --run
corepack pnpm --dir apps/web build
corepack pnpm --dir apps/web exec playwright test e2e/knowledge-foundation.spec.ts
git add apps/web
git commit -m "feat: add knowledge publishing workflow"
```

### 任务 10：底座验收与运维手册

**文件：** 创建 `FoundationAcceptanceTest.java`、`docs/runbooks/knowledge-foundation.md`、`docs/adr/0001-java-business-python-parser-boundary.md`，更新 `README.md`。

- [ ] **第 1 步：写端到端验收测试**

创建空间和授权，上传代表性 PDF，执行解析，强制人工审核后发布；验证授权用户列表/详情/下载，未授权用户全部 404；归档后双方均不再看到生产文档，审计仍在。

- [ ] **第 2 步：编写启停、健康检查、组织同步、失败任务重放、手工恢复、归档/删除、备份、密钥轮换和日志脱敏手册**
- [ ] **第 3 步：运行整体验证**

```powershell
.\mvnw.cmd verify
uv run --project workers/document-parser pytest
uv run --project workers/document-parser ruff check .
corepack pnpm --dir apps/web test --run
corepack pnpm --dir apps/web build
corepack pnpm --dir apps/web exec playwright test e2e/knowledge-foundation.spec.ts
docker compose -f infra/compose/compose.yaml config --quiet
```

- [ ] **第 4 步：提交并打标签**

```powershell
git add README.md docs apps workers contracts infra
git commit -m "docs: complete knowledge foundation acceptance"
git tag pilot-foundation-v1
```
