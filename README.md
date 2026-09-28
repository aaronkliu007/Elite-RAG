# 云基华海企业级 RAG 知识底座

本仓库用于建设公司级知识资产沉淀、复用与治理底座，首期覆盖 Word、Excel、PDF、PPT、Markdown 和 PNG 的安全接入、统一解析、人工审核、授权发布和审计，并为后续检索问答、报表处理、方案设计与评审提供可信数据基础。

## 当前状态

当前版本是“业务能力骨架”，不是可投产版本。

已完成并由自动化测试覆盖：

- 主体展开、默认拒绝 ACL 与越权统一返回 404；
- 钉钉身份适配器契约、组织快照校验和停用用户会话失效；
- 不可变文档版本、解析成功门禁、人工发布和归档；
- 上传隔离、大小与 MIME 校验、PDF/OOXML/Markdown 安全检查、SHA-256 内容寻址接口；
- Java 与 Python 的版本化解析契约、FastAPI/Docling 无状态解析 Worker；
- 可恢复入库租约、指数退避、尝试上限和结构化解析失败；
- 空间/文档 ACL 授权与撤销，以及上传/解析/发布/ACL/查看/下载/归档审计；
- 基础设施中立的可信检索内核：双路召回端口、RRF、重排降级、当前权限复核、中文近重复、来源证据和脱敏检索审计；
- Vue 知识管理流程及 mock HTTP 浏览器验收；
- 业务层端到端验收：PDF 上传、解析、人工发布、授权读取、归档和审计保留。

尚未完成，因此不得接入真实客户资料或宣称可上线：

- PostgreSQL Schema、Flyway 迁移及仓储适配器；
- MinIO、消息/任务队列和恶意软件扫描引擎的真实装配；
- Core API 的生产配置、依赖注入和前后端管理投影对齐；
- 钉钉真实租户联调、组织同步调度与正式登录会话；
- 经过审批的云端大模型连接、密钥托管和出网控制；
- PostgreSQL 批量授权适配器、OpenSearch 全文/向量索引、真实 Embedding/Reranker、引用 API 与检索质量黄金集；
- 真实负责人、业务基线、可信资料清单、备份恢复指标和试点准入。

真实 Docling 自动化目前覆盖固定 Markdown 表格和有效数字 PDF；DOCX、XLSX、PPTX、扫描 PDF 与 PNG 仍需 Task 0 批准的脱敏样本建立质量回归。业务层内存验收不证明数据库写入、任务状态与审计事件已在同一个 PostgreSQL 事务中回滚，也不证明并发上传唯一约束；这两项必须在 Task 2 的真实适配器完成后用故障注入集成测试验证。

这些边界以[知识底座实施计划](docs/superpowers/plans/2026-09-24-knowledge-foundation.md)为准，并在执行账本中持续记录。当前不创建 `pilot-foundation-v1` 标签。

可信检索内核的策略、验证命令、故障语义和后续基础设施门槛见[可信检索内核运行手册](docs/runbooks/trusted-retrieval-core.md)。当前只完成可长期保留的业务内核，不代表生产检索已经完成，明确不得创建 `pilot-retrieval-v1` 标签。

## 目标架构

```text
钉钉 ──身份/组织──> Java / Spring Boot 核心服务 ──业务状态、ACL、审计──> PostgreSQL
                         │
Web / Vue ──业务 API────┤
                         ├──源文件/派生文件──────────────> MinIO
                         │
                         └──受控字节流 + 版本化任务──────> Python / FastAPI 解析 Worker
                                                            │
                                                            └──CanonicalDocumentV1
```

Java 服务是业务事实与安全边界的唯一所有者。Python Worker 只负责无状态文档解析，不持有数据库、MinIO 或云模型密钥。详细理由见 [ADR-0001](docs/adr/0001-java-business-python-parser-boundary.md)。

## 仓库结构

- `apps/core-api`：Java 21 / Spring Boot 核心业务与安全边界；
- `apps/web`：Vue 3 / TypeScript 知识管理界面；
- `workers/document-parser`：Python 3.12 / FastAPI / Docling 解析 Worker；
- `contracts/parser`：Java 与 Python 共用的 JSON Schema；
- `docs/superpowers`：已批准的设计与分阶段实施计划；
- `docs/runbooks`：运维和人工恢复手册；
- `scripts/verify.ps1`：统一质量门禁。

## 本地开发

基础要求：

- JDK 21；
- Node.js 及 Corepack，使用仓库固定的 pnpm 版本；
- Python 3.12 与 uv 0.12.18；
- Windows PowerShell 7 或 Windows PowerShell 5.1。

安装前端与解析依赖：

```powershell
corepack pnpm install --frozen-lockfile
uv sync --project workers/document-parser --extra parsing --locked
```

执行统一验证：

```powershell
$env:JAVA_HOME = 'C:\Program Files\Zulu\zulu-21'
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/verify.ps1
corepack pnpm --dir apps/web exec playwright test e2e/knowledge-foundation.spec.ts
```

单独启动解析 Worker：

```powershell
uv run --project workers/document-parser uvicorn parser_worker.app:app `
  --app-dir workers/document-parser/src --host 127.0.0.1 --port 8001
```

健康检查地址为 `http://127.0.0.1:8001/health`。前端开发服务器可用下列命令启动：

```powershell
corepack pnpm --dir apps/web dev
```

Core API 目前没有 PostgreSQL/Flyway/MinIO 的可运行装配，不能仅凭 `spring-boot:run` 得到完整业务系统。应先完成实施计划 Task 0 和 Task 2，再按[知识底座运行手册](docs/runbooks/knowledge-foundation.md)进行联调和准入。

## 安全底线

- 未发布内容不进入普通用户查询范围；
- 授权失败与资源不存在对外统一为 404；
- Python 解析 Worker 不获得对象存储和数据库凭据；
- 任何云端模型调用必须经过审批、出网代理、字段脱敏和审计；
- 禁止在日志、配置、Git 历史或审计元数据中写入文件正文、Token、密钥和个人敏感信息；
- 物理删除内容寻址对象前，必须在数据库中原子确认无已提交引用和上传预留。

## 下一步

恢复 Task 0 与 Task 2：确认负责人和准入材料，在具备 Docker 或等价测试环境后补齐 PostgreSQL/Flyway、MinIO、真实仓储适配器、应用装配及前后端联调。只有全部投产门禁通过后，才可创建首个试点标签。
