# 试点硬化实施计划

> **面向执行者：** 必须使用 `superpowers:subagent-driven-development`（推荐）或 `superpowers:executing-plans` 逐任务执行。

**目标：** 将已验收的方案工作台转化为 10–20 人可控试点，并形成可量化的安全、可靠性、性能、恢复、成本和业务结论。

**架构：** 本阶段不增加新业务场景，只围绕现有边界增加安全过滤、追踪、指标、恢复自动化、负载测试和验收报告。发布门槛自动检查 ACL、检索、生成、备份和业务证据。

**技术栈：** Spring Boot Actuator、Micrometer、OpenTelemetry、Prometheus 兼容指标、结构化 JSON 日志、PostgreSQL/MinIO/OpenSearch 恢复工具、OWASP ZAP、依赖扫描、k6、JUnit、Playwright、Docker/Kubernetes。

**设计规格：** `docs/superpowers/specs/2026-09-24-enterprise-rag-solution-generation-design.md`

## 全局约束

- 仅在 `pilot-workbench-v1` 且前置验收全绿后开始。
- 不得增加方案生成以外的新场景。
- 生产文档不得进入测试、性能和安全环境；使用合成或已批准脱敏数据。
- 安全日志只记主体/资源/结果 ID，不记密钥和完整敏感正文。
- 业务时段可用性基线 99.5%，普通检索 P95 不超过 3 秒。
- PostgreSQL/对象存储 RPO 不超过 24 小时，RTO 不超过 4 小时。
- 任何越权命中都阻止发布。

## 评审重点

- 隐藏文本、零宽字符和文档内指令必须始终是数据，不得覆盖系统策略（任务 1）。
- 模型故障/延迟不得丢确认内容或重复计费（任务 2）。
- 日志、追踪、指标和错误不得含正文、密钥、签名 URL 和 Token（任务 3）。
- PostgreSQL 与 MinIO 恢复点不一致必须先检测/修复，再提供服务（任务 4）。
- 负载激增必须限流保护存储和模型预算（任务 5）。

---

### Task 1｜任务 1：RAG 安全与提示注入防护

**文件：** 创建 `UntrustedContentScanner`、`PromptBoundaryBuilder`、`GeneratedContentSanitizer`、攻击样本、安全测试和应急手册。

- [ ] **第 1 步：添加白底白字、零宽字符、编码指令、“忽略之前指令”、恶意 Markdown 图片、`javascript:`、提示词泄露和知识投毒样本**
- [ ] **第 2 步：标准化 Unicode 但保留原定位；高置信攻击阻断，其余审计**
- [ ] **第 3 步：系统指令、用户输入和证据严格分隔；证据不得变成系统消息或工具定义**
- [ ] **第 4 步：输出仅允许批准格式和合规 HTTPS 链接，删除脚本、事件、嵌入对象、data URL 和本地路径**
- [ ] **第 5 步：验证并提交**

```powershell
.\mvnw.cmd -pl apps/core-api -Dtest=PromptInjectionSecurityTest test
git add apps/core-api/src/main/java/com/yunjihai/rag/security apps/core-api/src/test security docs/runbooks/ai-security-response.md
git commit -m "security: harden untrusted RAG content"
```

### Task 2｜任务 2：容错与安全降级

**文件：** 创建 `DependencyPolicy`、`FaultInjectionTest`、Toxiproxy 配置和外部依赖故障手册。

- [ ] **第 1 步：对解析、PostgreSQL、MinIO、OpenSearch、Embedding、Reranker 和聊天模型注入超时、断连、429、500、非法响应和延迟**
- [ ] **第 2 步：验证解析重试/人工、OpenSearch 不丢事实、Embedding 退全文、Reranker 退融合排名、模型不丢确认内容、PostgreSQL 关闭失败、MinIO 不泄露 URL**
- [ ] **第 3 步：只对幂等传输失败重试，尊重 `Retry-After`/截止时间，限制并发并开启熔断；校验错误不重试**
- [ ] **第 4 步：验证并提交**

```powershell
.\mvnw.cmd -pl apps/core-api -Dtest=FaultInjectionTest test
git add apps/core-api/src/main/java/com/yunjihai/rag/resilience apps/core-api/src/test infra/compose docs/runbooks/dependency-outage.md
git commit -m "feat: add safe dependency degradation"
```

### Task 3｜任务 3：追踪、指标、成本与脱敏日志

**文件：** 创建 `CorrelationIdFilter`、`CostMeter`、`TelemetryRedactionTest`、Prometheus/告警配置和手册。

- [ ] **第 1 步：测试 API Key、签名 URL、客户名、正文、Access Token 和 Prompt 不出现在日志/追踪/错误**
- [ ] **第 2 步：同一 Correlation ID 贯穿 Web→Java→Python/OpenSearch/模型网关**
- [ ] **第 3 步：采集上传/解析/索引、队列、检索/重排/模型延迟、空检索、引用失败、Token/单方案成本、权限拒绝和用户采纳指标**
- [ ] **第 4 步：对积压、解析失败激增、投影延迟、模型错误/成本激增、P95、备份失败和容量阈值告警**
- [ ] **第 5 步：验证并提交**

```powershell
.\mvnw.cmd -pl apps/core-api -Dtest=TelemetryRedactionTest test
git add apps/core-api/src/main/java/com/yunjihai/rag/observability apps/core-api/src/test infra/observability docs/runbooks/observability.md
git commit -m "feat: add redacted end-to-end telemetry"
```

### Task 4｜任务 4：备份、恢复与跨存储对账

**文件：** 创建 `backup.ps1`、`restore.ps1`、`reconcile-stores.ps1`、`StoreReconciliationService`、测试和备份恢复手册。

- [ ] **第 1 步：测试缺对象、孤儿对象、已发布版本缺 Chunk、OpenSearch ACL 过期和恢复时间窗不一致**
- [ ] **第 2 步：备份 PostgreSQL/MinIO，不将 OpenSearch 当权威备份；Manifest 记快照时间、哈希、Schema 版本和对象计数**
- [ ] **第 3 步：恢复到隔离环境，迁移、校验、重建 OpenSearch 后才开放流量**
- [ ] **第 4 步：缺源 Blob 立即阻断文档，孤儿 Blob 隔离，索引重建，ACL 以 PostgreSQL 修复**
- [ ] **第 5 步：做实际计时恢复演练，证明 RPO/RTO，附签字报告并提交**

### Task 5｜任务 5：负载、并发与预算保护

**文件：** 创建 `pilot-load.js`、合成样本、`AdmissionController`、测试和容量手册。

- [ ] **第 1 步：测试每用户生成上限、部门预算、上传大小/并发、队列上限、用户间公平以及超限 429 不建任务/不花费**
- [ ] **第 2 步：模型前预留预算，完成后按实际结算，超时/失败幂等释放；管理员豁免必须限时且审计**
- [ ] **第 3 步：k6 模拟 20 人浏览、20% 上传、搜索、5 个并行章节、编辑、引用和 2 个导出**
- [ ] **第 4 步：门槛为搜索 `p(95)<3000`、除预期 429 外失败率 `<1%`、越权响应 0；只针对实测瓶颈调优**
- [ ] **第 5 步：提交脚本、假设、P50/P95/P99、饱和点和最终限额**

### Task 6｜任务 6：应用安全与供应链门槛

**文件：** 创建 ZAP 规则/上下文、`security-gate.ps1`、安全测试手册，更新 CI。

- [ ] **第 1 步：执行 Maven/Python/pnpm 依赖、密钥、镜像、SAST、已认证 ZAP、ACL 和 RAG 红队测试，固定扫描器版本**
- [ ] **第 2 步：硬门槛：密钥入库、无接受记录的可利用高危漏洞、任何 ACL 失败、XSS/输出注入或提示导致禁止动作/泄露均阻断**
- [ ] **第 3 步：风险接受记录必须有负责人、到期日、补偿控制和 Issue，运行门槛并提交**

```powershell
powershell -ExecutionPolicy Bypass -File scripts/security-gate.ps1
git add security scripts/security-gate.ps1 docs/runbooks/security-testing.md .github/workflows/verify.yml
git commit -m "security: gate pilot releases"
```

### Task 7｜任务 7：试点分析、验收报告与发布决策

**文件：** 创建 `PilotMetricsService`、测试、基线/周复盘/验收模板和 `pilot-acceptance.ps1`。

**产出决策：** `LAUNCH|EXTEND_QUALITY|STOP_REASSESS`。

- [ ] **第 1 步：测试三类决策、无基线、缺失周数据和任何安全事件强制复核**
- [ ] **第 2 步：按周/队列聚合，不存正文；计算耗时降低、周活、流程完成、复用率、引用覆盖/准确、编辑/退回、成本、可用性和延迟**
- [ ] **第 3 步：运行总门槛**

```powershell
.\mvnw.cmd verify
uv run --project workers/document-parser pytest
corepack pnpm --dir apps/web test --run
corepack pnpm --dir apps/web build
corepack pnpm --dir apps/web exec playwright test
powershell -ExecutionPolicy Bypass -File scripts/run-retrieval-eval.ps1
powershell -ExecutionPolicy Bypass -File scripts/security-gate.ps1
powershell -ExecutionPolicy Bypass -File scripts/pilot-acceptance.ps1
```

- [ ] **第 4 步：仅在附上真实证据后提交并打标签**

```powershell
git add apps docs/pilot scripts
git commit -m "docs: record pilot acceptance evidence"
git tag pilot-ready-v1
```
