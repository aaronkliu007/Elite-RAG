# 分章生成超时尝试扫描实施计划

> **面向执行者：** 使用 `superpowers:executing-plans` 按任务执行，先观察测试失败再写实现。

**目标：** 进程中断后，过期的分章生成尝试无需用户再次提交也能进入可重试状态。

**架构：** PostgreSQL 以数据库时钟和 `FOR UPDATE SKIP LOCKED` 批量锁定过期行，原子写入 `FAILED_RETRYABLE`、原状态检查点和修订号。Spring 定时器只执行扫描，不自动重试模型。`VERIFYING` 保留已有草稿检查点恢复路径。

**技术栈：** Java 21、Spring Boot、PostgreSQL 17、Flyway、JUnit、Testcontainers。

**规格：** `docs/superpowers/specs/2026-10-28-solution-section-generation-design.md`

## 全局约束

- 在 `E:\git_repo\Elite-RAG` 工作；中文文档；只提交本地 Git commit。
- 只处理 `PENDING`、`RETRIEVING`、`GENERATING`；不重发模型请求，不改变已完成结果。
- 租约使用数据库时钟；并发扫描不能重复转移同一尝试。

## 评审重点

- 刚有进展的任务不能被扫描器标为过期。
- 多实例同时扫描不能重复处理，也不能覆盖生成线程的新状态。
- `VERIFYING` 的持久草稿检查点不能丢失。
- 批量大小有上限，数据库故障不能让调度任务永久停止。
- 切换开关后不会意外启动扫描器。

---

### 任务 1：PostgreSQL 过期状态转换

**文件：** `JdbcGenerationAttemptStore.java`、`JdbcGenerationAttemptStoreIntegrationTest.java`、`V016__generation_attempt_recovery_scan_index.sql`。

**接口：** `int expireStale(Duration recoveryAfter, int batchSize)`。

- [x] 添加过期与新鲜记录、三种活动状态、`VERIFYING` 排除及并发扫描测试。
- [x] 运行定向测试，确认因缺失方法而失败。
- [x] 用数据库时钟和 `FOR UPDATE SKIP LOCKED` 实现有界批量原子更新，增加部分索引。
- [x] 重跑定向测试，确认通过。

### 任务 2：周期调度和运行说明

**文件：** `GenerationAttemptRecoveryScheduler.java`、`WorkerRuntimeConfiguration.java`、`application-local.yml`、调度测试、`solution-workbench-core.md`。

**接口：** `int scanOnce()`；定时运行受 `rag.workers.enabled` 与 `rag.solution.generation.recovery.enabled` 控制。

- [x] 添加异常后可继续下一周期的行为测试；开关和批量参数由 Spring 属性及 Bean 条件控制。
- [x] 运行测试，确认缺少调度器时失败。
- [x] 装配定时器与配置，补充中文操作说明。
- [x] 运行 Java 全量回归并检查差异；本地 commit 在最终复核后完成。

## 执行裁定

- 尝试创建、阶段更新时间和请求内租约判断改用 PostgreSQL 时钟，与扫描器保持同一时间基准；否则多节点时钟偏差会导致误回收。
- 恢复租约必须长于分章模型截止时间及已启用网关的总超时；否则同键重试可能在原模型调用完成前再次计费。
- 集成测试的扫描数量按当时全表符合条件的记录判断，不依赖 JUnit 方法执行顺序。
- 全量回归暴露既有限流 SQL 在行锁等待前取时钟导致并发超放行；同步修复该边界并重测。
- 模型调用前刷新 `GENERATING` 租约；如果扫描器已先回收，CAS 冲突会阻止模型调用。
- 限流冲突更新对窗口计数和窗口起点共用一次行锁后的数据库时钟，避免边界跨越造成错误计数。
