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

Embedding、重排和聊天适配器在发出 HTTP 请求前会再次检查所有动态文本。调用方应先完成脱敏；若文本仍需替换、命中阻断规则，或脱敏器异常，适配器直接返回 `MODEL_GATEWAY_POLICY_VIOLATION`，不发送该请求。此检查覆盖批量向量输入、重排查询与候选正文，以及问答问题、语言和版本字段、证据标题与摘录。

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

`RAG_QA_QUESTION_HMAC_KEY` 必须至少 32 个 UTF-8 字节，用于保存问题的 HMAC-SHA-256 摘要；不得与 API 密钥、登录摘要密钥或其他实例共用。`RAG_SANITIZATION_BUSINESS_TERMS` 是英文逗号分隔的阻断词，任何一项命中都会禁止该证据外发，变更后必须重新跑安全案例和黄金集。模型网关启用时供应商标识、模型配置版本、聊天模型、审批主机和 API 密钥都必须非空；Embedding 和 Reranker 模型可留空并明确降级，但启用向量检索时必须配置 Embedding 模型。DeepSeek 官方接口的隔离测试配置见 [DeepSeek 联调说明](deepseek-local-integration.md)。供应商和配置版本只能使用受控短标识，不得填写完整 URL、密钥或自由文本；每次端点、模型、超时、Token 上限或脱敏策略变更都必须提升配置版本。

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
- 模型调用前对经过最后一次数据库鉴权的召回候选执行确定性冲突检测，不局限于最终选给模型的证据。检测覆盖显式日期、金额、期限、比例、容量、QPS/TPS、人数/设备数、启停状态和同一表述主体的明确批准与未批准状态；部分单位会归一化。候选默认受融合上限 30 条和每条摘要 1200 字限制，额外筛查材料不进入模型或检索响应。发现冲突、检测器不可用，或提取的审批状态、结构化事实任一类别超过 1024 条时，按 `CONFLICTING_EVIDENCE` 严格拒答。
- 当前冲突检测以高精度显式事实为边界，不承诺识别所有自然语言矛盾；未被结构化识别的自由文本仍受证据充分性、引用支持和黄金集门禁约束。
- 冲突检测不能覆盖召回或融合范围之外、摘要截断以外或未能识别的矛盾，也不能保证候选与问题语义相关。如果检索混入不相关的相反结论，扩大筛查可能增加过度拒答；应改善相关性并重新评测，不能为了提高回答率而绕过冲突门禁。
- 精确引用校验只证明引文与证据文本一致，不能单独证明该引文与问题有关；合成假模型已复现引用无关证据仍被接受的情形，尚需单独解决，不可据此认定可生产使用。
- 引用核验增加有限的边界保护：会拒绝部分丢掉紧邻中文否定词、数字位数、负号或数量限定词的截取；针对部分千分位、分数、时间和科学计数法也有反例测试；数量后紧邻“以上、以下、以内、左右”等限定或明确数值区间时，截去这些范围会被拒绝。出现多个相同片段且任一匹配边界不安全时保守拒答。每次回答最多处理 100 条声明、225 个支持引用，超限按 `UNSUPPORTED_CLAIM` 拒答。该保护可能误拒（例如双重否定），也可能漏掉超出短窗口的否定、其他语言及未覆盖的数字格式，**不是完整语义校验**。
- `NO_AUTHORIZED_SPACE`、`INSUFFICIENT_EVIDENCE`、`CONFLICTING_EVIDENCE`、`UNSAFE_TO_SEND`、`INVALID_MODEL_OUTPUT`、`UNSUPPORTED_CLAIM`：属于预期严格拒答；不得自动改为模型自由回答。

问答因冲突拒答后，可通过“检索原始资料”进入全文检索。默认检索选定知识空间，也可选择“指定文档”并从有权访问的已发布文档中选择一份；文档较多时可继续加载。指定文档检索仅返回该文件的授权片段，其他文档及其中的冲突不在本次检索范围内，不能据此认为冲突已消除或形成全局结论。检索结果仍经数据库权限复核，文档列表加载失败时不会自动扩大为整个空间。
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

管理员可在应用启动前通过环境变量 `RAG_QA_EVALUATION_DATASET_PATH` 指定独立维护的黄金集 JSON 文件，例如：

```powershell
$env:RAG_QA_EVALUATION_DATASET_PATH = 'E:\git_secrets\Elite-RAG\reviewed-qa-dataset.json'
```

该文件必须是应用进程可读取的绝对路径，最大 10 MiB，格式沿用内置黄金集。未配置时仍加载内置合成集；路径错误、文件无效、重复字段、尾随 JSON 或超限时启动失败，不会静默回退。配置在进程启动时读取，修改文件后需安全重启并重新评测；数据集内容变化会使旧记录不再通过当前门禁。文件应存放在代码仓库之外并限制访问，标注不得包含不适合本评测环境处理的信息。

**不得同时设置外部黄金集和启用 `RAG_QA_EVALUATION_FIXTURE_ENABLED`**：本地虚构夹具会根据内置黄金集构建测试数据，两者同时启用时应用会拒绝启动。真实或脱敏业务黄金集必须使用已授权导入的知识及评测账号，不能使用虚构夹具代替。审核界面只能生成由人工复核后合并的标注片段，配置文件本身不自动产生审核结论。

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
- 已生成声明的独立复核覆盖率等于 100%；
- 正确拒答率等于 100%；
- 本次数据集内容指纹与当前数据集一致。

上述比例必须有对应样本：期望证据、引用、生成声明或拒答案例的数量为零时，对应比例按 0 处理，门禁不通过。预期回答却拒答的案例计入声明支持率分母，但不计入已生成声明的复核覆盖率分母。`generatedClaimCount`、`reviewedClaimCount`、`supportedClaimCount` 分别记录生成、复核、支持数量；标记为不支持的声明计入复核数量，但不计入支持数量。

每条可回答案例可在黄金集的 `reviewedClaims` 中登记独立复核结果：填写完整声明原文 `text`、审阅记录标识 `reviewReference`、结论 `SUPPORTED` 或 `UNSUPPORTED`，以及完整的 `supportingEvidence` 集合。每项来源包含 `documentId`、`versionId`、`nodeId`、`contentDigest`、`titleDigest`、`locatorDigest`。案例的 `reviewQuestionDigest` 为实际送入问答处理的规范化、脱敏后问题的 UTF-8 SHA-256；来源内容摘要取证据快照的 `content_digest`，标题摘要取快照标题的 UTF-8 SHA-256，定位摘要取 PostgreSQL 中 `locator::text` 的 UTF-8 SHA-256。声明、实际引用来源集合、问题或任一来源摘要不一致时，该声明按未复核处理。标注必须由有权限的业务人员基于证据独立审核；`reviewReference` 只是记录标识，系统不会验证审阅人身份或证明审阅确实发生。仓库内 50 条合成案例没有这些标注，因此不能通过新门禁，不应自行编造标注。

系统管理员可从知识空间首页进入“问答评测”，输入运行编号查看逐案例诊断，并筛选预期回答但拒答、有未审核声明或系统失败的案例。API 的 `caseResults` 仅包含案例和请求标识、预期与实际状态、原因及聚合计数，不包含问题、答案、来源标题或证据正文。旧运行没有这些记录。管理员可在已回答的案例点击“审核声明”，查看问题、声明及经再次鉴权的来源，独立选择支持或不支持并填写审核记录标识；系统生成可复制的 JSON 片段，需人工合并到外部黄金集的对应案例，提升版本、重启后重新评测。界面不直接写入黄金集，不验证审核记录真实性，不自动批准标注。

审核内容只对该案例的原评测账号开放，且此账号必须为系统管理员并在访问时仍有全部来源权限；打开审核及生成片段前均会复核，访问写入审计且响应禁止缓存。数据集指纹变化、脱敏结果变化、来源撤权、密钥轮换或运行缺少审核绑定时无法继续审核。旧运行没有审核绑定，需要重新运行评测；内置虚构夹具的固定评测账号不能作为管理员登录，需在隔离环境准备以本人账号为评测主体的外部黄金集和已授权知识。原始 SHA 问题摘要不保存在诊断记录中，记录的是使用服务端密钥计算的用途隔离 HMAC。不同评测账号之间不能代审，后续需要更细粒度的授权委派与审核持久化。

结果中的 `assessment` 按当前规则重算，`historicalAssessment` 保留当时结果；`datasetDigest` 记录完整数据集内容指纹。历史记录没有指纹或当前数据集与记录不一致时，当前判断包含 `DATASET_PROVENANCE` 并失败，历史结果本身不变。修改数据集或标注后须重新评测，不得仅复用版本号或历史通过结果。

Recall@10 统计经过最终权限复核和证据选择后的前 10 条，而非 OpenSearch 未鉴权原始召回。即使问答在保存证据快照前因冲突或证据不足而拒答，评测仍会在内存中记录这次已授权检索的文档和节点标识，以便计算召回；不记录问题或证据正文。

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


### 回答与问题的独立复核（2026-11-05）

人工审核每条声明时，需要分别判断来源是否支持该声明（`verdict`）以及该声明是否针对用户问题（`questionRelevance`，可选 `RELEVANT` 或 `IRRELEVANT`）。审核页面会在两项都选择后生成标注片段，标注仍需独立人工核实并由有权限的人员合并到评测数据集。

旧数据缺少 `questionRelevance` 时按 `UNKNOWN` 处理，不能算作完成复核。明确标为 `IRRELEVANT` 的声明计入无关数量，并触发 `QUESTION_RELEVANCE` 门禁，即使总体支持率高于阈值也不通过。支持数量仅包含来源支持且针对问题的声明。此项校验不证明回答完整、符合全部业务要求，也不等于自动语义验证。

评测数据集指纹升级到 v2，覆盖新增字段。以旧指纹保存的历史运行记录保留原始指标及历史判断；在当前规则下，其数据集指纹与 v2 不一致，当前门禁保守显示来源不匹配，需要补充独立审核并重新运行评测。不得为了通过门禁批量将旧记录默认标为相关。

### 问题范围冲突扩展的部署边界（2026-11-18）

问答提供本地策略扩展接口，默认关闭；仅设置 `rag.qa.question-scoped-conflicts.enabled=true` 不会安装策略。只有部署方提供且注册唯一经过评审的 `QuestionScopedConflictPolicy`，或另行启用内置试验策略时才可能启用；缺失、多个策略、判断未知或出错均保持严格拒答。策略可接触尚未脱敏的授权候选，必须是可信的本地实现，禁止将内容未经审批外发。

内置试验策略需要同时设置 `rag.qa.question-scoped-conflicts.enabled=true` 和 `rag.qa.question-scoped-conflicts.builtin-backup-policy=true`。它仅识别严格限定的备份或恢复演练问题、独立的单表预算文档及独立备份制度，必须检查完整筛查候选和完整规范化文档；其他情况均拒答。当前合成 30 条候选场景因存在不可识别材料而仍拒答，覆盖范围非常有限，尚未通过企业业务验收，不应在生产开启。

对于已经完成**人工审核**的重复问题，可使用另一种审核清单策略。审核人员需对该问题可能出现的全部筛查候选，逐份审核完整文档，将其标为 `RELEVANT`（相关）或 `UNRELATED`（无关），并确认相关来源间不存在会影响获批回答的未解决冲突。只有冲突对两侧均被审核为无关，且模型输出的每项声明、引用原文、文档版本和节点都与获批记录完全一致时，才可能回答。缺少审核、出现新候选、文档内容变化、来源定位与数据库不符、声明或引用不同，均继续拒答。审核针对实际筛查候选；检索范围仍有限，不能证明知识库中不存在未召回的冲突。

审核输入文件须保存在内网受控目录，不能提交到代码库。输入 JSON 的顶层为 `questions`；每项包含原始 `question`、真实审批记录 UUID `approvalId`、明确为 `true` 的 `allDocumentsReviewed`，以及 `documents` 和 `claims`：

```json
{
  "questions": [{
    "question": "示例问题？",
    "approvalId": "00000000-0000-0000-0000-000000000001",
    "allDocumentsReviewed": true,
    "documents": [{"documentId":"00000000-0000-0000-0000-000000000002", "contextFingerprint":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "relation":"RELEVANT"}],
    "claims": [{"documentId":"00000000-0000-0000-0000-000000000002", "versionId":"00000000-0000-0000-0000-000000000003", "nodeId":"示例节点", "text":"获批声明", "quote":"包含获批声明的原文"}]
  }]
}
```

以上仅是字段示意，不能作为真实审批记录。文档 UUID、当前已发布版本 UUID、节点 ID、原文和位置须由有权审核人员从受信来源核对；`contextFingerprint` 可由数据库函数 `qa_document_context_fingerprint(document_id, version_id)` 获取。审核文件中的问题应为系统脱敏后、规范化前的可用问题；声明和引文须与实际脱敏后允许输出的文字逐字一致，不能用摘要或自行改写替代。

在与服务相同密钥的受控环境中，通过 `python scripts/build_reviewed_qa_manifest.py --input <审核输入的绝对路径> --output <新清单的绝对路径>` 生成清单。脚本读取现有的 `RAG_QA_QUESTION_HMAC_KEY`，不输出密钥、问题、声明或引文原文，也不覆盖已有文件；输入文件仍含原文，应单独保护。生成结果仅含标识、指纹和 HMAC，须保存原始审批记录并限制读写。签名可发现没有密钥者对清单的篡改，但**不能验证审批编号是否真实，也不能代替人员审批**；持密钥者和部署配置写入者必须受控。

审核清单默认不加载。需在内网部署配置中显式设置 `rag.qa.question-scoped-conflicts.reviewed-manifest` 为签名清单的绝对路径，并设置 `rag.qa.question-scoped-conflicts.enabled=true`；不要同时启用其他范围策略，否则系统回退为严格拒答。文件签名无效或格式错误时应用启动失败。问题 HMAC 密钥轮换后须重新生成清单；清单内容变化需重启应用，审计中的策略版本由清单内容摘要决定，必须保留对应历史清单以供追溯。撤销或替换审核清单时，应先停止接收流量并排空所有使用旧清单的实例，再启动新配置：旧实例不会自动感知文件变更。新实例不会返回依据不同或已停用范围策略产生的历史答案，用户需重新提问。该方式要求事先审核具体问题和具体引用，不能代替通用语义判断或企业试点验收。

扩展要求所有检测出的冲突对均判定与问题无关，并限制回答逐字对应脱敏证据及通过策略的答案相关性复核；身份或审批冲突、候选疑似达到摘录截断上限时继续拒答。候选来自受限的检索结果，并非对整个知识库做穷举检查。候选身份会保存用于返回前的事务内授权复核，不保存候选正文；授权变化或保存失败时不会返回答案。此接口本身不是通用语义判断能力，投产前须用经批准的业务数据集独立验证。

多条声明组成的回答允许在声明之间使用空格、制表符或换行，避免单纯排版差异引发拒答；声明内容、引用和来源仍需逐字匹配，声明之间夹带文字或标点不会因此获得放行。原有引用边界、相关性及最终权限检查继续生效。

部署需依次执行数据库迁移 V022–V026。V025 为冲突筛查候选保存完整规范文档及标题的 SHA-256 指纹，并在最终保存和历史读取时检查；V026 固定指纹计算的时间区，避免不同数据库连接得到不同结果。来源内容变化、旧筛查快照没有指纹，或 V025 期间以非 UTC 时区生成的旧指纹不匹配时，答案读取按无权限处理，需重新提问。审计中的旧策略版本标为 `legacy-unknown`。V022 和 V023 之间旧版本写入审计可能受非空约束影响，执行迁移时应先暂停旧实例的问答写入，迁移完成并升级所有实例后再恢复；策略审核通过前保持扩展开关关闭。不得据此宣称已消除业务误拒或完成生产验收。
