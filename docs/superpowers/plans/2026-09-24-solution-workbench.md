# 方案工作台实施计划

> **面向执行者：** 必须使用 `superpowers:subagent-driven-development`（推荐）或 `superpowers:executing-plans` 逐任务执行。

**目标：** 交付人工可控的工作台，将已确认客户需求转为可编辑、有引用、有版本的方案草稿并导出 Word。

**架构：** Spring Boot 负责方案状态、模板、人工门槛、检索、提示词版本、模型路由、引用核验和风险检查。生成异步且以章节为单位，故障不丢已完成内容。Vue 提供需求/大纲确认、章节编辑、证据查看、版本比较和导出。

**技术栈：** Java 21、Spring Boot 4.1.1、Spring AI 2.0.1、PostgreSQL、Flyway、前置 `RetrievalService`、Vue 3、TypeScript、TipTap、Vitest、Playwright；Word 库由可提交的技术尖刺在 Apache POI/docx4j 中选择。

**设计规格：** `docs/superpowers/specs/2026-09-24-enterprise-rag-solution-generation-design.md`

## 全局约束

- 仅在 `pilot-retrieval-v1` 且权限/检索评估全绿后开始。
- 需求确认和大纲确认是强制门槛；未通过时 API 必须拒绝生成。
- 每次生成记录需求/大纲版本、检索请求、证据、模型、提示词、原始输出、编辑、延迟和费用。
- 只有模型网关能将授权、脱敏、最小化证据发给云模型。
- 公司能力、客户案例、数字、工期、价格和承诺必须有证据或显式人工确认。
- 章节失败不得删除已确认需求、大纲、已完成章节和人工编辑。

## 评审重点

- 非法/部分结构化模型输出不得覆盖上一有效版本（任务 3–4）。
- 并发编辑必须显式冲突，不得静默丢文本（任务 7）。
- 引用存在但不支持陈述时，必须阻止导出或要求评审人签字（任务 6）。
- 超时重试不得重复计费或产生双版本（任务 5）。
- 导出不得包含未授权证据、脚本、外链图片和危险链接（任务 8）。

---

### 任务 1：方案、模板与版本 Schema

**文件：** 创建 `V003__solution_workbench.sql`、`Solution`、`SolutionVersion`、`SolutionSection`、`SolutionTemplate` 及聚合测试。

**产出接口：** `create`、`reviseRequirements`、`confirmRequirements`、`reviseOutline`、`confirmOutline`、`saveSection`、`finalizeVersion`。

- [ ] **第 1 步：先测试未确认需求/大纲时不得生成，修改需求后大纲确认自动失效**
- [ ] **第 2 步：建立方案、不可变需求/大纲/章节版本、生成记录、证据、评审决定和导出产物**
- [ ] **第 3 步：可编辑头使用乐观版本，不可变版本保存创建人/时间，验证并提交**

```powershell
.\mvnw.cmd -pl apps/core-api -Dtest=SolutionAggregateTest test
git add apps/core-api/src/main/resources/db/migration apps/core-api/src/main/java/com/yunjihai/rag/solution apps/core-api/src/test/java/com/yunjihai/rag/solution
git commit -m "feat: add versioned solution aggregate"
```

### 任务 2：供应商中立模型网关与提示词注册表

**文件：** 创建 `ModelGateway`、`ModelRequest`、`ModelResponse`、`PromptRegistry`，以及需求/大纲/章节/引用核验 v1 提示词和测试。

**产出接口：**

```java
<T> ModelResponse<T> execute(ModelRequest<T> request, Class<T> responseType);
```

- [ ] **第 1 步：测试未批准模型拒绝、脱敏、禁止高密级出网、截止时间传播和日志无正文**
- [ ] **第 2 步：用 Spring AI 实现结构化输出校验、超时、仅传输错误重试和幂等台账**
- [ ] **第 3 步：测试统一使用确定性假供应商；生产 Profile 禁用假供应商**
- [ ] **第 4 步：验证并提交**

```powershell
.\mvnw.cmd -pl apps/core-api -Dtest=ModelGatewayTest test
git add apps/core-api/src/main/java/com/yunjihai/rag/model apps/core-api/src/main/resources/prompts apps/core-api/src/test/java/com/yunjihai/rag/model
git commit -m "feat: add governed model gateway"
```

### 任务 3：需求提取与人工确认

**文件：** 创建 `RequirementSet`、`RequirementExtractionService`、`RequirementController`、`RequirementConfirmationView.vue` 及测试。

- [ ] **第 1 步：先测试非法模型 JSON 不覆盖上一有效需求**
- [ ] **第 2 步：提取目标、范围、约束、交付物、干系人、日期、风险和未知项；每项事实必须指向客户文件位置**
- [ ] **第 3 步：UI 对照展示字段与来源，支持带原因修改并强制确认；网络失败不丢未保存编辑**
- [ ] **第 4 步：验证并提交**

```powershell
.\mvnw.cmd -pl apps/core-api -Dtest=RequirementExtractionServiceTest test
corepack pnpm --dir apps/web test --run RequirementConfirmationView
git add apps/core-api apps/web
git commit -m "feat: confirm structured customer requirements"
```

### 任务 4：模板驱动大纲生成与确认

**文件：** 创建 `Outline`、`OutlineService`、`OutlineController`、`OutlineEditorView.vue` 及测试。

- [ ] **第 1 步：测试必填章节不得消失、ID 不得重复、层级有上限、非法输出不覆盖旧大纲**
- [ ] **第 2 步：仅已确认需求可生成大纲；节点包含稳定 ID、必填性、目标、检索提示和风险规则**
- [ ] **第 3 步：UI 可调整可选章节顺序/目标，但不得删必填章节；确认后才开启生成**
- [ ] **第 4 步：验证并提交**

```powershell
.\mvnw.cmd -pl apps/core-api -Dtest=OutlineServiceTest test
corepack pnpm --dir apps/web test --run OutlineEditorView
git add apps/core-api apps/web
git commit -m "feat: add confirmable solution outlines"
```

### 任务 5：幂等分章检索与生成

**文件：** 创建 `SectionGenerationService`、`GenerationWorker`、`GenerationState`、`SectionGenerationStatus.vue` 及测试。

- [ ] **第 1 步：测试人工门槛、证据不足和超时后重试只生成一个版本/一次计费**
- [ ] **第 2 步：状态机为 `PENDING|RETRIEVING|GENERATING|VERIFYING|SUCCEEDED|INSUFFICIENT_EVIDENCE|FAILED_RETRYABLE|FAILED_MANUAL|CANCELLED`**
- [ ] **第 3 步：每次转换持久化并发布进度；重启从最后持久状态恢复**
- [ ] **第 4 步：提示词仅接收已确认需求、章节目标、模板规则和带不透明引用 ID 的证据包**
- [ ] **第 5 步：验证并提交**

```powershell
.\mvnw.cmd -pl apps/core-api -Dtest=SectionGenerationServiceTest test
git add apps/core-api apps/web
git commit -m "feat: generate recoverable cited sections"
```

### 任务 6：引用核验与全文风险检查

**文件：** 创建 `CitationVerifier`、`RiskRuleEngine`、`ConsistencyChecker` 及测试。

**产出结果：** `SUPPORTED|PARTIAL|UNSUPPORTED|HUMAN_CONFIRMATION_REQUIRED`。

- [ ] **第 1 步：测试真实但不相关引用为 `UNSUPPORTED`，部分支持为 `PARTIAL`，高风险字段需证据+人工确认**
- [ ] **第 2 步：先执行数字/名称/日期确定性规则，再将陈述和证据作为数据交给语义核验模型**
- [ ] **第 3 步：未解决的不支持、高风险未签字或全文矛盾阻止定稿/导出**
- [ ] **第 4 步：评审豁免必须记录人、原因、时间和问题版本，验证并提交**

```powershell
.\mvnw.cmd -pl apps/core-api -Dtest='CitationVerifierTest,RiskRuleEngineTest' test
git add apps/core-api/src/main/java/com/yunjihai/rag/solution/verification apps/core-api/src/test/java/com/yunjihai/rag/solution/verification
git commit -m "feat: verify citations and risky claims"
```

### 任务 7：协作编辑、版本比较与冲突安全

**文件：** 创建 `SectionController`、`SectionConcurrencyTest`、`SolutionEditorView.vue`、`SectionEditor.vue`、`VersionDiff.vue` 及 UI 测试。

- [ ] **第 1 步：先测试相同 row version 的第二次保存返回 409 且不覆盖先到内容**
- [ ] **第 2 步：TipTap 引用标记保存 evidence ID；自动保存携带期望版本，409 不得静默重试**
- [ ] **第 3 步：冲突界面对照服务端/本地内容；单章重生成只产生待接受版本**
- [ ] **第 4 步：验证并提交**

```powershell
.\mvnw.cmd -pl apps/core-api -Dtest=SectionConcurrencyTest test
corepack pnpm --dir apps/web test --run SolutionEditorView
git add apps/core-api apps/web
git commit -m "feat: add conflict-safe solution editing"
```

### 任务 8：安全 Word 导出

**文件：** 创建 `docs/adr/0002-word-export-library.md`、`WordExportService`、导出测试和参考方案样本。

- [ ] **第 1 步：用同一中文参考方案测试 Apache POI/docx4j，按字体、编号、表格、分页和引用保真性选一个，写 ADR 并删除落选尖刺代码**
- [ ] **第 2 步：解压 DOCX 检查不含脚本、外部图片关系、`javascript:`、未授权证据标题和本地路径**
- [ ] **第 3 步：导出前再授权全部证据；产物保存 SHA-256/对象键，下载再授权并审计**
- [ ] **第 4 步：验证并提交**

```powershell
.\mvnw.cmd -pl apps/core-api -Dtest=WordExportServiceTest test
git add docs/adr apps/core-api/src/main/java/com/yunjihai/rag/solution/export apps/core-api/src/test
git commit -m "feat: export verified solutions to Word"
```

### 任务 9：反馈、端到端验收与阶段门槛

**文件：** 创建 `FeedbackService`、`SolutionWorkbenchAcceptanceTest`、Playwright 流程、`evaluation/generation/golden-v1.jsonl` 和手册。

- [ ] **第 1 步：反馈原因固定为 `ACCEPTED|FACT_ERROR|MISSING_EVIDENCE|IRRELEVANT|OUTDATED|STYLE|DUPLICATE|SCOPE_MISMATCH|OTHER`**
- [ ] **第 2 步：Playwright 跑通上传需求、确认需求/大纲、生成、查引用、退回/编辑/采纳、解决风险、定稿、DOCX 导出和未授权访问拒绝**
- [ ] **第 3 步：整体验证，CI 使用假模型且不需云凭据**

```powershell
.\mvnw.cmd verify
corepack pnpm --dir apps/web test --run
corepack pnpm --dir apps/web build
corepack pnpm --dir apps/web exec playwright test e2e/solution-workbench.spec.ts
powershell -ExecutionPolicy Bypass -File scripts/run-retrieval-eval.ps1
git add apps evaluation docs/runbooks
git commit -m "test: complete solution workbench acceptance"
git tag pilot-workbench-v1
```
