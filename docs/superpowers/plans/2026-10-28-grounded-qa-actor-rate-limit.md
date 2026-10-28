# Grounded QA Actor Rate Limit Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为问答提交增加按操作者、跨实例一致的固定窗口限流，超限返回 429，且不创建问答记录、不调用模型。

**Architecture:** 在幂等执行器取得新请求所有权后、生成 requestId 前调用 `QaActorRateLimiter`。PostgreSQL 适配器通过使用数据库时钟的单行 UPSERT 原子维护每个操作者的固定窗口计数，并在独立短事务中提交；同一幂等键的已完成重放直接返回缓存结果，不重复消耗限额。

**Tech Stack:** Java 25、Spring Boot、Spring JDBC、PostgreSQL 17、Flyway、JUnit 6、Testcontainers

**Spec:** `docs/superpowers/plans/2026-09-24-pilot-hardening.md` Task 5

## Global Constraints

- 首版一家公司独立部署一套实例，但限流必须支持同实例多副本。
- 超限响应固定为 HTTP 429、错误码 `QA_RATE_LIMITED`，并返回整数秒 `Retry-After`。
- 限流只作用于新的 POST 问答提交；GET 查询和同幂等键重放不重复计数。
- 超限发生在问答记录创建与模型调用之前。
- 默认每个操作者每分钟最多 20 个新问答，可通过环境变量调整。

## Review Focus

- 同一操作者并发提交时计数不得超发；由 PostgreSQL UPSERT 集成测试覆盖。
- 窗口边界到达后首个请求必须重置为 1；由适配器集成测试覆盖。
- 超限不得创建问答记录或调用模型；由控制器测试覆盖。
- 同幂等键重放不得重复消耗限额；由幂等集成测试覆盖。
- 配置为零、负数或非正时长必须启动失败；由适配器构造测试覆盖。

---

### Task 1: 限流领域契约与 HTTP 语义

**Files:**
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/qa/QaActorRateLimiter.java`
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/qa/QaRateLimitExceededException.java`
- Modify: `apps/core-api/src/main/java/com/yunjihai/rag/qa/web/QaController.java`
- Modify: `apps/core-api/src/main/java/com/yunjihai/rag/api/ApiExceptionHandler.java`
- Test: `apps/core-api/src/test/java/com/yunjihai/rag/qa/web/QaControllerTest.java`
- Test: `apps/core-api/src/test/java/com/yunjihai/rag/qa/web/QaIdempotencyIntegrationTest.java`
- Test: `apps/core-api/src/test/java/com/yunjihai/rag/api/ApiExceptionHandlerTest.java`

**Interfaces:**
- Produces: `void QaActorRateLimiter.acquire(UUID actorId)`。
- Produces: `QaRateLimitExceededException.retryAfterSeconds()`，取值至少为 1。

- [x] **Step 1:** 先写控制器、幂等重放和 429 契约失败测试。
- [x] **Step 2:** 运行定向测试，确认因限流契约缺失而失败。
- [x] **Step 3:** 最小实现端口、异常、控制器调用顺序和异常映射。
- [x] **Step 4:** 重跑定向测试并确认通过。

### Task 2: PostgreSQL 原子限流适配器与配置

**Files:**
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/infrastructure/postgres/JdbcQaActorRateLimiter.java`
- Create: `apps/core-api/src/main/resources/db/migration/V007__qa_actor_rate_limit.sql`
- Modify: `apps/core-api/src/main/java/com/yunjihai/rag/infrastructure/postgres/PostgresAdapterConfiguration.java`
- Modify: `apps/core-api/src/main/resources/application-local.yml`
- Modify: `infra/compose/.env.example`
- Modify: `docs/runbooks/grounded-qa.md`
- Test: `apps/core-api/src/test/java/com/yunjihai/rag/infrastructure/postgres/JdbcQaActorRateLimiterIntegrationTest.java`
- Test: `apps/core-api/src/test/java/com/yunjihai/rag/infrastructure/postgres/PostgresMigrationIntegrationTest.java`

**Interfaces:**
- Consumes: `QaActorRateLimiter.acquire(UUID)`。
- Produces: `JdbcQaActorRateLimiter(JdbcClient, Duration window, int maxRequests)`。

- [x] **Step 1:** 写固定窗口、跨用户隔离、并发和窗口重置失败测试。
- [x] **Step 2:** 运行定向测试，确认适配器和 V007 尚不存在。
- [x] **Step 3:** 实现原子 UPSERT、配置装配、迁移和运维说明。
- [x] **Step 4:** 运行定向测试、全量测试、打包和差异检查。
- [x] **Step 5:** 创建本地 Git 提交，不推送远程。
