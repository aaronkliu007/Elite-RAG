# 核心知识管理闭环运行手册

## 1. 适用范围与准入结论

本手册覆盖本地账号、固定角色、知识空间、文档上传与解析、人工审核发布、全文检索和来源下载的受控测试环境。当前结论是：**受控测试可用、尚未生产准入**。

不得接入真实客户敏感资料，不得把本手册当作生产部署方案。正式试点前仍需完成企业恶意文件扫描、备份恢复演练、容量压测、渗透测试、日志与指标平台、TLS、OpenSearch 安全插件、密钥托管和发布审批。

固定目录如下，两个目录名都不包含额外反斜杠：

```text
项目：E:\git_repo\Elite-RAG
许可证：E:\git_secrets\Elite-RAG\minio.license
```

许可证只读挂载到 AIStor，不得复制进仓库、日志、截图或工单。

## 2. 启动受控测试环境

先准备本机配置并校验 Compose：

```powershell
Set-Location 'E:\git_repo\Elite-RAG'
Copy-Item infra/compose/.env.example infra/compose/.env
docker compose --env-file infra/compose/.env -f infra/compose/compose.yaml config --quiet
docker compose --env-file infra/compose/.env -f infra/compose/compose.yaml up -d --wait
docker compose --env-file infra/compose/.env -f infra/compose/compose.yaml ps
```

所有开发端口只绑定 `127.0.0.1`：PostgreSQL `5432`、AIStor API/控制台 `9000/9001`、OpenSearch `9200`、Parser Worker `8001`。Parser Worker 不持有数据库、AIStor、身份源或模型凭据；它只接收 Java 服务发送的单次受控文件流和版本化任务元数据。

首次启动 Parser Worker 会在命名卷中缓存依赖和运行时虚拟环境，耗时会明显长于其他容器。PyTorch 固定使用官方 CPU wheel，不下载 CUDA 运行时；源码仍以只读方式挂载。检查服务：

```powershell
Invoke-RestMethod http://127.0.0.1:8001/health
Invoke-RestMethod http://127.0.0.1:9200/_cluster/health
```

Core API 继续使用 README 中的白名单方式导入应用变量，并额外设置：

```powershell
$env:RAG_PARSER_ENDPOINT = 'http://127.0.0.1:8001'
$env:SPRING_PROFILES_ACTIVE = 'local'
.\mvnw.cmd -pl apps/core-api spring-boot:run
```

不要向 Core API 注入 `MINIO_ROOT_*` 或许可证路径。不要向 Parser Worker 注入任何数据库、对象存储或模型密钥。

## 3. 初始系统管理员引导

`local` 部署配置首次启动且数据库中没有系统管理员时，默认创建用户名 `admin`、初始密码 `admin` 的系统管理员。首次登录必须修改初始密码；修改完成后原会话失效，需要使用新密码重新登录。新密码必须为 12 至 128 个字符，且不能与初始密码相同。未完成修改前，后端拒绝业务 API 请求，仅开放当前会话、修改初始密码和退出所需接口。

可以通过部署环境变量覆盖默认引导信息；自定义密码须为 12 至 128 个字符，引导管理员首次登录也必须修改密码：

```powershell
$env:RAG_BOOTSTRAP_ADMIN_USERNAME = 'local-admin'
$env:RAG_BOOTSTRAP_ADMIN_PASSWORD = '<从本机密钥管理器读取>'
$env:RAG_BOOTSTRAP_ADMIN_DISPLAY_NAME = '本地系统管理员'
```

引导只在数据库中不存在系统管理员时生效。系统已有管理员后，默认账号或覆盖变量都不会重置已有账号；不存在“第一个注册用户自动成为管理员”的行为。数据库升级需先应用 Flyway V027。自定义明文口令不应写入日志或脚本。

系统管理员登录后创建普通用户和知识空间。知识空间创建者会在同一事务内自动获得该空间的 `SPACE_ADMIN` 角色和 USER SPACE ACL，确保创建后即可管理、读取、检索与下载；其他空间管理员、编辑者、审核发布者、只读成员仍须按空间显式授权。前端是否显示按钮不构成授权，所有动作仍由后端实时判定。

## 4. 账号停用、恢复与密码重置

- 停用账号会立即推进会话版本，旧 Session 下一次请求失效。
- 重新启用只恢复账号登录资格，不恢复已经撤销的空间角色。
- 密码重置同样撤销全部旧 Session；新口令不得通过聊天、工单或日志传递。
- 管理端禁止停用当前登录账号，并通过 PostgreSQL 事务锁保证并发停用操作至少保留一名已启用的系统管理员。其他管理员账号不可用时，由仍然有效的系统管理员恢复。首版没有数据库外“万能口令”，不得直接修改密码哈希或会话版本。

恢复后至少验证：旧 Cookie 返回未认证、新口令可以登录、空间角色与预期一致、审计中只有用户 ID 和动作，不含用户名或口令。

## 5. 文档入库、审核与任务重试

支持 `.docx`、`.xlsx`、`.pdf`、`.pptx`、`.md` 和 `.png`，单文件上限 100 MiB。上传成功只创建不可变草稿版本并异步解析；只有解析成功、元数据完整的草稿才能提交审核。审核发布者可以驳回或发布，驳回意见保留在不可变审核记录中。

解析任务使用租约和有限重试：

1. 临时失败等待 `next_attempt_at` 后自动重试；
2. Worker 崩溃后等待租约到期，由新的租约 Token 接管；
3. `ENCRYPTED`、`UNSUPPORTED`、`LIMIT_EXCEEDED` 等不可重试错误应由用户修正文档后重新上传；
4. 禁止直接修改 `lease_token`、`attempt_count`、解析状态或 Canonical 正文；
5. 首版没有公共手工重放 API。达到重试上限的任务必须保留错误码和审计，待受控运维工具交付后处理。

对象已写入而数据库事务失败时只登记 Blob 清理候选；候选不是删除许可。详细租约与清理规则见 `docs/runbooks/knowledge-foundation.md`。

## 6. 全文检索与降级判定

默认生产形态只启用 BM25 全文召回。经过审批并完成向量索引重建后可以启用真实 Embedding 和 Reranker；未配置或暂时不可用时，响应应明确包含 `VECTOR_UNAVAILABLE`、`RERANKER_UNAVAILABLE`，这属于可见能力降级，不应伪装成混合检索。问答能力的独立启用、严格拒答和配置回滚见 `docs/runbooks/grounded-qa.md`。

- OpenSearch 全部不可用：检索返回服务不可用，readiness 为 `DOWN`；不得返回伪造空结果。
- PostgreSQL 最终鉴权不可用：检索失败关闭；不得只依赖索引 ACL。
- 仅向量不可用：保留全文结果并显示降级提示。
- 发布已成功但投影尚未消费：权威发布状态不回滚，等待投影 Worker 收敛。

检索日志和审计不得记录查询正文、节点正文、向量或 ACL 明细，只记录哈希、数量、耗时和降级原因。

## 7. 索引引导与重建

首次启动需要让 `rag-chunks-read` 与 `rag-chunks-write` 同时指向同一代际索引；命令和映射见 `docs/runbooks/retrieval-production-adapters.md`。

重建必须调用内部 `OpenSearchRebuildService` 的受控运维入口：创建新代际、记录起始水位、全量扫描、重放增量、排他锁内最终追平、刷新和校验，最后原子切换两个别名。当前没有公共重建 API；不要直接把空索引切到读别名，也不要手工修改投影事件为完成。重建失败时保留旧别名，换新的 generation 名重试。

## 8. 撤权即时验证

撤销只读成员后，不等待 OpenSearch 投影收敛即可验证最终鉴权：

1. 使用被撤权账号保留的现有 Session 发起文档详情、预览、下载和相同检索；
2. 文档请求应返回与不存在一致的 404 语义，不能泄露标题；
3. 检索即使在 OpenSearch 中召回旧命中，也必须在 PostgreSQL 批量复核后移除 Evidence；
4. 再检查投影事件最终收敛，不能以“索引仍有旧内容”为由延迟撤权。

自动化证据位于 `CoreKnowledgeManagementAcceptanceTest`，它在不更新陈旧索引的情况下撤销角色并确认下一次检索和下载立即失败。

## 9. 统一验证与退出

```powershell
$env:JAVA_HOME = 'C:\Program Files\Zulu\zulu-21'
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/verify.ps1
corepack pnpm --dir apps/web exec playwright test
```

统一脚本校验 Compose，运行 Java `clean verify`（含真实 PostgreSQL、AIStor、OpenSearch 闭环验收）、Vue、Python Worker 测试和静态检查。Java 验收通过版本化 Parser Contract 驱动解析完成，Python 套件独立验证真实 Worker HTTP、六类格式边界、超限、加密和超时；两层共同覆盖解析边界，Maven 不依赖本机常驻 Python 进程。

停止并保留数据：

```powershell
docker compose --env-file infra/compose/.env -f infra/compose/compose.yaml down
```

只有确认本地数据和 Parser 依赖缓存均可丢弃时，才在隔离开发环境执行 `down --volumes`。退出前确认 Git 未跟踪 `.env`、许可证、真实资料、密码或密钥。

## 10. 已知限制

- 未接入企业恶意文件扫描器，不能生产接收外部文件。
- 未完成备份恢复、容量、灾备、渗透和性能测试。
- OpenSearch 本地 Compose 关闭安全插件，只能绑定回环地址。
- 没有公共解析重放、投影重建或失败任务管理 API。
- 已实现默认关闭的 OpenAI 兼容 Embedding、Reranker、问答模型适配器；真实供应商审批、真实凭据联调和生产容量/费用验证尚未完成。
- 已实现受控单轮 RAG 问答闭环；真实业务黄金集验收、生产安全加固、钉钉登录与组织同步、报表和方案生成仍属于后续批次。
