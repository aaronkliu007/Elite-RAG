# 方案工作台分章生成与核验实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将现有分章生成业务内核装配为可恢复、可核验、可人工采纳的业务闭环。

**Architecture:** PostgreSQL 是生成尝试和章节版本的权威存储；Spring 应用层执行权限、版本和事务门禁。模型调用只通过已审批网关，核验结果随不可变版本保存。Vue 页面以服务端能力集展示操作。

**Tech Stack:** Java 21、Spring Boot、PostgreSQL/Flyway、Testcontainers、Vue、Vitest、Playwright。

**Spec:** `docs/superpowers/specs/2026-10-28-solution-section-generation-design.md`

## Global Constraints

- 在 `E:\git_repo\Elite-RAG` 工作；中文文档；不推送远程。
- 一家公司独立部署一套实例；调用外部模型前复核来源权限。
- 生产业务状态由 PostgreSQL 持久化；生成结果不可自动采纳。

## Review Focus

- 同一客户端键并发请求只产生一个尝试，输入改变返回冲突。
- 陈旧修订号不能覆盖后续状态。
- 进程中断后状态可读取并可明确恢复或终止。
- 草稿与尝试成功状态必须同事务提交或同事务回滚。
- 撤权后不得从历史成功尝试泄漏正文。

---

### Task 1: PostgreSQL 生成尝试

**Files:**
- Create: `apps/core-api/src/main/resources/db/migration/V010__solution_generation_attempt.sql`
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/infrastructure/postgres/JdbcGenerationAttemptStore.java`
- Create: `apps/core-api/src/test/java/com/yunjihai/rag/infrastructure/postgres/JdbcGenerationAttemptStoreIntegrationTest.java`

**Interfaces:** Implements `GenerationAttemptStore.findByClientKey`, `register`, `replace`. `register` returns the existing attempt on key conflict; `replace` is CAS on `revision`.

- [x] Write real PostgreSQL integration tests for roundtrip, duplicate registration, fingerprint conflict, concurrent registration and stale CAS.
- [x] Run targeted test and confirm it fails for missing migration/adapter.
- [x] Add migration and adapter; store evidence IDs in a typed PostgreSQL UUID array and all other fields in typed columns so each CAS is one atomic statement.
- [x] Run targeted test and confirm it passes.
- [x] Commit this independently testable persistence unit.

### Task 2: 章节版本和原子提交

**Files:** `V011__solution_section_version.sql`, `JdbcSectionVersionRepository.java`, `JdbcSectionGenerationCommitPort.java`, matching PostgreSQL integration tests.

**Interfaces:** Implements `SectionVersionRepository` and `SectionGenerationCommitPort.commitIfCurrent`; transaction verifies confirmed requirement/outline and adopted baseline, inserts immutable draft and updates attempt atomically.

- [x] Write failing PostgreSQL tests for roundtrip, foreign solution/section, stale version, rollback and duplicate attempt checkpoint.
- [x] Run targeted tests; implement schema and adapters.
- [x] Run full Java regression and commit.

### Task 3: Model, recovery and HTTP

**Files:** Add approved `SolutionModelPort`, semantic verification adapter, generation application service and controller/DTO; update `SectionGenerationService` recovery and Spring configuration; matching unit/API tests.

**Interfaces:** Start/get/retry/adopt endpoints under `/api/v1/solutions/{solutionId}/sections/...` with session auth and `Idempotency-Key`.

- [x] Write failing tests for denied access, stale versions, interrupted attempts, invalid model references and model outage.
- [x] Implement adapter and recovery with bounded lease/retry semantics; wire HTTP and audit. Wrap human adoption in an outer transaction that includes section insert and solution CAS; the JDBC repository rejects ADOPTED writes without this transaction.
- [x] Run Java regression and commit.

### Task 4: Vue closed loop and acceptance

**Files:** Update solution workbench views/API client/types, add component and Playwright tests, update Chinese runbook.

- [ ] Write failing UI tests for status, verification, human adoption, conflict and responsive layout.
- [ ] Implement UI with server returned capabilities; run accessibility and layout gate.
- [ ] Run unified verification, review the diff and commit.
