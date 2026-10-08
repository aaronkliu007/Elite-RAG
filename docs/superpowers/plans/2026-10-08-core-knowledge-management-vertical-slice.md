# 企业知识库核心管理闭环实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 交付“本地登录 → 固定 RBAC → 知识空间授权 → 六类文档上传解析 → 审核发布 → 权限过滤全文检索 → 来源查看与下载”的可运行纵向闭环。

**Architecture:** 保持 Vue + Spring Boot 模块化单体 + Python Parser Worker。PostgreSQL 是身份、权限和业务事实的唯一权威源，AIStor/MinIO 保存不可变原始文件，OpenSearch 保存可重建检索投影；身份、存储、解析和检索均通过端口与适配器隔离。

**Tech Stack:** Java 21、Spring Boot 4.1.1、Spring Security、JdbcClient、Flyway、PostgreSQL 17、AIStor/MinIO、OpenSearch 3、Vue 3.5、Pinia 4、Vue Router 5、Vitest 5、Playwright 1.63、Python 3.12/FastAPI Worker、Docker Compose。

**Spec:** `docs/superpowers/specs/2026-10-08-core-knowledge-management-vertical-slice-design.md`

## Global Constraints

- 固定工作目录为 `E:\git_repo\Elite-RAG`，分支为 `solution-workbench-core`。
- 所有新增文档、界面文字、错误消息和运行说明使用中文。
- 每家公司独立部署一套实例；本批不增加 SaaS 多租户或逐行 `tenant_id`。
- Spring Boot 持有身份、权限、事务、审计和基础设施凭据；Python Worker 保持无状态。
- PostgreSQL 是权威源；OpenSearch 召回结果必须在返回前由 PostgreSQL 批量复核权限。
- 未配置真实 Embedding 时只启用全文检索，禁止生产使用测试向量。
- 密码为 12–128 个字符，BCrypt cost 固定为 12；Session 闲置 30 分钟、绝对有效期 8 小时。
- 同一账号 15 分钟内失败 5 次后锁定 15 分钟；未知账号执行等价耗时密码校验。
- Cookie 必须为 `HttpOnly`、`Secure`、`SameSite=Lax`；修改类请求必须通过 CSRF 校验。
- 关键写操作幂等记录至少保留 24 小时；同键异输入返回冲突。
- 不接入真实敏感资料，不创建试点或生产标签，不在本计划中推送远程。
- 产品代码严格按 TDD 实施；每个任务独立提交。

## Review Focus

- 用户名大小写、全角空格和 Unicode 变体不得创建重复账号；Task 3 的 `rejectsEquivalentUsernames` 固定该行为。
- 并发登录失败不得绕过五次锁定阈值，未知账号不得形成明显快速路径；Task 3 的并发与 dummy hash 测试覆盖。
- 同一幂等键上传不同文件或成员授权不同角色必须返回 409；Task 6 的 `rejectsSameKeyWithDifferentHash` 覆盖。
- 成员权限撤销后，即使 OpenSearch 返回旧候选也不得返回证据；Task 11 的 `filtersStaleCandidateAfterRevocation` 覆盖。
- 重复解析回调、迟到任务和并发发布不得生成两个当前版本或改写较新版本；Task 8、Task 9 的并发测试覆盖。

---

## 文件结构锁定

- `identity/local`：本地凭据、密码策略和认证服务。
- `identity/web`：Session Principal、登录接口和会话失效过滤器。
- `access/role`：固定角色、角色仓储和业务动作授权。
- `api`：通用错误信封、追踪 ID 和幂等执行器。
- `knowledge/web`、`retrieval/web`：知识业务与检索 HTTP DTO/Controller。
- `runtime`：解析与投影后台调度，不把调度器放入领域服务。
- `infrastructure/postgres|minio`：既有端口的生产适配器。
- `apps/web/src/api`：按 session/admin/spaces/documents/search 拆分 API。

不得把所有新接口继续塞入现有 `KnowledgeController.java` 或 `knowledge.ts`；旧兼容入口完成迁移后删除。

### Task 1: 扩展 PostgreSQL 业务模式

**Files:**
- Create: `apps/core-api/src/main/resources/db/migration/V003__core_knowledge_management.sql`
- Modify: `apps/core-api/src/test/java/com/yunjihai/rag/infrastructure/postgres/PostgresMigrationIntegrationTest.java`

**Interfaces:**
- Consumes: V001 的 `app_user`、`knowledge_space`、`document`、`document_version`、`acl_entry`、`audit_event`。
- Produces: `external_identity`、`local_credential`、`login_source_attempt`、`system_role_assignment`、`space_role_assignment`、`idempotency_record`、`document_review`，以及空间描述/修订号、文档分类/标签字段。

- [ ] **Step 1: 写失败的迁移集成测试**

新增 `createsCoreManagementTablesAndConstraints`，断言七张新表、外键、活动角色唯一索引和运行时角色权限；新增 `rejectsInvalidRoleAndDuplicateExternalIdentity`。

```java
assertThat(tableNames()).contains("external_identity", "local_credential",
        "login_source_attempt", "system_role_assignment", "space_role_assignment",
        "idempotency_record", "document_review");
assertThatThrownBy(() -> insertSpaceRole("UNKNOWN")).isInstanceOf(DataAccessException.class);
```

- [ ] **Step 2: 运行迁移测试确认失败**

Run: `.\mvnw.cmd -pl apps/core-api -Dtest=PostgresMigrationIntegrationTest test`

Expected: FAIL，缺少 V003 表或约束。

- [ ] **Step 3: 实现 V003**

身份提供方和主体唯一；本地用户名保存规范化值并唯一；登录来源限速以来源哈希和时间窗为键，不保存原始 IP；系统角色只允许 `SYSTEM_ADMIN`；空间角色只允许 `SPACE_ADMIN|EDITOR|REVIEWER_PUBLISHER|READER`；活动空间角色唯一；幂等唯一键为 `(actor_id, operation, idempotency_key)`；审核决定只允许 `REJECTED|PUBLISHED`；标签使用 `text[] NOT NULL DEFAULT '{}'`；显式授予 runtime role 最小权限。

- [ ] **Step 4: 运行测试确认通过**

Run: `.\mvnw.cmd -pl apps/core-api -Dtest=PostgresMigrationIntegrationTest test`

Expected: PASS。

- [ ] **Step 5: 提交**

```powershell
git add apps/core-api/src/main/resources/db/migration/V003__core_knowledge_management.sql apps/core-api/src/test/java/com/yunjihai/rag/infrastructure/postgres/PostgresMigrationIntegrationTest.java
git commit -m "feat: add core management schema"
```

### Task 2: 统一内部用户与会话版本主键

**Files:**
- Modify: `apps/core-api/src/main/java/com/yunjihai/rag/identity/AuthenticatedSession.java`
- Modify: `apps/core-api/src/main/java/com/yunjihai/rag/identity/SessionVersionStore.java`
- Modify: `apps/core-api/src/main/java/com/yunjihai/rag/identity/SessionRevocationService.java`
- Modify: `apps/core-api/src/main/java/com/yunjihai/rag/identity/OrganizationReconciliationPort.java`
- Modify: `apps/core-api/src/main/java/com/yunjihai/rag/identity/OrganizationSyncService.java`
- Modify: `apps/core-api/src/main/java/com/yunjihai/rag/infrastructure/postgres/JdbcSessionVersionStore.java`
- Modify: `apps/core-api/src/main/java/com/yunjihai/rag/infrastructure/postgres/JdbcOrganizationReconciliationAdapter.java`
- Test: `apps/core-api/src/test/java/com/yunjihai/rag/identity/DisabledUserSessionTest.java`
- Test: `apps/core-api/src/test/java/com/yunjihai/rag/infrastructure/postgres/JdbcOrganizationIntegrationTest.java`

**Interfaces:**
- Produces: `AuthenticatedSession(UUID userId, long version)`；`SessionVersionStore` 的四个方法全部以 `UUID userId` 为参数；组织对账返回 `Set<UUID>`。

- [ ] **Step 1: 把身份与会话测试改成 UUID 语义**

```java
var session = sessions.issue(userId);
sessions.revokeAll(userId);
assertThatThrownBy(() -> sessions.assertActive(session))
        .isInstanceOf(DisabledUserSessionException.class);
```

- [ ] **Step 2: 运行目标测试确认编译失败**

Run: `.\mvnw.cmd -pl apps/core-api -Dtest=DisabledUserSessionTest,JdbcOrganizationIntegrationTest test`

Expected: FAIL，现有接口仍接收外部字符串。

- [ ] **Step 3: 修改领域接口、组织对账和 JDBC 查询**

所有 Session SQL 使用 `WHERE id = :userId`；组织对账适配器在事务内返回被停用用户 UUID，不再把外部身份作为 Session 键。

- [ ] **Step 4: 运行身份测试**

Run: `.\mvnw.cmd -pl apps/core-api -Dtest=DisabledUserSessionTest,OrganizationSyncServiceTest,JdbcOrganizationIntegrationTest test`

Expected: PASS。

- [ ] **Step 5: 提交**

```powershell
git add apps/core-api/src/main/java/com/yunjihai/rag/identity apps/core-api/src/main/java/com/yunjihai/rag/infrastructure/postgres/JdbcSessionVersionStore.java apps/core-api/src/main/java/com/yunjihai/rag/infrastructure/postgres/JdbcOrganizationReconciliationAdapter.java apps/core-api/src/test/java/com/yunjihai/rag/identity apps/core-api/src/test/java/com/yunjihai/rag/infrastructure/postgres/JdbcOrganizationIntegrationTest.java
git commit -m "refactor: key sessions by internal user id"
```

### Task 3: 实现本地账号、密码和锁定策略

**Files:**
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/identity/local/UserAccount.java`
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/identity/local/LocalCredential.java`
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/identity/local/UserAccountStore.java`
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/identity/local/LocalCredentialStore.java`
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/identity/local/PasswordHasher.java`
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/identity/local/LocalAuthenticationService.java`
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/identity/local/LoginSourceRateLimiter.java`
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/identity/local/UserAdministrationService.java`
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/identity/local/BCryptPasswordHasher.java`
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/infrastructure/postgres/JdbcUserAccountStore.java`
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/infrastructure/postgres/JdbcLocalCredentialStore.java`
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/infrastructure/postgres/JdbcLoginSourceRateLimiter.java`
- Modify: `apps/core-api/src/main/java/com/yunjihai/rag/audit/AuditAction.java`
- Test: `apps/core-api/src/test/java/com/yunjihai/rag/identity/local/LocalAuthenticationServiceTest.java`
- Test: `apps/core-api/src/test/java/com/yunjihai/rag/identity/local/UserAdministrationServiceTest.java`
- Test: `apps/core-api/src/test/java/com/yunjihai/rag/infrastructure/postgres/JdbcLocalIdentityIntegrationTest.java`

**Interfaces:**
- Produces: `AuthenticationResult authenticate(String username, char[] password, String sourceIp)`；账号不存在也经过同一来源限速和 dummy hash 路径。
- Produces: `UserAccount createUser(String username, String displayName, char[] initialPassword, UUID actorId)`、`setEnabled`、`resetPassword`、`listUsers`。
- Produces: `PasswordHasher.hash(char[])`、`PasswordHasher.matches(char[], String)`；BCrypt cost 12。

- [ ] **Step 1: 写密码、规范化和锁定失败测试**

覆盖 `rejectsEquivalentUsernames`、密码 12–128、15 分钟五次失败锁定、并发失败不能绕过阈值、未知用户使用 dummy hash、同一来源 15 分钟 20 次失败后限速 15 分钟。用户名规范化为 strip + Unicode NFKC + 小写，展示名保留原文；来源仅存储带部署密钥的 HMAC-SHA-256，不持久化原始 IP。

- [ ] **Step 2: 运行测试确认失败**

Run: `.\mvnw.cmd -pl apps/core-api -Dtest=LocalAuthenticationServiceTest,UserAdministrationServiceTest test`

Expected: FAIL，类型不存在。

- [ ] **Step 3: 实现领域服务、BCrypt 和 JDBC 仓储**

认证使用注入 `Clock`；`LocalCredentialStore.lockForAuthentication(String normalizedUsername)` 以数据库行锁返回快照；账号失败窗口、来源限速和锁定同事务更新，成功后清理对应失败窗口；`char[]` 使用后清零。重复用户名翻译为稳定冲突异常；启停和密码重置调用 Task 2 会话撤销。

- [ ] **Step 4: 运行单元与集成测试**

Run: `.\mvnw.cmd -pl apps/core-api -Dtest=LocalAuthenticationServiceTest,UserAdministrationServiceTest,JdbcLocalIdentityIntegrationTest test`

Expected: PASS。

- [ ] **Step 5: 提交**

```powershell
git add apps/core-api/src/main/java/com/yunjihai/rag/identity/local apps/core-api/src/main/java/com/yunjihai/rag/infrastructure/postgres/JdbcUserAccountStore.java apps/core-api/src/main/java/com/yunjihai/rag/infrastructure/postgres/JdbcLocalCredentialStore.java apps/core-api/src/main/java/com/yunjihai/rag/infrastructure/postgres/JdbcLoginSourceRateLimiter.java apps/core-api/src/main/java/com/yunjihai/rag/audit/AuditAction.java apps/core-api/src/test/java/com/yunjihai/rag/identity/local apps/core-api/src/test/java/com/yunjihai/rag/infrastructure/postgres/JdbcLocalIdentityIntegrationTest.java
git commit -m "feat: add secure local accounts"
```

### Task 4: 装配 Spring Security 服务端 Session

**Files:**
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/identity/web/SessionPrincipal.java`
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/identity/web/SessionController.java`
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/identity/web/SessionVersionFilter.java`
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/identity/web/SecurityConfiguration.java`
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/identity/web/SessionProperties.java`
- Modify: `apps/core-api/src/main/resources/application.yml`
- Modify: `apps/core-api/src/main/resources/application-local.yml`
- Test: `apps/core-api/src/test/java/com/yunjihai/rag/identity/web/SessionControllerTest.java`
- Test: `apps/core-api/src/test/java/com/yunjihai/rag/identity/web/SessionSecurityIntegrationTest.java`

**Interfaces:**
- Consumes: Task 3 `LocalAuthenticationService`、Task 2 `SessionRevocationService`。
- Produces: `POST /api/v1/session/login|logout`、`GET /api/v1/session/me|csrf`；Principal 名称为内部用户 UUID。

- [ ] **Step 1: 写未认证、CSRF、Cookie 和超时测试**

断言登录轮换 Session ID；Cookie 包含 `HttpOnly; Secure; SameSite=Lax`；无 CSRF 写请求为 403；闲置 30 分钟、绝对 8 小时；停用用户和旧 version 下一请求为 401。

- [ ] **Step 2: 运行测试确认失败**

Run: `.\mvnw.cmd -pl apps/core-api -Dtest=SessionControllerTest,SessionSecurityIntegrationTest test`

Expected: FAIL。

- [ ] **Step 3: 实现安全链、会话接口和过滤器**

仅登录、CSRF、健康检查匿名；其余 `/api/**` 认证。使用 `HttpSessionSecurityContextRepository`；过滤器校验 enabled/version/绝对期限并清理失效 Session。配置 `server.servlet.session.timeout=30m` 和安全 Cookie。

- [ ] **Step 4: 运行测试**

Run: `.\mvnw.cmd -pl apps/core-api -Dtest=SessionControllerTest,SessionSecurityIntegrationTest,ArchitectureSmokeTest test`

Expected: PASS。

- [ ] **Step 5: 提交**

```powershell
git add apps/core-api/src/main/java/com/yunjihai/rag/identity/web apps/core-api/src/main/resources/application.yml apps/core-api/src/main/resources/application-local.yml apps/core-api/src/test/java/com/yunjihai/rag/identity/web
git commit -m "feat: add server-side web sessions"
```

### Task 5: 实现固定 RBAC、空间成员和管理员引导

**Files:**
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/access/role/SystemRole.java`
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/access/role/SpaceRole.java`
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/access/role/RoleAssignmentStore.java`
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/access/role/RoleAssignmentService.java`
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/access/role/RbacKnowledgeAuthorization.java`
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/identity/local/BootstrapAdministrator.java`
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/identity/local/BootstrapAdminProperties.java`
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/infrastructure/postgres/JdbcRoleAssignmentStore.java`
- Modify: `apps/core-api/src/main/java/com/yunjihai/rag/infrastructure/postgres/PostgresAdapterConfiguration.java`
- Test: `apps/core-api/src/test/java/com/yunjihai/rag/access/role/RbacKnowledgeAuthorizationTest.java`
- Test: `apps/core-api/src/test/java/com/yunjihai/rag/infrastructure/postgres/JdbcRoleAssignmentIntegrationTest.java`
- Test: `apps/core-api/src/test/java/com/yunjihai/rag/identity/local/BootstrapAdministratorTest.java`

**Interfaces:**
- Produces: `hasSystemRole`、`activeSpaceRoles`、`grantSpaceRole`、`revokeSpaceRole`。
- Produces: `RbacKnowledgeAuthorization implements KnowledgeWriteAuthorization`，替换生产 `denyAll()`。

- [ ] **Step 1: 写完整动作矩阵和 ACL 同步测试**

系统管理员允许全部；空间管理员允许空间内全部；编辑者允许创建/编辑/提交审核；审核发布者允许审核/发布；只读无写动作；创建空间仅系统管理员。授予首个空间角色创建 USER SPACE ACL，撤销最后角色才撤销 ACL；角色、ACL、审计、投影同事务。

- [ ] **Step 2: 运行测试确认失败**

Run: `.\mvnw.cmd -pl apps/core-api -Dtest=RbacKnowledgeAuthorizationTest,JdbcRoleAssignmentIntegrationTest,BootstrapAdministratorTest test`

Expected: FAIL。

- [ ] **Step 3: 实现角色服务、JDBC 和引导逻辑**

引导变量为 `RAG_BOOTSTRAP_ADMIN_USERNAME|PASSWORD|DISPLAY_NAME`；仅无系统管理员时创建；已有管理员时不得覆盖；缺少管理员且配置不完整时 local profile 启动失败。

- [ ] **Step 4: 装配并运行测试**

Run: `.\mvnw.cmd -pl apps/core-api -Dtest=RbacKnowledgeAuthorizationTest,JdbcRoleAssignmentIntegrationTest,BootstrapAdministratorTest,KnowledgeAuthorizationTest test`

Expected: PASS，生产配置不再注册 `denyAll()`。

- [ ] **Step 5: 提交**

```powershell
git add apps/core-api/src/main/java/com/yunjihai/rag/access/role apps/core-api/src/main/java/com/yunjihai/rag/identity/local/Bootstrap* apps/core-api/src/main/java/com/yunjihai/rag/infrastructure/postgres/JdbcRoleAssignmentStore.java apps/core-api/src/main/java/com/yunjihai/rag/infrastructure/postgres/PostgresAdapterConfiguration.java apps/core-api/src/test/java/com/yunjihai/rag/access/role apps/core-api/src/test/java/com/yunjihai/rag/identity/local/BootstrapAdministratorTest.java
git commit -m "feat: enforce fixed knowledge roles"
```

### Task 6: 建立统一 API 错误、追踪与幂等基础

**Files:**
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/api/ApiError.java`
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/api/ApiExceptionHandler.java`
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/api/RequestTraceFilter.java`
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/api/idempotency/IdempotencyScope.java`
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/api/idempotency/StoredHttpResult.java`
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/api/idempotency/IdempotencyStore.java`
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/api/idempotency/IdempotencyService.java`
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/infrastructure/postgres/JdbcIdempotencyStore.java`
- Test: `apps/core-api/src/test/java/com/yunjihai/rag/api/ApiExceptionHandlerTest.java`
- Test: `apps/core-api/src/test/java/com/yunjihai/rag/api/idempotency/IdempotencyServiceTest.java`
- Test: `apps/core-api/src/test/java/com/yunjihai/rag/infrastructure/postgres/JdbcIdempotencyIntegrationTest.java`

**Interfaces:**
- Produces: `ApiError(String code, String message, String traceId, Map<String,String> fieldErrors)`。
- Produces: `<T> T execute(IdempotencyScope scope, String requestHash, Class<T> resultType, Supplier<T> action)`。

- [ ] **Step 1: 写错误映射与幂等失败测试**

断言 400/401/403/统一 404/409/503；响应不含 SQL、堆栈、内部主机。覆盖结果重放、执行中重复、`rejectsSameKeyWithDifferentHash`、至少 24 小时保留和并发单执行者。

- [ ] **Step 2: 运行测试确认失败**

Run: `.\mvnw.cmd -pl apps/core-api -Dtest=ApiExceptionHandlerTest,IdempotencyServiceTest,JdbcIdempotencyIntegrationTest test`

Expected: FAIL。

- [ ] **Step 3: 实现通用 API 和幂等组件**

成功响应用 Jackson 保存；失败不缓存。`Idempotency-Key` 为 1–128 可打印 ASCII；请求哈希 SHA-256；合法 `X-Request-Id` UUID 可沿用，否则生成 UUIDv7。

- [ ] **Step 4: 运行测试**

Run: `.\mvnw.cmd -pl apps/core-api -Dtest=ApiExceptionHandlerTest,IdempotencyServiceTest,JdbcIdempotencyIntegrationTest,KnowledgeAuthorizationTest test`

Expected: PASS。

- [ ] **Step 5: 提交**

```powershell
git add apps/core-api/src/main/java/com/yunjihai/rag/api apps/core-api/src/main/java/com/yunjihai/rag/infrastructure/postgres/JdbcIdempotencyStore.java apps/core-api/src/test/java/com/yunjihai/rag/api apps/core-api/src/test/java/com/yunjihai/rag/infrastructure/postgres/JdbcIdempotencyIntegrationTest.java
git commit -m "feat: add safe API error and idempotency handling"
```

### Task 7: 交付用户、空间与成员管理 API

**Files:**
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/identity/web/AdminUserController.java`
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/knowledge/web/KnowledgeSpaceController.java`
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/knowledge/KnowledgeSpaceView.java`
- Modify: `apps/core-api/src/main/java/com/yunjihai/rag/knowledge/KnowledgeSpace.java`
- Modify: `apps/core-api/src/main/java/com/yunjihai/rag/knowledge/KnowledgeSpaceRepository.java`
- Modify: `apps/core-api/src/main/java/com/yunjihai/rag/knowledge/KnowledgeSpaceService.java`
- Modify: `apps/core-api/src/main/java/com/yunjihai/rag/infrastructure/postgres/JdbcKnowledgeSpaceRepository.java`
- Test: `apps/core-api/src/test/java/com/yunjihai/rag/identity/web/AdminUserControllerTest.java`
- Test: `apps/core-api/src/test/java/com/yunjihai/rag/knowledge/web/KnowledgeSpaceControllerTest.java`
- Test: `apps/core-api/src/test/java/com/yunjihai/rag/infrastructure/postgres/JdbcKnowledgeSpaceRepositoryIntegrationTest.java`

**Interfaces:**
- Produces: `/api/v1/admin/users` 列表/创建/启停/重置密码；`/api/v1/knowledge-spaces` 列表/创建；`/{spaceId}/members` 列表/授权/撤销。

- [ ] **Step 1: 写 Controller 权限与 DTO 测试**

系统管理员可管理用户和创建空间；空间管理员只能管理本空间成员；其他角色不能管理成员。列表只返回可见空间和 `allowedActions`。

- [ ] **Step 2: 运行测试确认失败**

Run: `.\mvnw.cmd -pl apps/core-api -Dtest=AdminUserControllerTest,KnowledgeSpaceControllerTest test`

Expected: FAIL。

- [ ] **Step 3: 扩展空间模型、JDBC 和 API**

`KnowledgeSpace` 增加 `description/revision`；仓储增加 `findById`、`listAuthorized`、`replace`；SQL 直接按有效角色/ACL 过滤。创建用户/空间和角色变更使用幂等服务；分页 1–100；密码永不进入响应。

- [ ] **Step 4: 运行测试**

Run: `.\mvnw.cmd -pl apps/core-api -Dtest=AdminUserControllerTest,KnowledgeSpaceControllerTest,JdbcKnowledgeSpaceRepositoryIntegrationTest test`

Expected: PASS。

- [ ] **Step 5: 提交**

```powershell
git add apps/core-api/src/main/java/com/yunjihai/rag/identity/web/AdminUserController.java apps/core-api/src/main/java/com/yunjihai/rag/knowledge apps/core-api/src/main/java/com/yunjihai/rag/infrastructure/postgres/JdbcKnowledgeSpaceRepository.java apps/core-api/src/test/java/com/yunjihai/rag/identity/web/AdminUserControllerTest.java apps/core-api/src/test/java/com/yunjihai/rag/knowledge/web apps/core-api/src/test/java/com/yunjihai/rag/infrastructure/postgres/JdbcKnowledgeSpaceRepositoryIntegrationTest.java
git commit -m "feat: add user and knowledge space APIs"
```

### Task 8: 补齐文档元数据、驳回与发布并发语义

**Files:**
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/knowledge/DocumentMetadata.java`
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/knowledge/ReviewDecision.java`
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/knowledge/DocumentReview.java`
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/knowledge/DocumentReviewStore.java`
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/infrastructure/postgres/JdbcDocumentReviewStore.java`
- Modify: `apps/core-api/src/main/java/com/yunjihai/rag/knowledge/DocumentAggregate.java`
- Modify: `apps/core-api/src/main/java/com/yunjihai/rag/knowledge/DocumentService.java`
- Modify: `apps/core-api/src/main/java/com/yunjihai/rag/knowledge/DocumentRepository.java`
- Modify: `apps/core-api/src/main/java/com/yunjihai/rag/infrastructure/postgres/JdbcDocumentRepository.java`
- Test: `apps/core-api/src/test/java/com/yunjihai/rag/knowledge/DocumentLifecycleTest.java`
- Test: `apps/core-api/src/test/java/com/yunjihai/rag/infrastructure/postgres/DocumentTransactionIntegrationTest.java`

**Interfaces:**
- Produces: `DocumentMetadata(String classification, List<String> tags)`、`DocumentAggregate.reject(UUID versionId, UUID reviewerId, Instant at)`、`DocumentService.reject(..., String comment, ...)`。
- Changes: `completeParsing` 只把 `parseStatus` 置为 `SUCCEEDED` 并保持 `DRAFT`；编辑者显式 `submitForReview`。

- [ ] **Step 1: 写生命周期失败测试**

解析成功后仍为 `DRAFT`；元数据不完整不能提交；驳回必须有 1–1000 字意见并回到 `DRAFT`；编辑者不能覆盖审核记录；并发发布只允许一个事务切换当前版本。

- [ ] **Step 2: 运行测试确认现有行为失败**

Run: `.\mvnw.cmd -pl apps/core-api -Dtest=DocumentLifecycleTest,DocumentTransactionIntegrationTest test`

Expected: FAIL，当前解析自动进入 `IN_REVIEW` 且无驳回记录。

- [ ] **Step 3: 修改领域聚合和仓储**

分类 1–100 字；标签去重排序、单项 1–50 字、最多 20 项。审核记录不可变。发布与审核记录、审计、投影 Outbox 同事务提交。

- [ ] **Step 4: 添加迟到任务与并发回归测试**

覆盖旧版本解析完成、重复回调、归档后回调、两个审核者同时发布；不得改写较新版本或产生两个当前指针。

- [ ] **Step 5: 运行测试**

Run: `.\mvnw.cmd -pl apps/core-api -Dtest=DocumentLifecycleTest,DocumentTransactionIntegrationTest,IngestionCompletionTransactionIntegrationTest test`

Expected: PASS。

- [ ] **Step 6: 提交**

```powershell
git add apps/core-api/src/main/java/com/yunjihai/rag/knowledge apps/core-api/src/main/java/com/yunjihai/rag/infrastructure/postgres/JdbcDocumentRepository.java apps/core-api/src/main/java/com/yunjihai/rag/infrastructure/postgres/JdbcDocumentReviewStore.java apps/core-api/src/test/java/com/yunjihai/rag/knowledge/DocumentLifecycleTest.java apps/core-api/src/test/java/com/yunjihai/rag/infrastructure/postgres/DocumentTransactionIntegrationTest.java apps/core-api/src/test/java/com/yunjihai/rag/infrastructure/postgres/IngestionCompletionTransactionIntegrationTest.java
git commit -m "feat: complete reviewable document lifecycle"
```

### Task 9: 装配上传、内容读取与后台 Worker

**Files:**
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/infrastructure/minio/MinioBlobReader.java`
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/infrastructure/postgres/JdbcDocumentContentGateway.java`
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/ingestion/MinioParserInvocation.java`
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/runtime/IngestionWorkerScheduler.java`
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/runtime/ProjectionWorkerScheduler.java`
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/runtime/WorkerRuntimeConfiguration.java`
- Modify: `apps/core-api/src/main/java/com/yunjihai/rag/ingestion/UploadService.java`
- Modify: `apps/core-api/src/main/java/com/yunjihai/rag/infrastructure/minio/MinioAdapterConfiguration.java`
- Modify: `apps/core-api/src/main/java/com/yunjihai/rag/infrastructure/postgres/PostgresAdapterConfiguration.java`
- Modify: `apps/core-api/src/main/resources/application-local.yml`
- Test: `apps/core-api/src/test/java/com/yunjihai/rag/ingestion/UploadServiceTest.java`
- Test: `apps/core-api/src/test/java/com/yunjihai/rag/ingestion/MinioParserInvocationTest.java`
- Test: `apps/core-api/src/test/java/com/yunjihai/rag/runtime/WorkerSchedulersTest.java`
- Test: `apps/core-api/src/test/java/com/yunjihai/rag/infrastructure/postgres/JdbcDocumentContentGatewayIntegrationTest.java`

**Interfaces:**
- Produces: `byte[] MinioBlobReader.read(String objectKey, long maxBytes)`、`MinioParserInvocation implements ParserInvocation`；Scheduler 的 `int drainAvailable()`。
- Changes: `UploadService.accept(UploadCommand, String idempotencyKey)` 在创建草稿的同一数据库事务内创建 ingestion job。

- [ ] **Step 1: 写上传原子性和内容读取测试**

上传成功同时存在版本和 ingestion job；数据库失败登记清理候选；重复键同文件返回同一版本；同键异文件冲突；源文件校验 100 MiB 与规范对象键；解析预览按 Canonical Node 稳定顺序生成 UTF-8。

- [ ] **Step 2: 写 Worker 调度测试**

同一周期只排一个执行；无任务快速返回；异常不停止后续周期；迟到租约不能完成；投影目标来自配置而不是客户端输入。

- [ ] **Step 3: 运行测试确认失败**

Run: `.\mvnw.cmd -pl apps/core-api -Dtest=UploadServiceTest,MinioParserInvocationTest,WorkerSchedulersTest,JdbcDocumentContentGatewayIntegrationTest test`

Expected: FAIL。

- [ ] **Step 4: 实现 MinIO 读取、ParserInvocation、内容网关和调度器**

下载到受控临时文件后调用现有 `ParserClient`，`finally` 删除；不得把 MinIO URL/凭据传给 Python。配置 `rag.ingestion.worker.*`、`rag.opensearch.worker.poll-delay`、`rag.parser.endpoint`；单线程加互斥标志防同实例重入。

- [ ] **Step 5: 运行测试**

Run: `.\mvnw.cmd -pl apps/core-api -Dtest=UploadServiceTest,MinioParserInvocationTest,WorkerSchedulersTest,JdbcDocumentContentGatewayIntegrationTest,ProjectionWorkerIntegrationTest test`

Expected: PASS。

- [ ] **Step 6: 提交**

```powershell
git add apps/core-api/src/main/java/com/yunjihai/rag/infrastructure/minio apps/core-api/src/main/java/com/yunjihai/rag/infrastructure/postgres/JdbcDocumentContentGateway.java apps/core-api/src/main/java/com/yunjihai/rag/ingestion apps/core-api/src/main/java/com/yunjihai/rag/runtime apps/core-api/src/main/java/com/yunjihai/rag/infrastructure/postgres/PostgresAdapterConfiguration.java apps/core-api/src/main/resources/application-local.yml apps/core-api/src/test/java/com/yunjihai/rag/ingestion apps/core-api/src/test/java/com/yunjihai/rag/runtime apps/core-api/src/test/java/com/yunjihai/rag/infrastructure/postgres/JdbcDocumentContentGatewayIntegrationTest.java
git commit -m "feat: run upload parsing and projection workers"
```

### Task 10: 交付文档管理 HTTP API

**Files:**
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/knowledge/web/DocumentController.java`
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/knowledge/web/DocumentDtos.java`
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/knowledge/web/UploadController.java`
- Modify: `apps/core-api/src/main/java/com/yunjihai/rag/knowledge/DocumentView.java`
- Delete: `apps/core-api/src/main/java/com/yunjihai/rag/knowledge/KnowledgeController.java`
- Test: `apps/core-api/src/test/java/com/yunjihai/rag/knowledge/web/DocumentControllerTest.java`
- Test: `apps/core-api/src/test/java/com/yunjihai/rag/knowledge/web/UploadControllerTest.java`

**Interfaces:**
- Produces: `/api/v1/knowledge-spaces/{spaceId}/documents` 列表/上传；`/api/v1/documents/{documentId}` 详情/预览/下载/归档；`/versions/{versionId}/submit-review|reject|publish`。

- [ ] **Step 1: 写上传、权限和 DTO 失败测试**

覆盖六种扩展名、空文件、伪造 MIME、加密、超过 100 MiB、缺幂等键；编辑者不能发布，审核者不能管理用户，只读不能上传；未授权与不存在同 404；只读详情不暴露 ACL/草稿。

- [ ] **Step 2: 运行测试确认失败**

Run: `.\mvnw.cmd -pl apps/core-api -Dtest=DocumentControllerTest,UploadControllerTest test`

Expected: FAIL。

- [ ] **Step 3: 实现 Controller/DTO 并删除旧 Controller**

上传流式交给 Task 9；元数据修改、提交审核、驳回、发布和归档全部要求幂等键，同键异输入返回 409；下载使用安全 `Content-Disposition`；DTO 不返回 source blob key、内部异常或 ACL 明细；管理详情返回历史版本、审核意见、解析错误码和 `allowedActions`。

- [ ] **Step 4: 运行测试**

Run: `.\mvnw.cmd -pl apps/core-api -Dtest=DocumentControllerTest,UploadControllerTest,KnowledgeAuthorizationTest test`

Expected: PASS。

- [ ] **Step 5: 提交**

```powershell
git add apps/core-api/src/main/java/com/yunjihai/rag/knowledge apps/core-api/src/test/java/com/yunjihai/rag/knowledge/web
git commit -m "feat: expose secured document management APIs"
```

### Task 11: 交付全文检索与来源定位 API

**Files:**
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/retrieval/web/SearchController.java`
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/retrieval/web/SearchRequest.java`
- Create: `apps/core-api/src/main/java/com/yunjihai/rag/retrieval/web/SearchResponse.java`
- Modify: `apps/core-api/src/main/java/com/yunjihai/rag/infrastructure/opensearch/OpenSearchAdapterConfiguration.java`
- Test: `apps/core-api/src/test/java/com/yunjihai/rag/retrieval/web/SearchControllerTest.java`
- Test: `apps/core-api/src/test/java/com/yunjihai/rag/acceptance/PostgresOpenSearchRetrievalAcceptanceTest.java`

**Interfaces:**
- Produces: `POST /api/v1/search`；输入 `spaceId/queryText/metadataFilter/requestedEvidenceCount`，用户 ID 和 request ID 由服务端确定。
- Produces: evidence 的文档/版本/节点/标题/摘要/分数/locator，以及降级原因；不返回向量和 ACL。

- [ ] **Step 1: 写查询验证、降级与撤权测试**

空查询、超过 2000 code points、证据数不在 8–15、无权空间失败；向量关闭返回全文结果和 `VECTOR_UNAVAILABLE`；OpenSearch 全不可用为 503。`filtersStaleCandidateAfterRevocation` 撤销 PostgreSQL ACL 后不等待索引刷新，响应不得含旧 evidence；授权库异常整体 503。

- [ ] **Step 2: 运行测试确认失败**

Run: `.\mvnw.cmd -pl apps/core-api -Dtest=SearchControllerTest,PostgresOpenSearchRetrievalAcceptanceTest test`

Expected: FAIL。

- [ ] **Step 3: 实现 DTO/Controller 和全文装配**

构造 `RetrievalQuery(UuidV7.generate(now), principal.userId(), ...)`；审计只记录查询哈希、数量、耗时和降级。确认 vector disabled 时不调用 Embedding。

- [ ] **Step 4: 运行测试**

Run: `.\mvnw.cmd -pl apps/core-api -Dtest=SearchControllerTest,PostgresOpenSearchRetrievalAcceptanceTest,RetrievalProductionWiringTest test`

Expected: PASS。

- [ ] **Step 5: 提交**

```powershell
git add apps/core-api/src/main/java/com/yunjihai/rag/retrieval/web apps/core-api/src/main/java/com/yunjihai/rag/infrastructure/opensearch/OpenSearchAdapterConfiguration.java apps/core-api/src/test/java/com/yunjihai/rag/retrieval/web apps/core-api/src/test/java/com/yunjihai/rag/acceptance/PostgresOpenSearchRetrievalAcceptanceTest.java
git commit -m "feat: expose permission-filtered search API"
```

### Task 12: 建立前端认证、通用 HTTP 与路由保护

**Files:**
- Create: `apps/web/src/api/http.ts`
- Create: `apps/web/src/api/session.ts`
- Create: `apps/web/src/views/LoginView.vue`
- Modify: `apps/web/src/stores/session.ts`
- Modify: `apps/web/src/router/index.ts`
- Modify: `apps/web/src/App.vue`
- Modify: `apps/web/src/App.test.ts`
- Test: `apps/web/src/views/LoginView.test.ts`
- Test: `apps/web/src/api/http.test.ts`

**Interfaces:**
- Produces: `apiRequest<T>()` 自动发送同源 Cookie、CSRF 和追踪 ID；Session Store 的 `bootstrap/login/logout`；未认证跳转 `/login`。

- [ ] **Step 1: 写登录、CSRF 和 401 测试**

应用先加载 CSRF 与 `/session/me`；登录成功返回原路由；401 清空 Store；403/409/503 显示中文消息与 traceId；不在 localStorage 保存 Session/密码。

- [ ] **Step 2: 运行测试确认失败**

Run: `corepack.cmd pnpm --dir apps/web test --run src/App.test.ts src/views/LoginView.test.ts src/api/http.test.ts`

Expected: FAIL。

- [ ] **Step 3: 实现 HTTP、Store 和路由守卫**

CSRF 只存内存；表单 autocomplete 为 `username/current-password`；提交时防重复；App Header 移除钉钉固定文案，显示真实用户和退出。

- [ ] **Step 4: 运行测试与构建**

Run: `corepack.cmd pnpm --dir apps/web test --run src/App.test.ts src/views/LoginView.test.ts src/api/http.test.ts`

Expected: PASS。

Run: `corepack.cmd pnpm --dir apps/web build`

Expected: PASS。

- [ ] **Step 5: 提交**

```powershell
git add apps/web/src/api apps/web/src/stores/session.ts apps/web/src/router/index.ts apps/web/src/App.vue apps/web/src/App.test.ts apps/web/src/views/LoginView.vue apps/web/src/views/LoginView.test.ts
git commit -m "feat: add authenticated web shell"
```

### Task 13: 交付前端用户、空间与成员管理

**Files:**
- Create: `apps/web/src/api/admin.ts`
- Create: `apps/web/src/api/spaces.ts`
- Create: `apps/web/src/views/UserAdminView.vue`
- Create: `apps/web/src/views/SpaceDetailView.vue`
- Create: `apps/web/src/components/SpaceMemberPanel.vue`
- Modify: `apps/web/src/views/KnowledgeHomeView.vue`
- Modify: `apps/web/src/router/index.ts`
- Test: `apps/web/src/views/UserAdminView.test.ts`
- Test: `apps/web/src/views/SpaceDetailView.test.ts`
- Test: `apps/web/src/components/SpaceMemberPanel.test.ts`

**Interfaces:**
- Produces: 用户创建/启停/重置密码，空间创建/列表，成员角色授予/撤销 UI。

- [ ] **Step 1: 写权限呈现和错误状态测试**

系统管理员看到用户入口；非管理员不可进入；空间管理员看到成员管理；其他角色只读；空数据、加载、失败、冲突刷新和超长名称有明确状态。

- [ ] **Step 2: 运行测试确认失败**

Run: `corepack.cmd pnpm --dir apps/web test --run src/views/UserAdminView.test.ts src/views/SpaceDetailView.test.ts src/components/SpaceMemberPanel.test.ts`

Expected: FAIL。

- [ ] **Step 3: 实现 API 与页面**

每个写请求生成 UUID 幂等键，同一按钮重试沿用原键至终态。角色只用固定中文标签。表格固定列宽和横向容器，不允许页面级横向滚动。

- [ ] **Step 4: 运行测试、构建和 lint**

Run: `corepack.cmd pnpm --dir apps/web test --run src/views/UserAdminView.test.ts src/views/SpaceDetailView.test.ts src/components/SpaceMemberPanel.test.ts`

Expected: PASS。

Run: `corepack.cmd pnpm --dir apps/web build`

Expected: PASS。

Run: `corepack.cmd pnpm --dir apps/web lint`

Expected: PASS。

- [ ] **Step 5: 提交**

```powershell
git add apps/web/src/api/admin.ts apps/web/src/api/spaces.ts apps/web/src/views/UserAdminView.vue apps/web/src/views/UserAdminView.test.ts apps/web/src/views/SpaceDetailView.vue apps/web/src/views/SpaceDetailView.test.ts apps/web/src/components/SpaceMemberPanel.vue apps/web/src/components/SpaceMemberPanel.test.ts apps/web/src/views/KnowledgeHomeView.vue apps/web/src/router/index.ts
git commit -m "feat: add user and space management UI"
```

### Task 14: 交付前端文档闭环与全文检索

**Files:**
- Create: `apps/web/src/api/documents.ts`
- Create: `apps/web/src/api/search.ts`
- Create: `apps/web/src/views/SearchView.vue`
- Create: `apps/web/src/components/SearchResults.vue`
- Modify: `apps/web/src/api/knowledge.ts`
- Modify: `apps/web/src/views/UploadView.vue`
- Modify: `apps/web/src/views/DocumentDetailView.vue`
- Modify: `apps/web/src/components/UploadPanel.vue`
- Modify: `apps/web/src/components/DocumentReviewPanel.vue`
- Modify: `apps/web/src/router/index.ts`
- Test: `apps/web/src/components/UploadPanel.test.ts`
- Test: `apps/web/src/components/DocumentReviewPanel.test.ts`
- Test: `apps/web/src/views/SearchView.test.ts`
- Test: `apps/web/e2e/knowledge-foundation.spec.ts`

**Interfaces:**
- Produces: 真实空间选择上传、解析轮询、提交审核、驳回、发布、预览/下载、全文检索与来源跳转。

- [ ] **Step 1: 写业务流程失败测试**

覆盖六类文件、空间必选、100 MiB 提示、上传幂等重试、解析轮询/失败码、编辑者提交、审核者驳回/发布、只读无写按钮、全文降级提示和来源定位。

- [ ] **Step 2: 运行测试确认失败**

Run: `corepack.cmd pnpm --dir apps/web test --run src/components/UploadPanel.test.ts src/components/DocumentReviewPanel.test.ts src/views/SearchView.test.ts`

Expected: FAIL。

- [ ] **Step 3: 实现页面并拆除旧 mock 结构**

上传不再使用固定 `default` 空间；旧 `knowledge.ts` 迁移后删除冗余接口。轮询可取消，离开页面立即停止。locator 按 PDF 页、PPT 页、表格单元格或 Markdown heading 显示。

- [ ] **Step 4: 扩展 Playwright 场景并全量验证**

覆盖登录、角色导航、上传、解析、审核、发布、撤权后 404、检索来源和 1366/1600px 无页面级横向溢出。

Run: `corepack.cmd pnpm --dir apps/web test --run`

Expected: PASS。

Run: `corepack.cmd pnpm --dir apps/web build`

Expected: PASS。

Run: `corepack.cmd pnpm --dir apps/web lint`

Expected: PASS。

Run: `corepack.cmd pnpm --dir apps/web exec playwright test`

Expected: PASS。

- [ ] **Step 5: 提交**

```powershell
git add apps/web/src apps/web/e2e/knowledge-foundation.spec.ts
git commit -m "feat: complete knowledge management web flow"
```

### Task 15: 完整基础设施验收、运行手册与交付闸门

**Files:**
- Create: `apps/core-api/src/test/java/com/yunjihai/rag/acceptance/CoreKnowledgeManagementAcceptanceTest.java`
- Create: `docs/runbooks/core-knowledge-management.md`
- Modify: `README.md`
- Modify: `scripts/verify.ps1`
- Modify: `infra/compose/compose.yaml`

**Interfaces:**
- Produces: 真实 PostgreSQL + AIStor/MinIO + Parser Worker + OpenSearch 的可重复验收路径和中文运行手册。

- [ ] **Step 1: 写完整验收测试**

引导管理员、创建五类角色用户、创建空间、上传代表文件、解析、提交/驳回/再提交/发布、投影、检索、来源、下载、撤权后立即拒绝；审计不含密码、正文、ACL 明细或密钥。

- [ ] **Step 2: 运行验收测试确认缺失装配**

Run: `.\mvnw.cmd -pl apps/core-api -Dtest=CoreKnowledgeManagementAcceptanceTest test`

Expected: 首次在未补齐 Compose/配置或验收辅助上 FAIL。

- [ ] **Step 3: 补齐 Compose、验证脚本和运行手册**

Compose 只绑定本机开发端口；license 只读挂载 `E:\git_secrets\Elite-RAG\minio.license`；README 标明“受控测试可用、尚未生产准入”。手册包含管理员引导、账号恢复、任务重试、全文降级、索引重建、撤权验证和已知限制。

- [ ] **Step 4: 运行全量验证**

Run: `.\mvnw.cmd clean verify`

Expected: PASS。

Run: `corepack.cmd pnpm test`

Expected: PASS。

Run: `corepack.cmd pnpm build`

Expected: PASS。

Run: `corepack.cmd pnpm lint`

Expected: PASS。

Run: `uv run --project workers/document-parser pytest -W error`

Expected: PASS。

Run: `uv run --project workers/document-parser ruff check workers/document-parser`

Expected: PASS。

Run: `uv run --project workers/document-parser mypy workers/document-parser/src`

Expected: PASS。

Run: `docker compose --env-file infra/compose/.env.example -f infra/compose/compose.yaml config --quiet`

Expected: PASS。

Run: `powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/verify.ps1`

Expected: Java、Vue、Python 与真实基础设施验收全部 PASS。

- [ ] **Step 5: 检查工作树与敏感信息并提交**

Run: `git diff --check`

Expected: 无输出。

Run: `git status --short`

Expected: 只包含计划内文件；`minio.license`、`.env`、密码和密钥未跟踪。

```powershell
git add apps/core-api/src/test/java/com/yunjihai/rag/acceptance/CoreKnowledgeManagementAcceptanceTest.java docs/runbooks/core-knowledge-management.md README.md scripts/verify.ps1 infra/compose/compose.yaml
git commit -m "test: verify core knowledge management flow"
```

## 最终完成检查

- [ ] 生产装配不存在 `denyAll()`、固定身份或不可用内容网关占位。
- [ ] local profile 管理员引导行为明确且安全。
- [ ] 所有关键写 API 要求幂等键并覆盖同键异输入。
- [ ] 所有读取、下载、预览和检索返回前执行当前 PostgreSQL 授权。
- [ ] OpenSearch、向量、解析依赖的降级和失败在 API/UI 可见。
- [ ] 中文 UI 通过 1366px、1600px 和窄屏检查。
- [ ] `scripts/verify.ps1` 全绿，工作树干净。
- [ ] 使用 `superpowers:requesting-code-review` 做整分支审查，Critical/Important 归零。
- [ ] 使用 `superpowers:verification-before-completion` 复跑完成证据后再声明交付。
