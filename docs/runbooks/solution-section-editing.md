# 方案章节人工修订与版本对比运行说明

## 使用流程

在已确认大纲并至少采纳一个章节后，打开该章节的“查看版本与人工修订”。有编辑权限的员工可修改纯文本正文并保存为新的不可变人工候选。保存不会替换当前已采纳章节；核对候选正文和原证据后，再单独点击“采纳人工候选”。版本对比可并排查看当前已采纳版本与人工候选，或选择其他有权查看的历史版本。

人工修订保留原生成证据 ID，但正文作为新的事实声明，统一标记 `HUMAN_CONFIRMATION_REQUIRED`。采纳后，有权评审者需在步骤 4 写明确认理由并签认，才能定稿。人工修订不会继承原模型草稿的 `SUPPORTED` 结论。首版仅支持纯文本修订；新增证据、逐条声明编辑和富文本引用标记后续单独设计。

## 接口与并发

| 操作 | 方法与路径 | 关键输入 |
| --- | --- | --- |
| 保存人工候选 | `POST /api/v1/solutions/{solutionId}/sections/{sectionId}/edits` | `Idempotency-Key`、`baselineVersionId`、`expectedDraftVersionId`、`expectedRevision`、`content` |
| 查询最新人工候选 | `GET /api/v1/solutions/{solutionId}/sections/{sectionId}/edits/latest?baselineVersionId=...` | 当前已采纳基线 ID |
| 版本列表 | `GET /api/v1/solutions/{solutionId}/sections/{sectionId}/versions` | 仅返回有权查看的已采纳和人工候选版本，最多 30 条 |
| 版本正文 | `GET /api/v1/solutions/{solutionId}/sections/{sectionId}/versions/{versionId}` | 版本 ID |
| 采纳候选 | `POST /api/v1/solutions/{solutionId}/sections/{sectionId}/versions/{versionId}/adopt` | 原有采纳接口，携带 `expectedRevision` |

第二次保存须携带上一次保存返回的候选 ID。其他人先保存、方案修订号变化或基线被重新采纳后返回 `409`；页面保留本地正文，显示服务器正文供人工比对，不自动覆盖。在基线仍为当前已采纳版本且来源仍可读时，相同客户端键和相同请求可恢复同一候选，即使随后又保存了其他候选；同键改写返回冲突。

每次保存、读取版本和采纳前都重新检查空间权限、需求来源与证据文档的当前发布和授权状态。来源撤权后拒绝访问对应版本。成功保存与审计在同一数据库事务内完成；审计失败则回滚候选。数据库迁移 V014 限制同一候选父版本只能有一个人工后继，以保证修订链线性。

## 验证与限制

在 `E:\git_repo\Elite-RAG` 运行 `scripts/verify.ps1`，或分别运行 Core API Maven 测试、`pnpm --dir apps/web test -- --run` 和 `pnpm --dir apps/web exec playwright test`。自动化验收涵盖并发冲突、候选版本不可变、证据撤权、原始证据链追溯、人工风险签认、Word 导出，以及桌面和窄屏页面。

本地验收不能代替真实审批模型、真实脱敏数据、容量、恢复和安全测试；当前系统仍不具备生产准入。
