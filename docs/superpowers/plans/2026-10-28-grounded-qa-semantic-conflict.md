# Grounded QA 语义冲突检测实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在模型调用前识别跨证据的高置信关键事实冲突，并以 `CONFLICTING_EVIDENCE` 严格拒答。

**Architecture:** 新增供应商无关的 `EvidenceConflictDetector` 端口和确定性默认实现。实现只处理显式键值事实、规范化可比较单位并跨不同证据比较；`GroundedQaService` 在证据充分性检查后调用该端口，冲突或检测异常均失败关闭。

**Tech Stack:** Java 21、Spring Boot 4、JUnit 6、AssertJ、Maven

**Spec:** `docs/superpowers/specs/2026-10-28-grounded-qa-semantic-conflict-design.md`

## Global Constraints

- 只检查已经最终鉴权并完成证据限量的内容。
- 检测必须发生在脱敏和任何聊天模型调用之前。
- 不新增云模型调用、外发内容、数据库表或公开 API。
- 冲突或检测异常均返回现有 `CONFLICTING_EVIDENCE` 严格拒答。
- 不记录问题、证据正文、事实键或业务值。
- 保持评测路径复用生产 `GroundedQaService`。

## Review Focus

- 等价金额、期限和容量单位不得被误判为冲突；Task 1 单元测试覆盖。
- “报销期限”和“付款期限”等不同事实键不得因公共后缀过短而冲突；Task 1 单元测试覆盖。
- 同一证据内部多个不同值不构成跨证据冲突；Task 1 单元测试覆盖。
- 数据完整性冲突必须兼容 Unicode 与空白归一化；Task 1 单元测试覆盖。
- 检测器运行时异常必须严格拒答且不得调用模型；Task 2 编排测试覆盖。

---

### Task 1: 确定性证据冲突检测端口与实现

**Files:**
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/qa/conflict/EvidenceConflictDetector.java`
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/qa/conflict/DeterministicEvidenceConflictDetector.java`
- Create: `apps/core-api/src/test/java/com/yunjihai/rag/qa/conflict/DeterministicEvidenceConflictDetectorTest.java`

**Interfaces:**
- Consumes: `List<com.yunjihai.rag.retrieval.domain.Evidence>`
- Produces: `boolean EvidenceConflictDetector.hasConflict(List<Evidence> evidence)`

- [x] **Step 1:** 先写失败测试，覆盖同身份正文冲突、金额/期限/日期/状态冲突、等价值、不同事实键、单证据多值和 Unicode/空白归一化。
- [x] **Step 2:** 运行 `.\mvnw.cmd -pl apps/core-api '-Dtest=DeterministicEvidenceConflictDetectorTest' test`，确认因端口和实现缺失而失败。
- [x] **Step 3:** 最小实现端口、事实抽取、类型化值规范化和跨证据比较。
- [x] **Step 4:** 重跑定向测试并确认全部通过。
- [x] **Step 5:** 创建本任务本地提交，不推送远程。

### Task 2: Grounded QA 编排接入与严格失败关闭

**Files:**
- Modify: `apps/core-api/src/main/java/com/yunjihai/rag/qa/GroundedQaService.java`
- Modify: `apps/core-api/src/main/java/com/yunjihai/rag/runtime/WorkerRuntimeConfiguration.java`
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/qa/conflict/EvidenceConflictDetectorAutoConfiguration.java`
- Create: `apps/core-api/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- Modify: `apps/core-api/src/test/java/com/yunjihai/rag/qa/GroundedQaServiceTest.java`
- Modify: `apps/core-api/src/test/java/com/yunjihai/rag/qa/web/QaTestHarness.java`
- Create: `apps/core-api/src/test/java/com/yunjihai/rag/runtime/WorkerRuntimeConfigurationTest.java`
- Modify: `docs/runbooks/grounded-qa.md`

**Interfaces:**
- Consumes: `EvidenceConflictDetector.hasConflict(List<Evidence>)`
- Produces: 模型调用前的 `QaOutcome.Refused(..., CONFLICTING_EVIDENCE, ...)`

- [x] **Step 1:** 先写失败测试，证明跨文档语义冲突和检测器异常都严格拒答，且模型调用次数为零；非冲突继续原链路。
- [x] **Step 2:** 运行 `.\mvnw.cmd -pl apps/core-api '-Dtest=GroundedQaServiceTest' test`，确认当前编排未消费新端口而失败。
- [x] **Step 3:** 注入端口、替换旧私有检查、增加默认 Bean，并更新测试夹具和运行手册。
- [x] **Step 4:** 运行冲突检测与编排定向测试。
- [x] **Step 5:** 运行 `.\mvnw.cmd -pl apps/core-api test`、跳过测试打包、差异检查和敏感信息扫描。
- [x] **Step 6:** 完成整批只读代码审查，修复 Critical/Important 后创建本地提交，不推送远程。
