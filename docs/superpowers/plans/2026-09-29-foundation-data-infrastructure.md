# 知识底座数据基础设施实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 用 PostgreSQL、Flyway、MinIO 和生产适配器替换知识底座的内存基础设施，并以真实数据库证明并发、租约、审计和事务回滚语义。

**Architecture:** 领域服务继续依赖现有端口，`infrastructure/postgres` 使用 Spring `JdbcClient` 和显式 SQL实现端口，Flyway 管理规范化 Schema；`infrastructure/minio` 实现不可变内容寻址对象。应用服务现有 `@Transactional` 是业务事务边界，Testcontainers 负责真实 PostgreSQL/MinIO 契约、并发与故障注入验证。

**Tech Stack:** Java 21、Spring Boot 4.1.1、Spring JdbcClient、PostgreSQL、Flyway、MinIO Java SDK、Testcontainers、Docker Compose、JUnit 5、AssertJ。

**Spec:** `docs/superpowers/specs/2026-09-29-foundation-data-infrastructure-design.md`

## Global Constraints

- 固定工作目录为 `E:\git\_repo\Elite-RAG`。
- 数据留在内网；本批不调用云模型，不接入真实客户资料。
- Java 21 / Spring Boot 是业务事实和事务边界的唯一所有者。
- PostgreSQL 是权威数据源；MinIO 只保存不可变原始文件。
- Python Worker 不持有数据库或 MinIO 凭据。
- 权限默认拒绝；未授权和不存在资源对外保持相同语义。
- 领域对象不得依赖 Spring JDBC、数据库行模型、MinIO SDK 或配置类型。
- 数据访问使用 Flyway + PostgreSQL + Spring `JdbcClient` 显式 SQL；不得用 H2 替代 PostgreSQL，也不得把聚合整体保存为 JSONB。
- 数据库迁移账号与运行账号分离，生产密钥不得进入 Git、日志或审计。
- 每项功能先写失败测试，只实现让测试通过的最小代码，并在任务末独立提交。
- 本批不引入 OpenSearch、向量检索、Embedding/重排/生成模型、方案工作台持久化、业务 API/UI，也不实现物理 Blob 删除 Worker。
- 本批不得创建 `pilot-foundation-v1`、`pilot-retrieval-v1` 或 `pilot-workbench-v1` 标签。

## Review Focus

- 外部用户/部门 ID 为空、超长或重复时必须整批拒绝组织同步，不能产生部分映射；Task 3 增加对应 PostgreSQL 契约测试。
- ACL 的 `valid_until` 恰好等于查询时间时必须视为过期；Task 5 增加边界测试。
- 已存在的 MinIO 对象与请求长度或媒体类型不一致时必须失败，不能把冲突对象当作幂等成功；Task 7 增加元数据冲突测试。
- Canonical 节点数量、父子关系和 JSONB 结构非法时必须在写入前失败且整笔事务回滚；Task 6 增加负样本与故障注入测试。
- PostgreSQL `timestamptz` 往返不得依赖本机时区，微秒精度内必须稳定；Task 2 的共享持久化契约测试覆盖 UTC 与非 UTC JVM 时区。

---

### Task 1: PostgreSQL/MinIO Compose 与 Flyway 基线

**Files:**
- Create: `infra/compose/compose.yaml`
- Create: `infra/compose/.env.example`
- Create: `infra/compose/postgres-init/001-create-runtime-role.sh`
- Create: `apps/core-api/src/main/resources/application.yml`
- Create: `apps/core-api/src/main/resources/application-local.yml`
- Create: `apps/core-api/src/main/resources/db/migration/V001__foundation.sql`
- Create: `apps/core-api/src/test/java/com/yunjihai/rag/infrastructure/postgres/PostgresMigrationIntegrationTest.java`

**Interfaces:**
- Consumes: approved schema in `docs/superpowers/specs/2026-09-29-foundation-data-infrastructure-design.md`.
- Produces: PostgreSQL tables `app_user`, `org_unit`, `org_membership`, `knowledge_space`, `document`, `document_version`, `active_blob_reservation`, `acl_entry`, `ingestion_job`, `canonical_document`, `canonical_node`, `audit_event`, `blob_cleanup_candidate`; local PostgreSQL/MinIO endpoints and `rag-documents` bucket.

- [ ] **Step 1: Write the failing migration contract test**

Create `PostgresMigrationIntegrationTest` with tests named:

- `migratesAnEmptyPostgresDatabaseWithAllFoundationTablesAndConstraints`
- `rejectsDuplicateExternalIdsDocumentVersionsAndActiveBlobReservations`
- `rejectsPublishedVersionFromAnotherDocument`
- `runningRoleCannotCreateOrAlterSchema`
- `runningRoleCannotUpdateOrDeleteAuditEvents`
- `flywayValidateSucceedsAfterRestart`
- `flywayValidateRejectsMissingOutOfOrderOrChecksumChangedMigration`

Assert the exact 13 tables above, UUID/timestamptz column types, foreign keys, enum/check constraints, unique keys, and the separation between migration and runtime roles.

- [ ] **Step 2: Run the migration test and verify RED**

Run:

```powershell
$env:JAVA_HOME='C:\Program Files\Zulu\zulu-21'
.\mvnw.cmd -q -pl apps/core-api -Dtest=PostgresMigrationIntegrationTest test
```

Expected: FAIL because `V001__foundation.sql` and Compose/configuration do not exist.

- [ ] **Step 3: Implement the migration and local infrastructure**

Reuse the already pinned PostgreSQL、Testcontainers 和 Flyway dependencies. Implement `V001__foundation.sql` with explicit foreign keys, unique keys, checks, optimistic revision columns, append-only audit permissions, and indexes for ACL lookup and job claiming. Enforce the published-version invariant with a composite candidate key on `document_version(document_id, id)` and a composite foreign key from `document(id, current_published_version_id)`. Add Compose services `postgres`, `minio`, and `minio-init`; bind development ports to `127.0.0.1`, use health checks, and source credentials from environment variables with non-production example values in `.env.example`. Mount `001-create-runtime-role.sh` into PostgreSQL initialization; it creates the login role from `RAG_DB_RUNTIME_USER`/`RAG_DB_RUNTIME_PASSWORD`, while the image's `POSTGRES_USER` remains the migration owner. Track the script as executable and never embed a password in it.

- [ ] **Step 4: Verify migration and Compose configuration GREEN**

Run:

```powershell
.\mvnw.cmd -q -pl apps/core-api -Dtest=PostgresMigrationIntegrationTest test
docker compose -f infra/compose/compose.yaml config --quiet
```

Expected: PASS and exit code 0.

- [ ] **Step 5: Commit**

```powershell
git add apps/core-api/src/main/resources infra/compose apps/core-api/src/test/java/com/yunjihai/rag/infrastructure/postgres/PostgresMigrationIntegrationTest.java
git commit -m "feat: add foundation data infrastructure"
```

### Task 2: JdbcClient 共享边界与只追加审计

**Files:**
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/infrastructure/postgres/PostgresPersistenceExceptionTranslator.java`
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/infrastructure/postgres/JdbcAuditEventStore.java`
- Create: `apps/core-api/src/test/java/com/yunjihai/rag/infrastructure/postgres/JdbcAuditEventStoreIntegrationTest.java`
- Create: `apps/core-api/src/test/java/com/yunjihai/rag/infrastructure/postgres/PostgresTimeMappingIntegrationTest.java`

**Interfaces:**
- Consumes: Task 1 `audit_event` table and runtime datasource.
- Produces: `JdbcAuditEventStore implements AuditEventStore`; shared fixed-code translation for unique, foreign-key, check and optimistic-lock failures.

- [ ] **Step 1: Write failing audit and time-mapping tests**

Test `appendPersistsOnlyWhitelistedMetadataWithoutUpdateOrDeletePath`, `duplicateAuditIdIsRejectedWithStableCode`, and `instantRoundTripsIndependentOfJvmDefaultTimezoneWithinMicrosecondPrecision`. Change the JVM default timezone during the latter test and restore it in `finally`.

- [ ] **Step 2: Run tests and verify RED**

Run:

```powershell
.\mvnw.cmd -q -pl apps/core-api '-Dtest=JdbcAuditEventStoreIntegrationTest,PostgresTimeMappingIntegrationTest' test
```

Expected: FAIL because the JdbcClient store and translator do not exist.

- [ ] **Step 3: Implement the shared persistence boundary**

Implement `JdbcAuditEventStore(JdbcClient)` with `void append(AuditEvent event)`. Store metadata as JSONB after the existing `AuditService` whitelist has accepted it. Implement the translator with stable error codes and no SQL, constraint name, connection string or driver message in the public exception.

- [ ] **Step 4: Run tests and verify GREEN**

Run the Task 2 test command. Expected: PASS.

- [ ] **Step 5: Commit**

```powershell
git add apps/core-api/src/main/java/com/yunjihai/rag/infrastructure/postgres apps/core-api/src/test/java/com/yunjihai/rag/infrastructure/postgres
git commit -m "feat: persist append-only audit events"
```

### Task 3: 组织对账与会话持久化

**Files:**
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/infrastructure/postgres/JdbcOrganizationReconciliationAdapter.java`
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/infrastructure/postgres/JdbcSessionVersionStore.java`
- Create: `apps/core-api/src/test/java/com/yunjihai/rag/infrastructure/postgres/JdbcOrganizationIntegrationTest.java`

**Interfaces:**
- Consumes: Task 1 `app_user`, `org_unit`, `org_membership`; Task 2 exception translation.
- Produces: `JdbcOrganizationReconciliationAdapter implements OrganizationReconciliationPort`; `JdbcSessionVersionStore implements SessionVersionStore`.

- [ ] **Step 1: Write failing organization tests**

Cover:

- `completeSnapshotAtomicallyUpsertsUsersDepartmentsAndMemberships`
- `removedOrDisabledUsersAreDisabledAndTheirSessionVersionAdvances`
- `incompleteSnapshotLeavesPreviousRowsUntouched`
- `blankOversizedOrDuplicateExternalIdsRejectTheWholeSnapshot`
- `departmentParentCycleRejectsTheWholeSnapshot`
- `sessionDisableFailureRollsBackReconciliationAndVersionAdvance`

Assert newly created internal IDs are UUIDv7-compatible and remain stable across later snapshots.

- [ ] **Step 2: Run tests and verify RED**

Run:

```powershell
.\mvnw.cmd -q -pl apps/core-api -Dtest=JdbcOrganizationIntegrationTest test
```

Expected: FAIL because both adapters are missing.

- [ ] **Step 3: Implement atomic reconciliation and session storage**

Implement `ReconciliationResult reconcile(OrganizationSnapshot snapshot)`, `long currentVersion(String userExternalId)`, `long advanceVersion(String userExternalId)`, `boolean isEnabled(String userExternalId)`, and `void setEnabled(String userExternalId, boolean enabled)`. Validate the entire snapshot before the first write, preserve external-to-internal UUID mappings, use batch SQL, and never delete the previous snapshot on invalid input.

- [ ] **Step 4: Run tests and verify GREEN**

Run the Task 3 test command. Expected: PASS.

- [ ] **Step 5: Commit**

```powershell
git add apps/core-api/src/main/java/com/yunjihai/rag/infrastructure/postgres/JdbcOrganizationReconciliationAdapter.java apps/core-api/src/main/java/com/yunjihai/rag/infrastructure/postgres/JdbcSessionVersionStore.java apps/core-api/src/test/java/com/yunjihai/rag/infrastructure/postgres/JdbcOrganizationIntegrationTest.java
git commit -m "feat: persist organization and session state"
```

### Task 4: 知识空间、文档版本与并发 Blob 预留

**Files:**
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/infrastructure/postgres/JdbcKnowledgeSpaceRepository.java`
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/infrastructure/postgres/JdbcDocumentRepository.java`
- Create: `apps/core-api/src/test/java/com/yunjihai/rag/infrastructure/postgres/JdbcDocumentRepositoryIntegrationTest.java`
- Create: `apps/core-api/src/test/java/com/yunjihai/rag/infrastructure/postgres/DocumentTransactionIntegrationTest.java`

**Interfaces:**
- Consumes: Task 1 knowledge/document/reservation tables; Task 2 audit and exception translation.
- Produces: `JdbcKnowledgeSpaceRepository implements KnowledgeSpaceRepository`; `JdbcDocumentRepository implements DocumentRepository` with database-enforced active Blob deduplication and revision locking.

- [ ] **Step 1: Write failing repository and concurrency tests**

Cover aggregate round-trip with multiple immutable versions, current published pointer and archive state; `findActiveVersionBySpaceAndSourceBlob`; archive atomically removes all active reservations for the document without deleting version rows; one-winner optimistic locking; two concurrent creates for the same `(spaceId, sha256)`; and rollback of document/version/reservation when audit append fails.

- [ ] **Step 2: Run tests and verify RED**

Run:

```powershell
.\mvnw.cmd -q -pl apps/core-api '-Dtest=JdbcDocumentRepositoryIntegrationTest,DocumentTransactionIntegrationTest' test
```

Expected: FAIL because the knowledge adapters are missing.

- [ ] **Step 3: Implement explicit document SQL**

Implement `insert`, `findById`, `findActiveVersionBySpaceAndSourceBlob`, and `replace(DocumentAggregate document, long expectedRevision)`. Reconstruct the aggregate from ordered version rows, reserve active Blob hashes within the transaction, use `UPDATE ... WHERE revision = ?`, and translate zero updated rows to `ConcurrentDocumentUpdateException` without leaking SQL details.

- [ ] **Step 4: Run tests and verify GREEN**

Run the Task 4 test command. Expected: PASS.

- [ ] **Step 5: Commit**

```powershell
git add apps/core-api/src/main/java/com/yunjihai/rag/infrastructure/postgres/JdbcKnowledgeSpaceRepository.java apps/core-api/src/main/java/com/yunjihai/rag/infrastructure/postgres/JdbcDocumentRepository.java apps/core-api/src/test/java/com/yunjihai/rag/infrastructure/postgres/JdbcDocumentRepositoryIntegrationTest.java apps/core-api/src/test/java/com/yunjihai/rag/infrastructure/postgres/DocumentTransactionIntegrationTest.java
git commit -m "feat: persist immutable knowledge documents"
```

### Task 5: PostgreSQL ACL 查询与命令适配器

**Files:**
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/infrastructure/postgres/JdbcPrincipalMembershipRepository.java`
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/infrastructure/postgres/JdbcAccessDecisionRepository.java`
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/infrastructure/postgres/JdbcAclCommandRepository.java`
- Create: `apps/core-api/src/test/java/com/yunjihai/rag/infrastructure/postgres/JdbcAclIntegrationTest.java`

**Interfaces:**
- Consumes: Task 1 ACL/organization/document tables; Task 2 audit store.
- Produces: production implementations of `PrincipalMembershipRepository`, `AccessDecisionRepository`, and `AclCommandRepository`.

- [ ] **Step 1: Write failing ACL tests**

Cover user plus ancestor-department expansion, default deny, space grant, document restriction intersection, revoked grants, unpublished/archived denial, `valid_until == at` expiry, duplicate grant idempotency, and rollback when ACL audit append fails. Assert unknown role/project-group memberships remain empty rather than fabricated.

- [ ] **Step 2: Run tests and verify RED**

Run:

```powershell
.\mvnw.cmd -q -pl apps/core-api -Dtest=JdbcAclIntegrationTest test
```

Expected: FAIL because JDBC ACL adapters do not exist.

- [ ] **Step 3: Implement ACL adapters**

Implement a recursive CTE for ancestor departments and a single authoritative read-decision query over current memberships, ACL rows, document publication and expiry. Implement grant/revoke commands with active-row uniqueness and no hard delete. Preserve the existing `AccessSnapshot` semantics so authorization logic remains in `AccessDecisionService`.

- [ ] **Step 4: Run tests and verify GREEN**

Run the Task 5 test command. Expected: PASS.

- [ ] **Step 5: Commit**

```powershell
git add apps/core-api/src/main/java/com/yunjihai/rag/infrastructure/postgres/JdbcPrincipalMembershipRepository.java apps/core-api/src/main/java/com/yunjihai/rag/infrastructure/postgres/JdbcAccessDecisionRepository.java apps/core-api/src/main/java/com/yunjihai/rag/infrastructure/postgres/JdbcAclCommandRepository.java apps/core-api/src/test/java/com/yunjihai/rag/infrastructure/postgres/JdbcAclIntegrationTest.java
git commit -m "feat: enforce ACLs from PostgreSQL"
```

### Task 6: PostgreSQL 入库租约、解析结果与原子完成

**Files:**
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/infrastructure/postgres/JdbcIngestionJobRepository.java`
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/infrastructure/postgres/JdbcParsedDocumentStore.java`
- Create: `apps/core-api/src/test/java/com/yunjihai/rag/infrastructure/postgres/JdbcIngestionJobIntegrationTest.java`
- Create: `apps/core-api/src/test/java/com/yunjihai/rag/infrastructure/postgres/IngestionCompletionTransactionIntegrationTest.java`

**Interfaces:**
- Consumes: Task 1 ingestion/canonical tables; Tasks 2 and 4 audit/document adapters.
- Produces: `JdbcIngestionJobRepository implements IngestionJobRepository`; `JdbcParsedDocumentStore implements ParsedDocumentStore`.

- [ ] **Step 1: Write failing lease and completion tests**

Cover FIFO claim, `next_attempt_at`, concurrent `SKIP LOCKED` claims, expired lease takeover, stale token rejection, retry/final failure state, idempotent canonical replacement, invalid node count/parent/JSONB rejection, and exact rollback when failure occurs after canonical document, node, document status, task status, or audit writes.

- [ ] **Step 2: Run tests and verify RED**

Run:

```powershell
.\mvnw.cmd -q -pl apps/core-api '-Dtest=JdbcIngestionJobIntegrationTest,IngestionCompletionTransactionIntegrationTest' test
```

Expected: FAIL because the JDBC ingestion stores are missing.

- [ ] **Step 3: Implement job claiming and canonical persistence**

Implement `claimNext` as one CTE/UPDATE with `FOR UPDATE SKIP LOCKED ... RETURNING`. Make `complete`, `retry`, and `fail` conditional on the active lease token. Implement `replace(DocumentVersionId, CanonicalDocumentBody, Instant)` as delete/insert for the same immutable version key inside the caller transaction, preserving stable node order and validated JSONB fields.

- [ ] **Step 4: Run tests and verify GREEN**

Run the Task 6 test command. Expected: PASS.

- [ ] **Step 5: Commit**

```powershell
git add apps/core-api/src/main/java/com/yunjihai/rag/infrastructure/postgres/JdbcIngestionJobRepository.java apps/core-api/src/main/java/com/yunjihai/rag/infrastructure/postgres/JdbcParsedDocumentStore.java apps/core-api/src/test/java/com/yunjihai/rag/infrastructure/postgres/JdbcIngestionJobIntegrationTest.java apps/core-api/src/test/java/com/yunjihai/rag/infrastructure/postgres/IngestionCompletionTransactionIntegrationTest.java
git commit -m "feat: persist recoverable ingestion jobs"
```

### Task 7: MinIO 客户端、补偿登记与健康检查

**Files:**
- Modify: `apps/core-api/pom.xml`
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/ingestion/BlobCleanupEligibilityRepository.java`
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/infrastructure/postgres/JdbcOrphanBlobRecorder.java`
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/infrastructure/postgres/JdbcBlobCleanupEligibilityRepository.java`
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/infrastructure/minio/MinioClientProperties.java`
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/infrastructure/minio/SdkMinioObjectClient.java`
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/infrastructure/minio/MinioReadinessHealthIndicator.java`
- Create: `apps/core-api/src/test/java/com/yunjihai/rag/infrastructure/minio/MinioBlobStoreIntegrationTest.java`
- Create: `apps/core-api/src/test/java/com/yunjihai/rag/infrastructure/postgres/JdbcBlobCleanupIntegrationTest.java`

**Interfaces:**
- Consumes: Task 1 MinIO service and cleanup/reservation tables; existing `MinioBlobStore` and `MinioObjectClient`.
- Produces: SDK-backed `MinioObjectClient`; persistent `OrphanBlobRecorder`; `boolean isEligible(OrphanBlobCandidate candidate)` on `BlobCleanupEligibilityRepository`; MinIO readiness contributor.

- [ ] **Step 1: Write failing MinIO and cleanup tests**

Cover first write `created=true`, identical repeat `created=false`, concurrent identical writes, deterministic key without filename, existing-object length/media-type mismatch rejection, cleanup candidate deduplication, and `isEligible` returning false while any document version or active reservation references the SHA-256. Test missing/read-only bucket readiness as DOWN without logging credentials.

- [ ] **Step 2: Run tests and verify RED**

Run:

```powershell
.\mvnw.cmd -q -pl apps/core-api '-Dtest=MinioBlobStoreIntegrationTest,JdbcBlobCleanupIntegrationTest' test
```

Expected: FAIL because SDK/configuration and cleanup adapters are missing.

- [ ] **Step 3: Implement MinIO and cleanup boundaries**

Add the pinned MinIO Java SDK. Implement `SdkMinioObjectClient.putIfAbsent(...)` with conditional/atomic creation semantics and post-race metadata verification; never overwrite. Persist orphan candidates idempotently. Implement the eligibility query only—do not add a physical deletion worker. The health indicator must use a dedicated probe object/prefix and must not touch business objects.

- [ ] **Step 4: Run tests and verify GREEN**

Run the Task 7 test command. Expected: PASS.

- [ ] **Step 5: Commit**

```powershell
git add apps/core-api/pom.xml apps/core-api/src/main/java/com/yunjihai/rag/ingestion/BlobCleanupEligibilityRepository.java apps/core-api/src/main/java/com/yunjihai/rag/infrastructure/minio apps/core-api/src/main/java/com/yunjihai/rag/infrastructure/postgres/JdbcOrphanBlobRecorder.java apps/core-api/src/main/java/com/yunjihai/rag/infrastructure/postgres/JdbcBlobCleanupEligibilityRepository.java apps/core-api/src/test/java/com/yunjihai/rag/infrastructure/minio apps/core-api/src/test/java/com/yunjihai/rag/infrastructure/postgres/JdbcBlobCleanupIntegrationTest.java
git commit -m "feat: connect immutable MinIO blob storage"
```

### Task 8: Spring 装配、真实纵向验收与运行文档

**Files:**
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/infrastructure/postgres/PostgresAdapterConfiguration.java`
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/infrastructure/minio/MinioAdapterConfiguration.java`
- Create: `apps/core-api/src/test/java/com/yunjihai/rag/acceptance/PostgresFoundationAcceptanceTest.java`
- Create: `apps/core-api/src/test/java/com/yunjihai/rag/acceptance/DependencyHealthIntegrationTest.java`
- Modify: `apps/core-api/src/test/java/com/yunjihai/rag/ArchitectureSmokeTest.java`
- Modify: `scripts/verify.ps1`
- Modify: `docs/runbooks/knowledge-foundation.md`
- Modify: `README.md`

**Interfaces:**
- Consumes: Tasks 1–7 adapters and existing domain services.
- Produces: local-profile production bean graph and a repeatable real-infrastructure acceptance gate.

- [ ] **Step 1: Write the failing Spring and vertical acceptance tests**

`ArchitectureSmokeTest` must load the ordinary test profile without external infrastructure. `PostgresFoundationAcceptanceTest` must start PostgreSQL and MinIO containers, load the local adapter graph, and execute: complete organization sync → space and ACL creation → Blob upload and document draft → ingestion claim and synthetic valid parser response → `IN_REVIEW` → publish → authorized read → denied read hidden as not found → audit verification. Assert database and MinIO state, not only returned objects. `DependencyHealthIntegrationTest` must prove healthy dependencies make readiness `UP`, either unavailable dependency makes readiness `DOWN`, and liveness remains `UP` in both outage cases.

- [ ] **Step 2: Run acceptance and verify RED**

Run:

```powershell
.\mvnw.cmd -q -pl apps/core-api '-Dtest=ArchitectureSmokeTest,PostgresFoundationAcceptanceTest' test
```

Expected: FAIL because the production adapter configuration is not wired.

- [ ] **Step 3: Implement profile-scoped wiring and documentation**

Create configuration properties and beans under the `local` profile; keep ordinary unit tests infrastructure-free. Add datasource/Flyway/MinIO readiness groups and fixed error translation. Update `scripts/verify.ps1` with an explicit Docker capability check before infrastructure tests, and update README/runbook with start, stop, health, migration, lease recovery, cleanup-candidate inspection and credential rules.

- [ ] **Step 4: Run focused acceptance GREEN**

Run the Task 8 test command. Expected: PASS.

- [ ] **Step 5: Run complete verification**

Run:

```powershell
$env:JAVA_HOME='C:\Program Files\Zulu\zulu-21'
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/verify.ps1
corepack pnpm --dir apps/web exec playwright test
docker compose -f infra/compose/compose.yaml config --quiet
docker compose --env-file infra/compose/.env.example -p elite-rag-verify -f infra/compose/compose.yaml up -d --wait
docker compose --env-file infra/compose/.env.example -p elite-rag-verify -f infra/compose/compose.yaml down --volumes
git diff --check
git tag --list pilot-foundation-v1 pilot-retrieval-v1 pilot-workbench-v1
```

Expected: Java, Python, Vue and Playwright suites pass; Compose validates and every dependency becomes healthy before the isolated `elite-rag-verify` stack is removed; `git diff --check` is silent; forbidden tag output is empty. Wrap the Compose startup/check in PowerShell `try/finally` so `down --volumes` runs even when a health check fails.

- [ ] **Step 6: Commit**

```powershell
git add apps/core-api/src/main/java/com/yunjihai/rag/infrastructure apps/core-api/src/test/java/com/yunjihai/rag/acceptance apps/core-api/src/test/java/com/yunjihai/rag/ArchitectureSmokeTest.java scripts/verify.ps1 docs/runbooks/knowledge-foundation.md README.md
git commit -m "test: accept persistent knowledge foundation"
```

## Final Review Gate

- [ ] Run an independent whole-branch review against `docs/superpowers/specs/2026-09-29-foundation-data-infrastructure-design.md`.
- [ ] Fix only review findings that protect an approved requirement, data integrity, authorization, transaction atomicity, recovery, or maintainability; record declined suggestions with rationale.
- [ ] Re-run the complete Task 8 verification after the final fix commit.
- [ ] Confirm the branch contains no real credentials, customer content, forbidden tags, uncommitted files or generated container volumes.
