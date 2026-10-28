# 方案工作台第一条生产纵向闭环实施计划

> **面向执行者：** 必须使用 `superpowers:subagent-driven-development`（推荐）或 `superpowers:executing-plans` 逐任务执行。任务步骤用 `- [ ]` 跟踪。

**目标：** 在 `E:\git_repo\Elite-RAG` 中交付可持久化、可从浏览器操作的方案创建、需求确认与大纲确认闭环。

**架构：** 现有纯 Java 方案领域模型保留；Spring 应用层接入 PostgreSQL 事务、来源权限和精简幂等记录，再向现有 Session API 和 Vue 页面提供受权视图。每条写路径均以当前版本、权限和事务作为门禁。

**技术栈：** Java 21、Spring Boot、PostgreSQL 17、Flyway、JDBC、Testcontainers、Vue 3、TypeScript、Vitest、Playwright。

**规格：** `docs/superpowers/specs/2026-10-28-solution-workbench-first-vertical-slice-design.md`

## 全局约束

- 工作目录固定为 `E:\git_repo\Elite-RAG`，分支为 `solution-workbench-core`；仅做本地提交，不主动推送。
- 一家公司一套实例，PostgreSQL 是方案业务状态的事实源。
- Web API 使用 `/api/v1`；用户身份和时间由服务端确定。
- 方案与版本正文不得进入日志、审计和幂等记录。
- 无权查看与不存在统一 `404`；权限源故障失败关闭。
- 需求及大纲版本不可变；更新使用聚合 `expectedRevision`。
- 来源必须为同空间当前已发布、当前有权的文档版本；后续撤权使确认动态失效。
- 不交付分章生成、定稿、Word 导出、钉钉同步或试点标签。
- 产品代码按 RED→GREEN 编写，每任务结束运行对应完整测试并做本地提交。

## 文件与职责

- `apps/core-api/src/main/java/com/yunjihai/rag/solution/domain/`：聚合重建、名称与独立 `EDIT_OUTLINE` 动作。
- `apps/core-api/src/main/resources/db/migration/V008__solution_workbench.sql`：方案、不可变版本、来源、节点和精简幂等表。
- `apps/core-api/src/main/java/com/yunjihai/rag/infrastructure/postgres/JdbcSolution*.java`：仓储、事务和幂等适配。
- `apps/core-api/src/main/java/com/yunjihai/rag/solution/application/`：应用事务、来源验证、授权视图、双人复核策略。
- `apps/core-api/src/main/java/com/yunjihai/rag/solution/web/`：Session API、DTO 与固定错误投影。
- `apps/web/src/api/solutions.ts`、`apps/web/src/views/Solution*.vue`：页面数据和操作。
- `apps/core-api/src/test/`、`apps/web/src/**/*.test.ts`、`apps/web/e2e/`：对应的领域、真实 PostgreSQL、API 和页面验收。

## 评审重点

1. 历史版本 UUID 属于另一方案或空间：详情和确认均隐藏，不泄露内容；任务 3、5 测试。
2. 同一幂等键并发提交与来源撤权后重放：最多一次写入，撤权后不返回旧正文；任务 4、5 测试。
3. 数据库事务中审计失败或乐观锁冲突：版本、聚合、幂等完成状态均不残留；任务 2、4 测试。
4. 系统管理员未获得空间读取权限：列表不出现方案，写和详情也被拒绝；任务 3、5 测试。
5. 页面保存失败或并发冲突：输入不丢、不会自动覆盖，窄屏仍能找到主操作；任务 6 测试。

---

### 任务 1：领域契约与版本重建

**文件：** 修改 `solution/domain/Solution.java`、`solution/SolutionAction.java`、`solution/SolutionWorkbenchService.java`；新增 `solution/domain/SolutionName.java` 和对应领域测试。

**接口：** `Solution.restore(...) -> Solution` 仅从受校验的持久状态重建；`SolutionName.of(String) -> SolutionName` 限长、规范化且非空；`SolutionAction.EDIT_OUTLINE` 供后续权限映射使用。具体持久状态参数建议收拢为不可变 `SolutionSnapshot`，包含现有聚合全部字段及名称，不允许绕过构造不变量。

- [ ] 先写 `SolutionAggregateTest`：新建/重建往返、非法指针与确认关系拒绝、名称空白与过长拒绝、编辑大纲不再使用确认权限。
- [ ] 运行 `./mvnw.cmd -pl apps/core-api -Dtest=SolutionAggregateTest,SolutionWorkbenchServiceTest test`，确认新增测试因接口缺失而失败。
- [ ] 实现受约束重建和动作分离；不改变已确认需求/大纲的失效语义。
- [ ] 重跑上述测试并确认通过；提交 `feat: prepare solution domain for persistence`。

### 任务 2：Flyway 与持久仓储

**文件：** 新增 `V008__solution_workbench.sql`、`JdbcSolutionRepository.java`、`JdbcRequirementVersionRepository.java`、`JdbcOutlineVersionRepository.java`；修改 `PostgresAdapterConfiguration.java`；新增 `JdbcSolutionRepositoryIntegrationTest.java`。

**接口：** 实现现有 `SolutionRepository`、`RequirementVersionRepository`、`OutlineVersionRepository` 的 `findById/insert/replace`；新增 `SolutionQueryPort.listBySpace(UUID spaceId,int page,int pageSize)` 返回有界摘要。聚合及所有版本从数据库完整重建，`replace` 以 `expectedRevision` CAS 更新。

- [ ] 先写真实 PostgreSQL 测试：Flyway 迁移、插入后新连接重建、版本不可变、跨方案版本指针拒绝、CAS 竞争仅一方成功、事务回滚不留孤儿版本。
- [ ] 运行 `./mvnw.cmd -pl apps/core-api -Dtest=JdbcSolutionRepositoryIntegrationTest,PostgresMigrationIntegrationTest test`，确认新增测试失败。
- [ ] 实现表约束与 JDBC 适配，版本内容使用关系行保存，定位 JSON 只解析白名单结构。
- [ ] 重跑上述测试并确认通过；提交 `feat: persist solution requirements and outlines`。

### 任务 3：空间授权、来源与双人复核

**文件：** 新增 `solution/application/SolutionAccessService.java`、`SolutionSourceAccess.java`、`SolutionReviewPolicy.java` 和测试；接入 `KnowledgeSpaceReadAuthorization`、现有角色和文档权限/发布状态端口。

**接口：** `requireView(UUID actorId,UUID spaceId,Instant at)`；`requireAction(UUID actorId,UUID spaceId,UUID solutionId,SolutionAction action,Instant at)`；`requireCurrentSources(UUID actorId,UUID spaceId,RequirementVersion version,Instant at)`；`canConfirm(UUID editorId,UUID reviewerId) -> boolean`。权限决策先检查空间读取，再映射固定角色；任何来源检查不完整或异常即拒绝。

- [ ] 先写权限测试：未入空间的系统管理员、跨空间来源、未发布来源、撤权来源、未知定位、同一人确认、版本不归属方案均失败关闭。
- [ ] 运行 `./mvnw.cmd -pl apps/core-api -Dtest=SolutionAccessServiceTest,SolutionSourceAccessTest test`，确认失败。
- [ ] 实现权限映射、来源校验和实例级双人复核默认策略；保留历史确认，详情动态计算有效性。
- [ ] 重跑上述测试并确认通过；提交 `feat: authorize solution edits and evidence sources`。

### 任务 4：事务应用服务与精简幂等

**文件：** 新增 `JdbcSolutionCommandIdempotencyStore.java`、`solution/application/SolutionCommandService.java`、对应真实 PostgreSQL 集成测试；修改 `PostgresAdapterConfiguration.java`。

**接口：** `SolutionCommandService.create/reviseRequirements/confirmRequirements/reviseOutline/confirmOutline` 分别接收服务端 actor、请求 DTO 与幂等键，返回已提交方案版本引用。精简幂等存储提供 `claim/complete/release`，键作用域为 actor、资源、动作；完成记录只含版本 ID 与修订号，绝不含正文。

- [ ] 先写测试：相同键同摘要只写一次、不同摘要 `409`、竞争提交、审计失败回滚、来源/空间撤权后重放不泄漏、确认人与编辑人相同时拒绝。
- [ ] 运行 `./mvnw.cmd -pl apps/core-api -Dtest=SolutionCommandServiceIntegrationTest test`，确认失败。
- [ ] 在事务边界实现写入与幂等结果完成；重放先复核权限与历史来源，再由结果引用恢复原响应。来源已撤权返回不含正文的固定冲突码。
- [ ] 重跑上述测试并确认通过；提交 `feat: transact and deduplicate solution commands`。

### 任务 5：HTTP API 与授权视图

**文件：** 新增 `solution/web/SolutionController.java`、`SolutionDtos.java`、`SolutionControllerSecurityIntegrationTest.java`；修改 `ApiExceptionHandler.java`、`PostgresAdapterConfiguration.java`。

**接口：** 严格实现规格第 4 节的七个 `/api/v1` 路由。`GET` 详情返回当前版本与 `allowedActions`；列表只查询当前有权空间并稳定分页。DTO 由服务端生成 ID、时间和版本号，客户端不得提供 actor。

- [ ] 先写 HTTP 测试：未登录 `401`，无权与不存在 `404`，并发与幂等冲突 `409`，未确认需求编辑大纲 `422`，权限源异常 `503`，历史版本 UUID 无法越权读取，撤权重放无正文。
- [ ] 运行 `./mvnw.cmd -pl apps/core-api -Dtest=SolutionControllerSecurityIntegrationTest test`，确认失败。
- [ ] 实现 Controller、输入边界和错误投影；`allowedActions` 每次由当前权限及流程状态计算。
- [ ] 重跑上述测试并确认通过；提交 `feat: expose governed solution workbench api`。

### 任务 6：Vue 方案列表、需求与大纲页面

**文件：** 新增 `apps/web/src/api/solutions.ts`、`apps/web/src/views/SolutionListView.vue`、`SolutionDetailView.vue` 及测试；修改 `apps/web/src/router/index.ts`、`App.vue`；新增 `apps/web/e2e/solution-workbench.spec.ts`。

**接口：** API 模型与任务 5 的 DTO 同名字段一致；列表路由 `/spaces/:spaceId/solutions`，详情路由 `/solutions/:solutionId`。需求编辑显示分类/正文/来源，大纲编辑显示章节/层级/顺序/必填/风险；确认展示准确版本并单独提交。来源选择复用现有 `searchApi.search(spaceId, queryText)` 返回的 `documentId`、`versionId` 和 `locator`，提交时仍由后端重新验证。

- [ ] 先写 Vitest 与 Playwright 测试：无权按钮隐藏、创建到确认完整路径、保存失败保留输入、离开提示、`409` 不覆盖、空状态/加载/错误、窄屏主操作可见和键盘操作。
- [ ] 运行 `corepack pnpm --dir apps/web test --run`，确认新增测试失败。
- [ ] 实现 API 包装及页面路由，依据后端 `allowedActions` 渲染操作；不把正文写入 URL 或浏览器日志。
- [ ] 运行 `corepack pnpm --dir apps/web test --run`、`corepack pnpm --dir apps/web build`、`corepack pnpm --dir apps/web exec playwright test solution-workbench.spec.ts`，确认通过；提交 `feat: add solution requirements and outline views`。

### 任务 7：纵向验收、运行说明与完整回归

**文件：** 新增 `apps/core-api/src/test/java/com/yunjihai/rag/acceptance/PostgresSolutionWorkbenchAcceptanceTest.java`、`docs/runbooks/solution-workbench-first-slice.md`；更新 `README.md` 当前状态。

**接口：** 验收用虚构数据，从空间授权、创建、修订、交叉确认、重启恢复、撤权、并发冲突走完完整路径。运行手册写明配置、迁移、恢复、故障与尚未生产准入边界。

- [ ] 先写纵向验收测试并运行，确认在缺少业务装配时失败。
- [ ] 完成装配和运行说明，验证撤权后读/重放失败关闭且重启后仍保留历史状态。
- [ ] 运行 `./mvnw.cmd -pl apps/core-api clean verify`、`corepack pnpm --dir apps/web test --run`、`corepack pnpm --dir apps/web build`、`corepack pnpm --dir apps/web exec playwright test`、`powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/verify.ps1`；记录无法运行的环境门禁，失败不能说成通过。
- [ ] 检查 `git diff --check`、变更范围与密钥泄漏，确认没有试点标签；提交 `test: accept production solution workbench first slice`。

## 完成判据

七项任务均有 RED→GREEN 证据和本地提交。真实 PostgreSQL、API、Vue 及浏览器验收证明需求和大纲可从页面创建、确认、恢复；无权用户不可见，撤权与并发不会泄漏或覆盖内容。所有可运行门禁通过并如实记录外部环境限制，分支保留在本地，等待用户另行指示推送。
