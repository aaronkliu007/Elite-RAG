# 可信知识问答运行手册

## 1. 适用范围与准入结论

本手册覆盖单轮可信问答、来源再次鉴权、OpenAI 兼容模型网关、向量化/重排、黄金集离线评测、降级、排障和配置回滚。当前结论仍是：**受控测试可用、已实施但待最终验收，尚未生产准入**。

问答默认检索当前用户有权访问的全部知识空间。空间集合、用户身份、ACL、模型、端点、提示词和安全策略均由服务端决定，浏览器只提交问题和 `Idempotency-Key`。服务端保留规范化原问题的 HMAC 摘要用于幂等审计，在检索前检查问题文本；可脱敏问题仅以替换后的文本进入检索和聊天模型，命中阻断规则或脱敏器异常时按 `UNSAFE_TO_SEND` 拒答。模型调用审计的脱敏命中数合并问题与证据的命中。回答只能引用当前已发布、已授权并通过脱敏策略的证据；证据不足或不可验证时严格拒答。

## 2. 默认关闭与安全边界

默认配置 `RAG_AI_ENABLED=false`、`RAG_VECTOR_ENABLED=false`、`RAG_QA_EVALUATION_FIXTURE_ENABLED=false`，且 `RAG_QA_QUESTION_HMAC_KEY` 为空。此时不装配真实模型客户端，不写入黄金集虚构知识，问答 HTTP 能力也不开放；检索保留 BM25 路径并显式报告向量能力不可用。

启用前必须同时满足：

- 模型端点、供应商、数据出境范围和模型名称已经公司审批；
- 出站防火墙或代理只允许访问审批主机和端口，DNS/证书策略已评审；
- API 密钥和问题摘要 HMAC 密钥由仓库外密钥系统注入；
- 已使用脱敏或虚构数据完成黄金集、容量、费用和撤权验证；
- 生产环境已配置 TLS、告警、备份恢复、值班和回滚负责人。

模型网关只接受 HTTPS 根地址，地址不得包含用户信息、查询参数或片段，主机必须精确出现在 `RAG_AI_APPROVED_HOSTS`。客户端禁止 HTTP/HTTPS 自动重定向，不能依靠重定向访问另一主机。仅自动化测试可开启代码级回环 HTTP 例外，`application-local.yml` 固定关闭该例外，生产不可使用。

## 3. 仓库外配置与启动

基础设施仍按 README 启动。不要把模型密钥写入 `infra/compose/.env`；当前 Core API 以 Native Spring Boot 方式运行，模型配置只注入该进程。下面只展示变量关系，真实值必须来自审批配置中心或本机密钥管理器：

```powershell
$env:RAG_AI_ENABLED = 'true'
$env:RAG_AI_PROVIDER = 'approved-openai-compatible'
$env:RAG_AI_CONFIG_VERSION = 'model-config-v1'
$env:RAG_AI_ENDPOINT = 'https://approved-model-gateway.example.com'
$env:RAG_AI_APPROVED_HOSTS = 'approved-model-gateway.example.com'
$env:RAG_AI_API_KEY = $env:APPROVED_RAG_API_KEY
$env:RAG_AI_EMBEDDING_MODEL = 'approved-embedding-model'
$env:RAG_AI_RERANKER_MODEL = 'approved-reranker-model'
$env:RAG_AI_CHAT_MODEL = 'approved-chat-model'
$env:RAG_QA_QUESTION_HMAC_KEY = $env:APPROVED_RAG_QUESTION_HMAC_KEY

$env:RAG_AI_CONNECT_TIMEOUT = 'PT3S'
$env:RAG_AI_RESPONSE_TIMEOUT = 'PT15S'
$env:RAG_AI_TOTAL_TIMEOUT = 'PT30S'
$env:RAG_AI_MAX_INPUT_TOKENS = '12000'
$env:RAG_AI_MAX_OUTPUT_TOKENS = '2000'
$env:RAG_AI_MAX_CONCURRENCY = '4'
$env:RAG_AI_DAILY_DEPLOYMENT_TOKEN_LIMIT = '1000000'
$env:RAG_AI_INPUT_COST_PER_MILLION_TOKENS = '0'
$env:RAG_AI_OUTPUT_COST_PER_MILLION_TOKENS = '0'
$env:RAG_SANITIZATION_BUSINESS_TERMS = '内部项目代号,受控业务术语'
```

`RAG_QA_QUESTION_HMAC_KEY` 必须至少 32 个 UTF-8 字节，用于保存问题的 HMAC-SHA-256 摘要；不得与 API 密钥、登录摘要密钥或其他实例共用。`RAG_SANITIZATION_BUSINESS_TERMS` 是英文逗号分隔的阻断词，任何一项命中都会禁止该证据外发，变更后必须重新跑安全案例和黄金集。模型网关启用时供应商标识、模型配置版本、三个模型名、审批主机和 API 密钥都必须非空，否则应用拒绝启动。供应商和配置版本只能使用受控短标识，不得填写完整 URL、密钥或自由文本；每次端点、模型、超时、Token 上限或脱敏策略变更都必须提升配置版本。

问答提交默认按操作者固定窗口限流：`RAG_QA_ACTOR_RATE_LIMIT_WINDOW=PT1M`、`RAG_QA_ACTOR_RATE_LIMIT_MAX_REQUESTS=20`。超限返回 `429`、错误码 `QA_RATE_LIMITED` 和 `Retry-After`；限制在幂等执行器取得新请求所有权后生效，因此同一 `Idempotency-Key` 的已完成重放不会重复计数。计数使用 PostgreSQL 时钟并在独立短事务中提交，避免多副本 JVM 时钟偏差，也不会让操作者计数行锁跨越检索或模型调用；后续业务失败不退还已放行次数。不得通过扩大模型并发或代理重试绕过该保护；修改限额前应结合模型预算和压测结果审批。

启动后检查：

```powershell
Invoke-RestMethod http://127.0.0.1:8080/actuator/health/readiness
```

`modelGateway` 健康组件应为 `UP`；在允许查看健康详情的受控环境中应显示 `capability=ENABLED`，且不得包含端点、模型名或凭据。启用问答后使用系统现有 Session/CSRF 登录流程访问 `/qa`；不要绕过浏览器安全边界直接伪造身份。

## 4. 向量索引启用与回滚

Embedding 与查询向量必须使用相同模型和维度。已有无向量索引时，不得只把 `RAG_VECTOR_ENABLED` 改成 `true`：

1. 审批并锁定 Embedding 模型与 `RAG_VECTOR_DIMENSION`；
2. 创建包含对应维度向量映射的新索引代际；
3. 使用内部 `OpenSearchRebuildService` 全量重新投影全部已发布节点；
4. 校验文档数、抽样召回、权限过滤和向量维度；
5. 原子切换 `rag-chunks-read` 与 `rag-chunks-write` 后再接流量。

模型或维度变化同样必须创建新代际并全量重建，不能在原索引混用向量。回滚时先恢复上一组模型配置和 `RAG_VECTOR_ENABLED=false`，重启 Core API，确认 BM25 可用；若要恢复旧向量能力，只能切回与旧模型/维度一致且已验证的完整索引代际。

## 5. 降级、拒答与系统故障

- 向量关闭或 Embedding 不可用：保留 BM25 召回并标记 `VECTOR_UNAVAILABLE`。
- Reranker 不可用：保留 BM25/向量经 RRF 融合后的顺序并标记 `RERANKER_UNAVAILABLE`。
- OpenSearch、PostgreSQL 最终鉴权或最终证据再次鉴权不可用：整次问答失败关闭，不返回未复核答案。
- 模型调用前会对不同证据中的显式键值事实做确定性冲突检测，覆盖日期、金额、期限、比例、容量、QPS/TPS、人数/设备数和启停状态，并先归一化万元/元、年/月、GB/MB 等等价值。发现冲突或检测器不可用时统一按 `CONFLICTING_EVIDENCE` 严格拒答，绝不降级为模型自由判断。
- 当前冲突检测以高精度显式事实为边界，不承诺识别所有自然语言矛盾；未被结构化识别的自由文本仍受证据充分性、引用支持和黄金集门禁约束。
- `NO_AUTHORIZED_SPACE`、`INSUFFICIENT_EVIDENCE`、`CONFLICTING_EVIDENCE`、`UNSAFE_TO_SEND`、`INVALID_MODEL_OUTPUT`、`UNSUPPORTED_CLAIM`：属于预期严格拒答；不得自动改为模型自由回答。
- `MODEL_AUTHENTICATION`、`MODEL_RATE_LIMITED`、`MODEL_TIMEOUT`、`MODEL_UNAVAILABLE`、`MODEL_AUDIT_FAILED`、`FINAL_AUTHORIZATION_FAILED`、`QA_TIMEOUT`：属于系统故障；保留请求 ID 排障，但不得向用户泄露网关响应、提示词、证据或密钥。

模型生成结束后系统还会一次性复核该请求全部证据的当前授权、当前发布版本和 Canonical 节点。生成期间撤权、归档或改换当前发布版本会阻止答案完成。用户每次打开引用来源还会再次实时鉴权；已撤权和不存在统一返回 404 语义。

## 6. Token、并发、费用与重试

- 单次输入上限由 `RAG_AI_MAX_INPUT_TOKENS` 控制，输出预留由 `RAG_AI_MAX_OUTPUT_TOKENS` 控制；超过上限在发送前失败关闭。
- `RAG_AI_MAX_CONCURRENCY` 使用公平信号量；无可用许可时立即按限流失败，不排无限队列。
- `RAG_AI_DAILY_DEPLOYMENT_TOKEN_LIMIT` 按 UTC 自然日约束单个 Core API 进程的模型调用预算，并按供应商返回的实际 Token 对预留做校正。
- 当前日额度只保存在进程内存中，重启会清零，多副本也不共享；正式多副本或强费用控制前必须改为集中式持久配额，不能把当前值当作财务硬上限。
- 客户端仅对限流、超时和暂时不可用做一次受控重试，并受同一总超时约束；问答编排也只允许一次瞬时故障重试。不要在代理层叠加无界重试。
- 费用指标使用每百万 Token 单价估算。单价为 `0` 表示未配置估价，不表示模型免费。

## 7. 黄金集与发布门禁

仓库内 `contracts/qa/golden-dataset-v1.json` 是 50 条脱敏、虚构案例，覆盖 Word、PDF、PPT、Excel、Markdown、PNG OCR、跨文档、同义词、缩写、冲突、拒答和权限陷阱。评测必须复用生产问答的检索、鉴权、脱敏、模型和引用校验路径，不得创建绕过权限的“评测捷径”。

在全新的隔离评测数据库中，可在启动 Native Spring Boot 进程前显式设置：

```powershell
$env:RAG_QA_EVALUATION_FIXTURE_ENABLED = 'true'
```

该开关只在 `local` profile 生效，默认关闭。启动时会以固定 ID 事务性安装 1 个虚构评测用户、2 个评测空间、50 条已授权虚构文档和 4 条无授权的权限陷阱文档，并为两个空间各写入一条 OpenSearch 投影事件。初始化可重复执行；若固定 ID 已被其他数据占用或只存在部分夹具，应用以 `EVALUATION_FIXTURE_ID_CONFLICT` 拒绝启动，禁止覆盖或修补现有数据。不要在承载真实知识或真实用户的数据库启用该开关。

初始化完成不等于索引已就绪。必须等待 `retrieval_projection_event` 中两条空间事件处理成功，并确认 OpenSearch 投影 readiness 为 `UP` 后，才能发起评测。评测完成后应销毁隔离数据库和对应测试索引；不要把虚构夹具迁移到生产实例。

系统管理员通过 `POST /api/admin/qa/evaluation-runs` 启动评测，请求只允许 `datasetId`、`codeVersion`、`policyVersion`、`modelConfigVersion`；非管理员按不存在处理。首版固定门槛为：

- 未授权泄漏数等于 0；
- 引用有效率等于 100%；
- Recall@10 不低于 85%；
- 声明支持率不低于 90%；
- 正确拒答率不低于 90%。

任何一项不达标都不得批准模型、提示词、脱敏策略、检索策略或索引代际上线。当前黄金集是工程契约，不代表真实业务验收；生产准入前必须由业务、法务和安全共同维护真实脱敏黄金集并人工抽验。

## 8. 模型配置变更与回滚

每次变更端点、模型、Token 上限、超时、费用单价、脱敏词或提示词时：

1. 记录代码版本、策略版本、提示词版本、模型配置版本和索引代际；
2. 使用固定黄金集运行完整评测并保存门禁结果；
3. 在受控环境验证限流、超时、无效引用、敏感内容和撤权竞态；
4. 通过变更审批后分批启用并监控拒答率、失败率、延迟、Token 和估算费用；
5. 异常时将 `RAG_AI_ENABLED=false` 并重启，保留知识管理与 BM25 检索；不要删除问答审计或修改历史评测结果。

若仅 Chat 模型异常，也按整套已审批配置回滚；首版不支持在运行中让浏览器或用户选择备用模型。

## 9. 日志、审计与排障禁项

日志和审计禁止记录：原始问题、完整提示词、证据正文、文档正文、原始模型响应、向量、ACL 明细、用户名、Cookie、Token、API/HMAC/数据库/AIStor 密钥或许可证内容。模型调用审计只记录受控供应商标识、端点类别（例如 `chat-completions`，不是完整 URL）、模型名、模型配置版本、重试次数、阶段、结果码、数量、耗时、Token 和不可逆请求/响应摘要。

排障顺序：

1. 先看 readiness 的 PostgreSQL、AIStor、OpenSearch、Embedding 和模型网关能力；
2. 再按请求 ID 查询状态、阶段指标和安全结果码；
3. 检查授权空间数量、召回/最终鉴权/选中证据数量，不查看正文；
4. 检查网关状态码、超时、限流和 Token 统计，不打印响应体；
5. 对来源问题用原用户 Session 重新打开，确认 404 是撤权还是资源失效的统一安全表现。

## 10. 验证与当前未完成项

```powershell
$env:JAVA_HOME = 'C:\Program Files\Zulu\zulu-21'
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/verify.ps1
corepack pnpm --dir apps/web exec playwright test
```

统一验证包含真实 PostgreSQL、AIStor、OpenSearch 和受控 WireMock 网关，不调用真实云端模型。它覆盖多空间证据、未授权内容不外发、引用可定位、生成期间撤权、来源查看前撤权、并发幂等、私钥阻断、超时/限流和无效引用。

仍未完成：真实模型与真实脱敏黄金集的公司审批和联调、集中式多副本额度、正式容量/性能/费用验证、备份恢复与灾备演练、渗透测试、出站代理/TLS/密钥托管、生产监控和值班、业务验收、钉钉身份与组织同步、报表和方案工作台生产化。完成这些事项前不得宣称生产准入，也不得创建试点标签。
