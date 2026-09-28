# 方案工作台业务内核实施计划

> **面向智能执行者：** 必须使用 `superpowers:subagent-driven-development`（推荐）或 `superpowers:executing-plans` 逐任务执行本计划。所有步骤使用复选框跟踪。

**目标：** 交付不依赖数据库、HTTP、前端或真实云模型的方案工作台业务内核，证明需求/大纲人工门禁、分章检索生成、引用风险核验、幂等恢复和安全定稿闭环。

**架构：** `com.yunjihai.rag.solution` 定义不可变版本、方案聚合和端口；应用服务单向调用现有 `RetrievalService`、供应商中立模型端口、核验端口和授权端口。全部基础设施以接口隔离，纵向验收只使用测试源码内的内存夹具。

**技术栈：** Java 21、Spring Boot 4.1.1、JUnit 5、AssertJ、Maven；不新增产品依赖。

**规格：** `docs/superpowers/specs/2026-09-28-solution-workbench-core-design.md`

## 全局约束

- 产品代码只放在 `apps/core-api/src/main/java/com/yunjihai/rag/solution`，现有审计枚举和白名单除外。
- 业务类型不得依赖 Spring、数据库实体、HTTP DTO、模型 SDK 或供应商类型。
- 未确认当前需求和当前大纲时，检索、模型和语义核验调用次数必须为 0。
- 修改需求必须撤销大纲确认并使旧生成、风险确认和定稿资格失效；修改大纲必须撤销大纲确认并使旧大纲任务失效。
- 模型结果只创建待采纳版本；不得覆盖人工编辑或已采纳版本。
- Evidence ID 必须来自本次检索白名单；未知、重复或缺失必需引用时整次结果非法，不得部分采用。
- 相同幂等键只能对应相同输入指纹、一个逻辑尝试、一个模型费用摘要和最多一个成功章节版本。
- 金额、日期/工期、性能/SLA、客户案例、公司能力、法律承诺必须有证据，并按规则取得人工确认。
- 定稿前必须重新复核全部 Evidence 的当前访问权；端口异常、null、未知返回或明确拒绝任一 ID 时失败关闭。
- 审计和异常不得包含完整需求、章节正文、Evidence 摘要、模型正文、供应商错误正文、主体集合或密钥。
- 本计划不得创建数据库迁移、任务队列、HTTP API、Vue 页面、真实 Prompt 内容、模型适配器、Word 导出、黄金集或 `pilot-retrieval-v1`、`pilot-workbench-v1` 标签。

## 评审重点

- 同一幂等键配合不同需求/大纲/章节输入指纹时必须显式冲突，且在检索或模型调用前失败（任务 3、5）。
- 模型返回白名单外 Evidence ID、重复 Claim ID，或事实 Claim 缺少引用时必须整次拒绝，不保存部分章节（任务 3、5）。
- 模型调用期间需求、大纲或章节基线变化时，迟到结果必须转为 `CANCELLED`；不得保存草稿，但已经发生的模型使用摘要只记录一次（任务 5）。
- 语义核验返回未知、重复、缺失 Claim ID、null 或异常时不得默认支持；普通声明进入人工评审，高风险声明保持未解决（任务 2）。
- 定稿复核返回子集、未知 Evidence ID、null 或异常时不得生成定稿版本、移动聚合指针或写成功审计（任务 6）。

---

### 任务 1：需求、大纲和方案聚合契约

**文件：**

- 创建：`apps/core-api/src/main/java/com/yunjihai/rag/solution/domain/SolutionPhase.java`
- 创建：`apps/core-api/src/main/java/com/yunjihai/rag/solution/domain/RequirementCategory.java`
- 创建：`apps/core-api/src/main/java/com/yunjihai/rag/solution/domain/RiskType.java`
- 创建：`apps/core-api/src/main/java/com/yunjihai/rag/solution/domain/RequirementSource.java`
- 创建：`apps/core-api/src/main/java/com/yunjihai/rag/solution/domain/RequirementItem.java`
- 创建：`apps/core-api/src/main/java/com/yunjihai/rag/solution/domain/RequirementVersion.java`
- 创建：`apps/core-api/src/main/java/com/yunjihai/rag/solution/domain/OutlineNode.java`
- 创建：`apps/core-api/src/main/java/com/yunjihai/rag/solution/domain/OutlineVersion.java`
- 创建：`apps/core-api/src/main/java/com/yunjihai/rag/solution/domain/VersionConfirmation.java`
- 创建：`apps/core-api/src/main/java/com/yunjihai/rag/solution/domain/Solution.java`
- 创建：`apps/core-api/src/main/java/com/yunjihai/rag/solution/domain/SolutionLifecycleException.java`
- 创建：`apps/core-api/src/main/java/com/yunjihai/rag/solution/domain/ConcurrentSolutionUpdateException.java`
- 创建：`apps/core-api/src/main/java/com/yunjihai/rag/solution/port/SolutionRepository.java`
- 创建：`apps/core-api/src/main/java/com/yunjihai/rag/solution/port/RequirementVersionRepository.java`
- 创建：`apps/core-api/src/main/java/com/yunjihai/rag/solution/port/OutlineVersionRepository.java`
- 测试：`apps/core-api/src/test/java/com/yunjihai/rag/solution/domain/SolutionAggregateTest.java`

**接口：**

- 消费：`DocumentVersionRef`、`SourceLocator`、服务端 `Instant` 和调用方生成的 UUID。
- 产出：
  - `RequirementSource(DocumentVersionRef documentVersion, SourceLocator locator)`；
  - `RequirementItem(UUID itemId, RequirementCategory category, String text, List<RequirementSource> sources)`；除 `UNKNOWN` 外至少一个来源；
  - `RequirementVersion(UUID id, long number, List<RequirementItem> items, UUID createdBy, Instant createdAt, String changeReason)`；
  - `OutlineNode(UUID sectionId, String title, int level, int order, boolean required, String objective, String retrievalHint, Set<RiskType> riskTypes)`；层级 `1..4`，顺序从 1 连续；
  - `OutlineVersion(UUID id, long number, List<OutlineNode> nodes, UUID createdBy, Instant createdAt)`；
  - `VersionConfirmation(UUID versionId, UUID confirmedBy, Instant confirmedAt)`，由 `Solution` 以 `Optional` 保存需求/大纲确认；
  - `Solution.create(UUID solutionId, UUID spaceId, UUID actorId, RequirementVersion initial, Instant at)`；
  - `reviseRequirements`、`confirmRequirements`、`reviseOutline`、`confirmOutline`、`adoptSection`、`markFinalized`、`startNewRevision` 均返回新 `Solution` 并要求 `expectedRevision`；
  - `Optional<Solution> SolutionRepository.findById(UUID)`、`void insert(Solution)`、`void replace(Solution,long expectedRevision)`；
  - `Optional<RequirementVersion> RequirementVersionRepository.findById(UUID)`、`void insert(RequirementVersion)`；
  - `Optional<OutlineVersion> OutlineVersionRepository.findById(UUID)`、`void insert(OutlineVersion)`。

- [ ] **步骤 1：编写失败的不可变值对象测试**

覆盖 null/空白、集合含 null、防御性复制、非 UNKNOWN 需求无来源、重复 item ID、大纲重复 section ID、层级越界、顺序跳跃、空必填大纲和风险集合不可变。

- [ ] **步骤 2：运行领域测试并确认编译失败**

运行：`mvn.cmd -q -pl apps/core-api -Dtest=SolutionAggregateTest test`

预期：因领域类型尚不存在而失败。

- [ ] **步骤 3：实现需求和大纲值对象**

使用 record 和紧凑构造器集中校验；字符串统一执行 NFKC、首尾去空白和连续空白折叠，集合拒绝 null 并防御性复制。

- [ ] **步骤 4：运行值对象测试并确认通过**

运行：`mvn.cmd -q -pl apps/core-api -Dtest=SolutionAggregateTest test`

- [ ] **步骤 5：编写失败的聚合生命周期测试**

测试创建、确认需求、建立/确认大纲、采纳章节和定稿的正常路径；测试未确认门禁、修改需求撤销大纲并清空当前章节指针、修改大纲使旧章节指针失效、定稿后普通修改被拒绝且 `startNewRevision` 开启新修订、错误 `expectedRevision` 显式冲突。

- [ ] **步骤 6：运行聚合测试并确认失败**

运行：`mvn.cmd -q -pl apps/core-api -Dtest=SolutionAggregateTest test`

预期：因聚合方法或仓储端口尚未实现而失败。

- [ ] **步骤 7：实现不可变 `Solution` 聚合和仓储端口**

聚合只保存当前版本指针、当前采纳章节 `Map<sectionId, sectionVersionId>`、流程阶段、定稿版本 ID 和 `revision`；所有改变返回新对象，版本号每次加一。

- [ ] **步骤 8：运行任务测试**

运行：`mvn.cmd -q -pl apps/core-api -Dtest=SolutionAggregateTest test`

预期：全部通过。

- [ ] **步骤 9：提交任务 1**

```powershell
git add apps/core-api/src/main/java/com/yunjihai/rag/solution/domain apps/core-api/src/main/java/com/yunjihai/rag/solution/port apps/core-api/src/test/java/com/yunjihai/rag/solution/domain
git commit -m "feat: add versioned solution aggregate"
```

### 任务 2：声明、引用支持性与风险规则

**文件：**

- 创建：`apps/core-api/src/main/java/com/yunjihai/rag/solution/domain/Claim.java`
- 创建：`apps/core-api/src/main/java/com/yunjihai/rag/solution/verification/CitationVerdict.java`
- 创建：`apps/core-api/src/main/java/com/yunjihai/rag/solution/verification/ClaimEvidenceInput.java`
- 创建：`apps/core-api/src/main/java/com/yunjihai/rag/solution/verification/ClaimVerification.java`
- 创建：`apps/core-api/src/main/java/com/yunjihai/rag/solution/verification/SolutionRiskReport.java`
- 创建：`apps/core-api/src/main/java/com/yunjihai/rag/solution/verification/CitationVerificationPort.java`
- 创建：`apps/core-api/src/main/java/com/yunjihai/rag/solution/verification/SectionVerificationService.java`
- 测试：`apps/core-api/src/test/java/com/yunjihai/rag/solution/verification/SectionVerificationServiceTest.java`

**接口：**

- 消费：任务 1 的 `RiskType`、可信检索 `Evidence`。
- 产出：
  - `Claim(UUID claimId, String text, boolean factual, Set<UUID> evidenceIds, Set<RiskType> riskTypes)`；
  - `CitationVerificationPort.verify(List<ClaimEvidenceInput>, Instant deadline)` 返回 `List<ClaimVerification>`；
  - `SectionVerificationService.verify(List<Claim>, List<Evidence>, Instant deadline)` 返回 `SolutionRiskReport`；
  - `CitationVerdict` 固定为 `SUPPORTED | PARTIAL | UNSUPPORTED | HUMAN_CONFIRMATION_REQUIRED`。

- [ ] **步骤 1：编写失败的本地确定性核验测试**

覆盖事实 Claim 无引用、白名单外 Evidence、重复 Claim ID；`项目周期30天` 对证据 `项目周期60天`、`报价100万元` 对证据 `报价10万元` 必须为 `UNSUPPORTED`，相同数字和单位允许继续语义核验。

- [ ] **步骤 2：运行测试并确认失败**

运行：`mvn.cmd -q -pl apps/core-api -Dtest=SectionVerificationServiceTest test`

- [ ] **步骤 3：实现声明契约与确定性核验**

数字、金额、日期和百分比使用 Unicode NFKC 后的保守 token 比较；不能确认支持时不得推断为支持。

- [ ] **步骤 4：运行确定性核验测试并确认通过**

运行：`mvn.cmd -q -pl apps/core-api -Dtest=SectionVerificationServiceTest test`

- [ ] **步骤 5：编写失败的语义端口非法结果测试**

覆盖未知、重复、缺失 Claim ID、null、抛错和截止时间传播；非法或不可用结果下，普通事实为 `HUMAN_CONFIRMATION_REQUIRED`，高风险事实同样保持未解决，不得产生 `SUPPORTED`。

- [ ] **步骤 6：运行语义非法结果测试并确认失败**

运行：`mvn.cmd -q -pl apps/core-api -Dtest=SectionVerificationServiceTest test`

- [ ] **步骤 7：实现语义结果完整性校验和风险升级**

模型返回 `SUPPORTED` 的高风险类型仍升级为 `HUMAN_CONFIRMATION_REQUIRED`；`PARTIAL`、`UNSUPPORTED` 原样保留。

- [ ] **步骤 8：运行任务测试**

运行：`mvn.cmd -q -pl apps/core-api -Dtest=SectionVerificationServiceTest test`

- [ ] **步骤 9：提交任务 2**

```powershell
git add apps/core-api/src/main/java/com/yunjihai/rag/solution/domain apps/core-api/src/main/java/com/yunjihai/rag/solution/verification apps/core-api/src/test/java/com/yunjihai/rag/solution/verification
git commit -m "feat: verify solution claims and risks"
```

### 任务 3：模型契约、章节版本与生成尝试状态机

**文件：**

- 创建：`apps/core-api/src/main/java/com/yunjihai/rag/solution/model/ModelScenario.java`
- 创建：`apps/core-api/src/main/java/com/yunjihai/rag/solution/model/PromptRef.java`
- 创建：`apps/core-api/src/main/java/com/yunjihai/rag/solution/model/SolutionModelRequest.java`
- 创建：`apps/core-api/src/main/java/com/yunjihai/rag/solution/model/SolutionModelResponse.java`
- 创建：`apps/core-api/src/main/java/com/yunjihai/rag/solution/model/SolutionModelPort.java`
- 创建：`apps/core-api/src/main/java/com/yunjihai/rag/solution/model/GeneratedRequirementProposal.java`
- 创建：`apps/core-api/src/main/java/com/yunjihai/rag/solution/model/GeneratedOutlineProposal.java`
- 创建：`apps/core-api/src/main/java/com/yunjihai/rag/solution/model/GeneratedSectionDraft.java`
- 创建：`apps/core-api/src/main/java/com/yunjihai/rag/solution/generation/GenerationStatus.java`
- 创建：`apps/core-api/src/main/java/com/yunjihai/rag/solution/generation/GenerationKey.java`
- 创建：`apps/core-api/src/main/java/com/yunjihai/rag/solution/generation/ModelUsage.java`
- 创建：`apps/core-api/src/main/java/com/yunjihai/rag/solution/generation/GenerationAttempt.java`
- 创建：`apps/core-api/src/main/java/com/yunjihai/rag/solution/generation/GenerationAttemptStore.java`
- 创建：`apps/core-api/src/main/java/com/yunjihai/rag/solution/domain/SectionVersionKind.java`
- 创建：`apps/core-api/src/main/java/com/yunjihai/rag/solution/domain/SectionVersion.java`
- 创建：`apps/core-api/src/main/java/com/yunjihai/rag/solution/generation/GenerationPolicy.java`
- 测试：`apps/core-api/src/test/java/com/yunjihai/rag/solution/generation/GenerationContractTest.java`

**接口：**

- 消费：任务 1 的版本 ID，任务 2 的 `Claim` 和 `SolutionRiskReport`，可信检索 `Evidence`。
- 产出：
  - `<T> SolutionModelResponse<T> SolutionModelPort.execute(SolutionModelRequest request, Class<T> responseType)`；
  - `SolutionModelRequest` 分字段保存系统规则引用、确认需求、章节目标、模板规则、Evidence 和截止时间，不提供预拼接 Prompt 字符串；
  - `GenerationKey(String clientKey, UUID solutionId, UUID requirementVersionId, UUID outlineVersionId, UUID sectionId, Optional<UUID> baselineSectionVersionId)`，提供确定性 SHA-256 输入指纹；
  - `GenerationAttempt.start(UUID attemptId, GenerationKey key, Instant at)` 及合法状态转换；
  - `GenerationStatus` 固定为 `PENDING | RETRIEVING | GENERATING | VERIFYING | SUCCEEDED | INSUFFICIENT_EVIDENCE | FAILED_RETRYABLE | FAILED_MANUAL | CANCELLED`；
  - `Optional<GenerationAttempt> GenerationAttemptStore.findByClientKey(String clientKey)`、`GenerationAttempt insertIfAbsent(GenerationAttempt attempt)`、`void replace(GenerationAttempt next, long expectedRevision)`；
  - `GenerationPolicy.defaults()` 固定请求 Evidence 数 8、最低证据数 1、Prompt `solution-section/v1`、模型截止时间 60 秒。

- [ ] **步骤 1：编写失败的模型与章节契约测试**

覆盖请求字段隔离、防御性复制、正文不进入 `toString`、响应类型匹配、SectionVersion 只允许 `MODEL_DRAFT | HUMAN_EDIT | ADOPTED` 且引用/Claim 不可变。

- [ ] **步骤 2：运行模型与章节契约测试并确认失败**

运行：`mvn.cmd -q -pl apps/core-api -Dtest=GenerationContractTest test`

- [ ] **步骤 3：实现模型和章节契约**

错误对象只保存规范化错误码；供应商异常正文不得进入领域对象。

- [ ] **步骤 4：运行模型与章节契约测试并确认通过**

运行：`mvn.cmd -q -pl apps/core-api -Dtest=GenerationContractTest test`

- [ ] **步骤 5：编写失败的状态机和幂等键测试**

覆盖正常状态链、四类异常结束状态、费用摘要最多写一次、相同 client key 不同输入指纹显式冲突；`SUCCEEDED | INSUFFICIENT_EVIDENCE | FAILED_MANUAL | CANCELLED` 不可再次转换，只有 `FAILED_RETRYABLE` 可通过显式 `retry` 在同一逻辑尝试内从最后持久化检查点恢复并增加执行次数。

- [ ] **步骤 6：运行状态机测试并确认失败**

运行：`mvn.cmd -q -pl apps/core-api -Dtest=GenerationContractTest test`

- [ ] **步骤 7：实现生成尝试、策略与存储端口**

`GenerationAttempt` 为不可变 record；每次转换增加 revision，非法转换抛出无正文领域异常。

- [ ] **步骤 8：运行任务测试**

运行：`mvn.cmd -q -pl apps/core-api -Dtest=GenerationContractTest test`

- [ ] **步骤 9：提交任务 3**

```powershell
git add apps/core-api/src/main/java/com/yunjihai/rag/solution/model apps/core-api/src/main/java/com/yunjihai/rag/solution/generation apps/core-api/src/main/java/com/yunjihai/rag/solution/domain apps/core-api/src/test/java/com/yunjihai/rag/solution/generation
git commit -m "feat: define governed solution generation contracts"
```

### 任务 4：需求、大纲和人工确认应用服务

**文件：**

- 创建：`apps/core-api/src/main/java/com/yunjihai/rag/solution/SolutionAction.java`
- 创建：`apps/core-api/src/main/java/com/yunjihai/rag/solution/SolutionAuthorizationPort.java`
- 创建：`apps/core-api/src/main/java/com/yunjihai/rag/solution/SolutionWorkbenchService.java`
- 修改：`apps/core-api/src/main/java/com/yunjihai/rag/audit/AuditAction.java`
- 修改：`apps/core-api/src/main/java/com/yunjihai/rag/audit/AuditService.java`
- 测试：`apps/core-api/src/test/java/com/yunjihai/rag/solution/SolutionWorkbenchServiceTest.java`
- 修改测试：`apps/core-api/src/test/java/com/yunjihai/rag/audit/AuditServiceTest.java`

**接口：**

- 消费：任务 1 的聚合/仓储，任务 3 的模型端口和需求/大纲提案。
- 产出：`createSolution`、`proposeRequirements`、`reviseRequirements`、`confirmRequirements`、`proposeOutline`、`reviseOutline`、`confirmOutline`；所有命令包含 actor、solution、expectedRevision、requestId，模型操作另含幂等键。
- `SolutionAuthorizationPort.require(UUID actorId, UUID spaceId, UUID solutionId, SolutionAction action, Instant at)`；动作固定为规格列出的六项。

- [ ] **步骤 1：编写失败的门禁和授权顺序测试**

断言未确认需求不能生成大纲；无权限、授权异常和 revision 冲突都在模型调用前失败；确认只对当前精确版本生效。

- [ ] **步骤 2：运行门禁和授权顺序测试并确认失败**

运行：`mvn.cmd -q -pl apps/core-api -Dtest=SolutionWorkbenchServiceTest test`

- [ ] **步骤 3：实现人工修订与确认路径**

先授权、再读取、执行聚合转换、按 expectedRevision 保存，最后写审计；审计失败时不返回成功结果。

- [ ] **步骤 4：运行人工路径测试并确认通过**

运行：`mvn.cmd -q -pl apps/core-api -Dtest=SolutionWorkbenchServiceTest test`

- [ ] **步骤 5：编写失败的模型提案完整性测试**

需求提案覆盖空分类、重复 item ID、事实无来源；大纲提案覆盖必填章节丢失、重复 ID、层级/顺序非法。任何非法或部分结构结果不得替换上一有效版本。

- [ ] **步骤 6：运行模型提案测试并确认失败**

运行：`mvn.cmd -q -pl apps/core-api -Dtest=SolutionWorkbenchServiceTest test`

- [ ] **步骤 7：实现需求和大纲提案路径**

模型响应先完整转成任务 1 的值对象，只有全部校验通过后才保存新版本；Prompt 固定为 `requirements/v1` 和 `outline/v1`。

- [ ] **步骤 8：运行模型提案测试并确认通过**

运行：`mvn.cmd -q -pl apps/core-api -Dtest=SolutionWorkbenchServiceTest test`

- [ ] **步骤 9：编写失败的脱敏审计测试**

新增动作 `SOLUTION_CREATE | REQUIREMENTS_CONFIRM | OUTLINE_CONFIRM`；只允许 solution/requirement/outline/request/prompt/model ID、版本号、状态、计数、耗时和费用摘要。

- [ ] **步骤 10：运行审计测试并确认失败**

运行：`mvn.cmd -q -pl apps/core-api -Dtest='SolutionWorkbenchServiceTest,AuditServiceTest' test`

- [ ] **步骤 11：扩展审计动作和白名单**

只新增步骤 9 已固定的动作和字段，未知字段继续被拒绝或过滤。

- [ ] **步骤 12：运行任务测试与全量 Java 测试**

运行：`mvn.cmd -q -pl apps/core-api -Dtest='SolutionWorkbenchServiceTest,AuditServiceTest' test`

运行：`mvn.cmd -q -pl apps/core-api test`

- [ ] **步骤 13：提交任务 4**

```powershell
git add apps/core-api/src/main/java/com/yunjihai/rag/solution apps/core-api/src/main/java/com/yunjihai/rag/audit apps/core-api/src/test/java/com/yunjihai/rag/solution apps/core-api/src/test/java/com/yunjihai/rag/audit
git commit -m "feat: govern solution requirements and outlines"
```

### 任务 5：幂等分章检索、生成、核验与采纳

**文件：**

- 创建：`apps/core-api/src/main/java/com/yunjihai/rag/solution/generation/GenerateSectionCommand.java`
- 创建：`apps/core-api/src/main/java/com/yunjihai/rag/solution/generation/SectionVersionRepository.java`
- 创建：`apps/core-api/src/main/java/com/yunjihai/rag/solution/generation/SectionGenerationResult.java`
- 创建：`apps/core-api/src/main/java/com/yunjihai/rag/solution/generation/SectionGenerationService.java`
- 测试：`apps/core-api/src/test/java/com/yunjihai/rag/solution/generation/SectionGenerationServiceTest.java`

**接口：**

- 消费：任务 1 的仓储与版本门禁、任务 2 的核验服务、任务 3 的尝试/模型端口、任务 4 的授权端口、现有 `RetrievalService`。
- 产出：
  - `SectionGenerationResult generate(GenerateSectionCommand command)`；
  - `Solution adoptSection(UUID actorId, UUID solutionId, UUID sectionVersionId, long expectedRevision, Instant at)`；
  - `Optional<SectionVersion> SectionVersionRepository.findById(UUID)`、`SectionVersion insertIfAbsent(SectionVersion)`、`Optional<SectionVersion> findByAttemptId(UUID)`；
  - 成功生成只保存一个 `MODEL_DRAFT`，采纳另建 `ADOPTED` 并更新聚合。

- [ ] **步骤 1：编写失败的人工门禁、证据不足和调用顺序测试**

断言顺序为授权→读取当前版本→登记尝试→检索→模型→核验→版本栅栏→保存草稿；未确认、缺少章节、证据少于 1 时模型调用为 0。

- [ ] **步骤 2：运行人工门禁和证据不足测试并确认失败**

运行：`mvn.cmd -q -pl apps/core-api -Dtest=SectionGenerationServiceTest test`

- [ ] **步骤 3：实现正常状态链和证据不足路径**

检索请求 Evidence 数固定为 8；查询由当前确认需求摘要、章节目标和检索提示构造，调用方不能注入主体或安全时间。

- [ ] **步骤 4：运行正常路径测试并确认通过**

运行：`mvn.cmd -q -pl apps/core-api -Dtest=SectionGenerationServiceTest test`

- [ ] **步骤 5：编写失败的模型 Evidence 白名单测试**

覆盖未知 Evidence ID、重复 Claim ID、事实 Claim 无引用和模型返回 null；整次进入 `FAILED_MANUAL`，不写 SectionVersion、不调用采纳。

- [ ] **步骤 6：运行 Evidence 白名单测试并确认失败**

运行：`mvn.cmd -q -pl apps/core-api -Dtest=SectionGenerationServiceTest test`

- [ ] **步骤 7：实现模型响应完整性校验与核验接入**

只有全部 Claim 合法才调用任务 2 的核验服务；结果连同风险报告保存到候选章节。

- [ ] **步骤 8：运行 Evidence 白名单测试并确认通过**

运行：`mvn.cmd -q -pl apps/core-api -Dtest=SectionGenerationServiceTest test`

- [ ] **步骤 9：编写失败的幂等、重试和迟到结果测试**

覆盖同键同指纹复用、同键异指纹冲突、检索/模型临时失败后恢复、模型回调期间修改需求/大纲/基线章节；迟到结果为 `CANCELLED`、无草稿，实际 ModelUsage 只记录一次。

- [ ] **步骤 10：运行幂等和迟到结果测试并确认失败**

运行：`mvn.cmd -q -pl apps/core-api -Dtest=SectionGenerationServiceTest test`

- [ ] **步骤 11：实现版本栅栏和幂等恢复**

每个外部调用后重新读取当前聚合；版本不匹配立即停止后续保存。临时失败按同一 attempt 恢复，不创建第二尝试。

- [ ] **步骤 12：运行幂等和迟到结果测试并确认通过**

运行：`mvn.cmd -q -pl apps/core-api -Dtest=SectionGenerationServiceTest test`

- [ ] **步骤 13：编写失败的采纳门禁测试**

`UNSUPPORTED` 或 `PARTIAL` 的草稿不能采纳；`HUMAN_CONFIRMATION_REQUIRED` 可进入已采纳版本，但必须在任务 6 完成人工确认后才能定稿。采纳生成新的 `ADOPTED` 版本，模型草稿保持不可变。

- [ ] **步骤 14：运行采纳门禁测试并确认失败**

运行：`mvn.cmd -q -pl apps/core-api -Dtest=SectionGenerationServiceTest test`

- [ ] **步骤 15：实现采纳门禁**

采纳前重新读取当前需求、大纲和章节基线，确认候选仍属于当前版本，再创建 `ADOPTED` 版本并更新聚合。

- [ ] **步骤 16：运行任务测试与全量 Java 测试**

运行：`mvn.cmd -q -pl apps/core-api -Dtest=SectionGenerationServiceTest test`

运行：`mvn.cmd -q -pl apps/core-api test`

- [ ] **步骤 17：提交任务 5**

```powershell
git add apps/core-api/src/main/java/com/yunjihai/rag/solution apps/core-api/src/test/java/com/yunjihai/rag/solution/generation
git commit -m "feat: generate idempotent cited solution sections"
```

### 任务 6：风险确认、Evidence 复核与不可变定稿

**文件：**

- 创建：`apps/core-api/src/main/java/com/yunjihai/rag/solution/verification/RiskConfirmation.java`
- 创建：`apps/core-api/src/main/java/com/yunjihai/rag/solution/verification/RiskConfirmationRepository.java`
- 创建：`apps/core-api/src/main/java/com/yunjihai/rag/solution/finalization/EvidenceAccessPort.java`
- 创建：`apps/core-api/src/main/java/com/yunjihai/rag/solution/finalization/FinalizedSolutionVersion.java`
- 创建：`apps/core-api/src/main/java/com/yunjihai/rag/solution/finalization/SolutionFinalizationStore.java`
- 创建：`apps/core-api/src/main/java/com/yunjihai/rag/solution/finalization/SolutionFinalizationService.java`
- 修改：`apps/core-api/src/main/java/com/yunjihai/rag/audit/AuditAction.java`
- 测试：`apps/core-api/src/test/java/com/yunjihai/rag/solution/finalization/SolutionFinalizationServiceTest.java`
- 修改测试：`apps/core-api/src/test/java/com/yunjihai/rag/audit/AuditServiceTest.java`

**接口：**

- 消费：任务 1 的聚合和 Outline 必填节点，任务 2 的风险报告，任务 5 的已采纳章节。
- 产出：
  - `confirmRisk(UUID actorId, UUID solutionId, UUID sectionVersionId, UUID claimId, String reason, Instant at)`；
  - `FinalizedSolutionVersion finalizeSolution(UUID actorId, UUID solutionId, long expectedRevision, Instant at)`；
  - `EvidenceAccessPort.authorize(UUID userId, UUID spaceId, Set<UUID> evidenceIds, Instant at)` 返回当前允许 ID；
  - `void SolutionFinalizationStore.commit(Solution updated, long expectedRevision, FinalizedSolutionVersion finalized, AuditCommand successAudit)` 必须原子保存定稿版本、聚合新指针和脱敏成功审计；
  - `List<RiskConfirmation> RiskConfirmationRepository.findBySolutionId(UUID solutionId)`、`RiskConfirmation insertIfAbsent(RiskConfirmation confirmation)`；
  - 定稿版本保存需求、大纲、章节版本清单、风险确认清单、创建信息和确定性 SHA-256 内容摘要。

- [ ] **步骤 1：编写失败的风险确认绑定测试**

断言确认必须有权限、非空原因并绑定准确 sectionVersion/claim/evidence；声明、引用、需求或大纲变化后旧确认不再满足门禁。

- [ ] **步骤 2：运行风险确认测试并确认失败**

运行：`mvn.cmd -q -pl apps/core-api -Dtest=SolutionFinalizationServiceTest test`

预期：因风险确认类型、仓储或服务尚不存在而失败。

- [ ] **步骤 3：实现不可变风险确认和授权检查**

确认只解决 `HUMAN_CONFIRMATION_REQUIRED`；不能把 `PARTIAL` 或 `UNSUPPORTED` 通过签字改为支持。

- [ ] **步骤 4：再次运行风险确认测试并确认通过**

运行：`mvn.cmd -q -pl apps/core-api -Dtest=SolutionFinalizationServiceTest test`

- [ ] **步骤 5：编写失败的定稿门禁与 Evidence 复核测试**

覆盖未确认需求/大纲、缺必填章节、非 ADOPTED 章节、未解决风险、expectedRevision 冲突；门禁失败时原子提交调用为 0，提交冲突时定稿版本、聚合指针和成功审计均不改变。

覆盖完整允许、明确返回子集、未知 ID、null、抛错以及调用期间撤权；后五种情况均不创建定稿、不移动聚合指针、不泄漏标题或正文。

- [ ] **步骤 6：运行定稿测试并确认失败**

运行：`mvn.cmd -q -pl apps/core-api -Dtest=SolutionFinalizationServiceTest test`

预期：因定稿服务、Evidence 复核端口或原子存储尚不存在而失败。

- [ ] **步骤 7：实现定稿服务、Evidence 复核和内容摘要**

按 section 顺序和稳定 UUID 文本计算 UTF-8 SHA-256；先完成全部门禁和 Evidence 复核，再构造白名单字段的 `AuditCommand`，通过 `SolutionFinalizationStore.commit` 原子保存最终版本、聚合指针和成功审计。

- [ ] **步骤 8：再次运行定稿测试并确认通过**

运行：`mvn.cmd -q -pl apps/core-api -Dtest=SolutionFinalizationServiceTest test`

- [ ] **步骤 9：编写失败的审计语义测试**

覆盖风险确认成功审计、定稿原子成功审计，以及原子提交中的审计失败、失败审计也失败的异常语义；原子审计失败后不得留下定稿版本或移动聚合指针。

- [ ] **步骤 10：运行审计测试并确认失败**

运行：`mvn.cmd -q -pl apps/core-api -Dtest='SolutionFinalizationServiceTest,AuditServiceTest' test`

预期：因新审计动作或异常处理尚未补齐而失败。

- [ ] **步骤 11：扩展审计并实现失败语义**

新增 `RISK_CONFIRM | SOLUTION_FINALIZE` 和所需白名单字段；定稿成功审计交给原子提交端口，任何内部写入失败都整体回滚；记录业务失败的审计再次失败时保留原异常并追加 suppressed exception。

- [ ] **步骤 12：运行任务测试与全量 Java 测试**

运行：`mvn.cmd -q -pl apps/core-api -Dtest='SolutionFinalizationServiceTest,AuditServiceTest' test`

运行：`mvn.cmd -q -pl apps/core-api test`

- [ ] **步骤 13：提交任务 6**

```powershell
git add apps/core-api/src/main/java/com/yunjihai/rag/solution apps/core-api/src/main/java/com/yunjihai/rag/audit apps/core-api/src/test/java/com/yunjihai/rag/solution apps/core-api/src/test/java/com/yunjihai/rag/audit
git commit -m "feat: finalize authorized reviewed solutions"
```

### 任务 7：内存纵向验收、运行手册与阶段边界

**文件：**

- 创建：`apps/core-api/src/test/java/com/yunjihai/rag/acceptance/InMemorySolutionWorkbenchScenario.java`
- 创建：`apps/core-api/src/test/java/com/yunjihai/rag/acceptance/SolutionWorkbenchCoreAcceptanceTest.java`
- 创建：`docs/runbooks/solution-workbench-core.md`
- 修改：`README.md`

**接口：**

- 消费：任务 1–6 的公开入口和端口。
- 产出：可重复的中文虚构方案纵向验收，以及明确的后续适配与标签边界。

- [ ] **步骤 1：编写失败的纵向验收测试**

同一场景依次验证：创建/确认需求；生成/确认大纲；证据不足章节不调用模型；合法章节生成；不支持引用阻止采纳；修订后引用通过；金额或工期风险阻止定稿；采纳全部必填章节；授权用户确认风险；定稿前复核 Evidence；创建不可变定稿；通过 `startNewRevision` 修改需求并使旧大纲、任务、确认和定稿资格失效；固定输入得到确定性版本关系和审计计数。

- [ ] **步骤 2：运行验收并确认失败**

运行：`mvn.cmd -q -pl apps/core-api -Dtest=SolutionWorkbenchCoreAcceptanceTest test`

- [ ] **步骤 3：只补齐测试源码内的内存夹具**

不得新增生产内存仓储、假模型 Profile 或授权后门；夹具模拟模型回调期间状态变化和定稿期间撤权。

- [ ] **步骤 4：运行验收与 Java 打包验证**

运行：`mvn.cmd -q -pl apps/core-api -Dtest=SolutionWorkbenchCoreAcceptanceTest test`

运行：`mvn.cmd -q -pl apps/core-api verify`

- [ ] **步骤 5：编写中文运行手册并更新 README**

说明状态机、默认策略、幂等键、版本失效、引用/风险门禁、审计字段、故障定位和后续基础设施门槛；明确本批没有 API/UI/数据库/真实模型/Word，不创建两个试点标签。

- [ ] **步骤 6：运行仓库全量门禁**

```powershell
$env:UV_PYTHON = 'C:\Users\NO.1\AppData\Roaming\uv\python\cpython-3.12.14-windows-x86_64-none\python.exe'
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/verify.ps1

$uvExe = 'C:\Users\NO.1\AppData\Roaming\Python\Python312\Scripts\uv.exe'
$python312 = 'C:\Users\NO.1\AppData\Roaming\uv\python\cpython-3.12.14-windows-x86_64-none\python.exe'
& $uvExe sync --python $python312 --project workers/document-parser --extra parsing --frozen
& $uvExe run --python $python312 --project workers/document-parser pytest -q -W error
& $uvExe run --python $python312 --project workers/document-parser ruff check .
& $uvExe run --python $python312 --project workers/document-parser mypy workers/document-parser/src

corepack.cmd pnpm --dir apps/web exec playwright test
```

预期：Java、前端、Python、静态检查和 Playwright 全部通过。

- [ ] **步骤 7：检查格式、工作树和禁止标签**

运行：`git diff --check`

运行：`git tag --list pilot-retrieval-v1 pilot-workbench-v1`

预期：无格式错误；两个标签均不存在。

- [ ] **步骤 8：提交任务 7**

```powershell
git add apps/core-api/src/test/java/com/yunjihai/rag/acceptance docs/runbooks/solution-workbench-core.md README.md
git commit -m "test: accept infrastructure-neutral solution workbench core"
```

- [ ] **步骤 9：请求全分支代码审查**

审查范围从本规格提交 `bf902c6` 的父提交到当前 HEAD，重点检查全局约束和五项评审重点。Critical/Important 只允许一次 TDD 修复轮，之后重跑任务 7 全量门禁；保持两个试点标签不存在。
