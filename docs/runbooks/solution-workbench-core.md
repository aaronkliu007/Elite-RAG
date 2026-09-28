# 方案设计与评审工作台业务内核运行手册

## 1. 适用范围

本手册用于验证和排查“方案设计与评审工作台”的基础设施中立业务内核。当前实现覆盖需求版本、方案大纲、分章检索与生成、引用核验、人工采纳、高风险确认、Evidence 当前权限复核和不可变定稿。

当前实现不是可供员工直接使用的完整系统。本批明确不包含 HTTP API、前端工作台、数据库表与仓储适配器、真实云端大模型、任务队列、钉钉装配和 Word 导出。测试源码中的内存仓储、假模型和固定身份只用于自动化验收，不会进入生产包。

## 2. 业务闭环

正常闭环按以下顺序执行：

1. 创建不可变需求版本，并由有权用户确认当前准确版本；
2. 创建不可变大纲版本，确认当前大纲；
3. 按大纲章节构造检索查询，请求可信检索服务返回 Evidence；
4. Evidence 少于最低数量时停止，不调用模型；
5. 将确认需求、当前章节、模板规则和 Evidence 分区传给模型端口；
6. 校验模型结构、Claim ID 和 Evidence 白名单，再执行确定性与可选语义核验；
7. 模型结果只保存为 `MODEL_DRAFT`，人工采纳时另建 `ADOPTED` 版本；
8. `PARTIAL`、`UNSUPPORTED` 阻止采纳；`HUMAN_CONFIRMATION_REQUIRED` 必须由授权用户绑定准确章节、声明、Evidence、需求和大纲进行确认；
9. 定稿前检查所有必填章节、当前版本关系和风险确认，并一次性复核全部 Evidence 当前访问权限；
10. 定稿版本、聚合最终指针和成功审计由 `SolutionFinalizationStore` 原子提交。

## 3. 状态与版本

方案阶段为：

```text
REQUIREMENTS_DRAFT
  -> REQUIREMENTS_CONFIRMED
  -> OUTLINE_DRAFT
  -> OUTLINE_CONFIRMED
  -> IN_REVIEW
  -> FINALIZED
```

流程阶段只是展示信息，不能替代实时门禁。每次修改都创建新版本：

- 修改需求会撤销需求确认、大纲确认、当前大纲、已采纳章节和定稿资格；
- 修改大纲会撤销大纲确认、已采纳章节和定稿资格；
- 重新生成章节只创建候选稿，不自动改变当前采纳指针；
- 定稿后继续修改必须调用 `startNewRevision`，旧定稿版本保持不可变；
- 所有聚合更新都携带 `expectedRevision`，冲突必须显式失败。

## 4. 生成状态机与默认策略

生成状态为：

```text
PENDING -> RETRIEVING -> GENERATING -> VERIFYING -> SUCCEEDED
                       \-> INSUFFICIENT_EVIDENCE
任一活动状态 ---------> FAILED_RETRYABLE | FAILED_MANUAL | CANCELLED
```

默认策略：

| 参数 | 默认值 |
|---|---:|
| 检索 Evidence 数 | 8 |
| 最低 Evidence 数 | 1 |
| 分章 Prompt | `solution-section/v1` |
| 模型截止时间 | 60 秒 |
| 需求提案 Prompt | `requirements/v1` |
| 大纲提案 Prompt | `outline/v1` |

客户端幂等键与方案、需求版本、大纲版本、章节和基线章节共同形成输入指纹。同键同指纹复用同一逻辑 attempt 和成功草稿；同键异指纹返回冲突。检索或模型临时失败在同一 attempt 的检查点恢复，不创建第二笔费用记录。

检索、模型和核验等外部调用返回后必须重新读取方案聚合。需求、大纲、确认状态或章节基线已变化时，迟到结果进入 `CANCELLED`，不能保存草稿。

## 5. 引用与风险门禁

- 事实 Claim 必须引用至少一个本次 Evidence ID；
- 未知 Evidence ID、重复 Claim ID、引用集合不一致或 null 模型响应进入 `FAILED_MANUAL`；
- 数字和单位不一致由本地规则直接判为 `UNSUPPORTED`；
- 语义核验异常、null、缺失、重复或未知返回不会默认通过，而是进入人工确认；
- `PARTIAL` 和 `UNSUPPORTED` 不能通过签字变成可采纳；
- 风险确认保存声明指纹，不记录正文；声明、Evidence、需求、大纲或章节版本变化后旧确认失效；
- 定稿时 `EvidenceAccessPort` 必须返回与请求完全相同的 ID 集合。子集、未知 ID、null 或异常全部失败关闭。

## 6. 权限与审计

业务内核只使用以下供应商中立动作，不硬编码钉钉角色名：

- `EDIT_REQUIREMENTS`
- `CONFIRM_REQUIREMENTS`
- `CONFIRM_OUTLINE`
- `ADOPT_SECTION`
- `CONFIRM_RISK`
- `FINALIZE`

方案审计动作包括 `SOLUTION_CREATE`、`REQUIREMENTS_CONFIRM`、`OUTLINE_CONFIRM`、`RISK_CONFIRM` 和 `SOLUTION_FINALIZE`。允许记录的内容限于方案/版本/声明/请求/Prompt/模型标识、状态、数量、耗时、Token、费用和内容哈希。

需求正文、章节正文、Claim 正文、Evidence 摘要、风险确认原因、模型原始输出和供应商错误正文不得进入审计元数据。定稿成功审计必须与定稿版本及聚合指针同一事务提交。业务失败审计也失败时，保留原业务异常，并把审计异常追加为 suppressed exception。

## 7. 故障定位

1. `INSUFFICIENT_EVIDENCE`：补充已发布且有权访问的资料，或调整章节目标；不要绕过最低证据门槛。
2. `FAILED_RETRYABLE`：检查可信检索或模型网关可用性，使用原客户端幂等键重试。
3. `FAILED_MANUAL`：检查结构化模型响应、Claim 唯一性和 Evidence 白名单；不要直接写入章节仓储。
4. `CANCELLED`：确认生成期间是否修改了需求、大纲或章节基线；用当前版本发起新请求。
5. 采纳失败：检查核验结果是否包含 `PARTIAL` 或 `UNSUPPORTED`，以及候选版本是否仍属于当前需求和大纲。
6. 定稿失败：按顺序检查确认状态、必填章节、ADOPTED 版本、风险确认、Evidence 当前权限和 `expectedRevision`。
7. 审计或原子提交失败：不得手工移动最终指针；检查真实事务适配器并重试完整业务操作。

错误码和审计只用于关联排查，不应把正文拼进异常消息或日志。

## 8. 本地验证

Windows 验证环境使用 JDK 21：

```powershell
$env:JAVA_HOME = 'C:\Program Files\Zulu\zulu-21'
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"

mvn.cmd -q -pl apps/core-api -Dtest=SolutionWorkbenchCoreAcceptanceTest test
mvn.cmd -q -pl apps/core-api verify
```

纵向验收使用中文虚构数据，覆盖证据不足、引用不支持、修订后通过、高风险阻断、人工确认、Evidence 复核、不可变定稿和新修订失效规则。

## 9. 后续基础设施门槛

进入完整工作台实现前，至少需要：

- PostgreSQL/Flyway 方案、版本、attempt、风险确认和定稿事务适配器；
- OpenSearch 与当前可信检索生产适配器；
- 经审批的模型网关、Prompt 注册、密钥托管、出网代理和费用告警；
- 钉钉身份到六项业务动作的权限映射；
- 生产 HTTP API、Vue 工作台和错误码投影；
- Word 导出、来源预览与短时授权访问；
- 脱敏黄金集、质量阈值、容量测试、备份恢复和故障注入。

当前不得创建 `pilot-retrieval-v1` 或 `pilot-workbench-v1` 标签，也不得接入真实客户资料或宣称方案工作台可投产。
