# 方案工作台分章生成接口运行说明

## 当前范围

本阶段交付已确认需求和大纲后的章节生成、证据核验、尝试状态读取及人工采纳接口。生成尝试和不可变章节版本保存在 PostgreSQL；草稿与成功状态同事务提交。章节不会自动采纳。Vue 操作页、定稿与 Word 导出仍按后续阶段实施。

Core API 使用 `local` profile。分章生成需要启用 OpenSearch 检索；模型调用仅通过已审批的 `rag.ai` 网关。若未启用模型网关，生成尝试会记录可重试的模型不可用状态。Flyway V010/V011/V012 分别建立生成尝试、章节版本和证据来源快照结构，运行账号不应执行迁移。

## 接口

所有接口都需要已登录 Session；写请求还需要 CSRF 凭据。编辑者或空间管理员须同时拥有空间读取权。服务端在模型调用前后检查当前需求来源和检索证据对应文档版本的可读状态。

| 操作 | 方法与路径 | 输入 | 结果 |
| --- | --- | --- | --- |
| 发起或重试生成 | `POST /api/v1/solutions/{solutionId}/sections/{sectionId}/generation-attempts` | `Idempotency-Key`，`expectedRevision`，可选 `baselineSectionVersionId` | 尝试状态；成功时含草稿正文、声明及核验结果 |
| 读取尝试 | `GET /api/v1/solutions/{solutionId}/sections/{sectionId}/generation-attempts?clientKey=...` | 原客户端键 | 当前尝试状态；成功且证据仍可访问时含草稿 |
| 人工采纳 | `POST /api/v1/solutions/{solutionId}/sections/{sectionId}/versions/{sectionVersionId}/adopt` | `Idempotency-Key`，`expectedRevision` | 新方案修订号及当前版本引用 |

生成请求的 `expectedRevision` 应取方案详情中的最新修订号；`baselineSectionVersionId` 应与该章节当前已采纳版本一致，首次生成传 `null`。同一个客户端键重试相同输入会复用原尝试；变更输入须使用新键，旧键配新输入返回 `409`。采纳前人工核对正文、引用、量化值和核验结论，再提交最新修订号。来源失效或无权统一返回 `404`；修订冲突返回 `409`；前置状态不满足返回 `422`。

`PENDING`、`RETRIEVING`、`GENERATING`、`VERIFYING` 是进行中状态；`SUCCEEDED` 表示生成草稿成功，仍需人工采纳。`INSUFFICIENT_EVIDENCE`、`FAILED_MANUAL`、`CANCELLED` 是终态。`FAILED_RETRYABLE` 可用原客户端键重试；默认执行上限为 3 次，超过后转为人工处理。进程中断后的活跃尝试默认在 2 分钟租约届满后按持久化状态恢复或终止。

## 撤权与故障

首次生成时仅持久化证据的来源标识和摘录哈希，不保存摘录正文。读取成功草稿及人工采纳时，按来源快照检查当前已发布文档版本及读取权限；撤权或版本切换后按失败关闭处理。模型调用失败后的重试可重新检索，但必须匹配首次快照中的文档、版本、节点及摘录哈希，并复用原证据 ID；来源变化时转人工处理。

模型响应只能包含带证据 ID 的结构化声明。服务端重新构建 Markdown，并检查引用白名单和量化值；无法精确证实的声明标记为需人工确认。日志和审计仅记录状态、标识与脱敏错误码，不记录提示词、客户正文、许可证或模型密钥。

验证命令：

```powershell
Set-Location 'E:\git_repo\Elite-RAG'
$env:JAVA_HOME = 'C:\Program Files\Zulu\zulu-21'
.\mvnw.cmd -pl apps/core-api -am test
```

生产准入仍需与实际审批网关联调，并完成脱敏黄金集、撤权、并发、费用和恢复演练；本阶段的本地自动化测试不能替代这些验收。
