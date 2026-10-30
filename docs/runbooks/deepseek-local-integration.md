# DeepSeek 官方 API 受控联调说明

本文用于在隔离测试环境中，以虚构或获准脱敏的材料联调现有问答及方案工作台；不代表已通过生产准入。原始文档、解析内容、索引和业务数据仍保存在内网，只有经过现有鉴权、范围控制和脱敏处理的最小上下文可发送至经审批的模型接口。

## 1. 准备密钥

由密钥所有者通过公司认可的方式获取 DeepSeek 官方 API 密钥。将密钥以纯文本保存在仓库外的 `E:\git_secrets\Elite-RAG\deepseek-api-key.txt`，文件只包含密钥；限制 Windows 文件读取权限，只授权实际运维账号。不要把密钥发到聊天、工单或日志，也不要提交到 Git。

问答审计还需要一个独立且稳定的问题摘要 HMAC 密钥（至少 32 个 UTF-8 字节），不能使用 API 密钥代替。可由管理员在同一仓库外密钥目录保存为 `qa-question-hmac.key`。该密钥必须长期安全保存：重启时使用同一密钥，不得随意更换或打印。

如果该文件尚不存在，可由密钥管理员在自己的 PowerShell 中运行以下命令创建随机密钥；已有文件会保留。目录应事先由管理员创建并限制读取权限。

```powershell
$ragQaHmacPath = 'E:\git_secrets\Elite-RAG\qa-question-hmac.key'
if (-not (Test-Path -LiteralPath $ragQaHmacPath)) {
    $ragRandomBytes = New-Object byte[] 32
    $ragRng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try { $ragRng.GetBytes($ragRandomBytes) } finally { $ragRng.Dispose() }
    [System.IO.File]::WriteAllText($ragQaHmacPath, [Convert]::ToBase64String($ragRandomBytes))
    [Array]::Clear($ragRandomBytes, 0, $ragRandomBytes.Length)
    Remove-Variable ragRandomBytes, ragRng
}
```

## 2. 准备运行环境

按 [README](../../README.md) 使用项目目录 `E:\git_repo\Elite-RAG` 启动隔离测试基础设施，并在启动 Core API 的同一个 PowerShell 窗口中按 README 导入数据库和对象存储的应用账号配置。不要启动云模型调用来验证真实资料；先使用虚构材料，并确认出站与数据策略已审批。

在启动 Core API 前执行以下配置，`Get-Content` 的结果只赋值给进程变量，不会显示密钥：

```powershell
Set-Location 'E:\git_repo\Elite-RAG'
$ragDeepSeekKeyPath = 'E:\git_secrets\Elite-RAG\deepseek-api-key.txt'
$ragQaHmacPath = 'E:\git_secrets\Elite-RAG\qa-question-hmac.key'
if (-not (Test-Path -LiteralPath $ragDeepSeekKeyPath -PathType Leaf)) { throw 'DeepSeek 密钥文件不存在' }
if (-not (Test-Path -LiteralPath $ragQaHmacPath -PathType Leaf)) { throw '问答 HMAC 密钥文件不存在' }
$env:RAG_AI_API_KEY = (Get-Content -LiteralPath $ragDeepSeekKeyPath -Raw).Trim()
$env:RAG_QA_QUESTION_HMAC_KEY = (Get-Content -LiteralPath $ragQaHmacPath -Raw).Trim()
if ([string]::IsNullOrWhiteSpace($env:RAG_AI_API_KEY)) { throw 'DeepSeek 密钥文件为空' }
if ([Text.Encoding]::UTF8.GetByteCount($env:RAG_QA_QUESTION_HMAC_KEY) -lt 32) { throw '问答 HMAC 密钥长度不足 32 字节' }

$env:RAG_AI_ENABLED = 'true'
$env:RAG_AI_PROVIDER = 'deepseek'
$env:RAG_AI_CONFIG_VERSION = 'deepseek-flash-test-v1'
$env:RAG_AI_ENDPOINT = 'https://api.deepseek.com'
$env:RAG_AI_APPROVED_HOSTS = 'api.deepseek.com'
$env:RAG_AI_CHAT_MODEL = 'deepseek-flash'
$env:RAG_AI_EMBEDDING_MODEL = ''
$env:RAG_AI_RERANKER_MODEL = ''
$env:RAG_VECTOR_ENABLED = 'false'
$env:RAG_AI_CONNECT_TIMEOUT = 'PT3S'
$env:RAG_AI_RESPONSE_TIMEOUT = 'PT40S'
$env:RAG_AI_TOTAL_TIMEOUT = 'PT55S'
$env:SPRING_PROFILES_ACTIVE = 'local'
.\mvnw.cmd -pl apps/core-api spring-boot:run
```

此配置仅启用聊天模型。未配置向量和重排模型时，检索使用全文并明确报告能力降级；如果启用向量检索但没有配置 Embedding 模型，应用拒绝启动。不要将 `deepseek-flash` 填入 Embedding 或 Reranker 字段。并发、Token 限额和费用配置使用现有安全策略，按审批的测试预算调整。

官方接口资料：[模型标识](https://api-docs.deepseek.com/api/list-models/)、[聊天接口与 JSON 格式](https://api-docs.deepseek.com/api/create-chat-completion/)。本系统使用该接口的 JSON 对象模式及非思考模式，并在服务端继续验证字段、引用与权限。实际模型效果、费用和响应时间必须通过真实调用验证。

## 3. 联调与退出

1. 确认基础设施 readiness、模型配置及出站策略；不要输出环境变量、密钥或完整提示词。
2. 在隔离空间使用虚构文档，先测试有依据问答、无依据拒答，再测试需求候选和章节生成；人工核对来源和结果。
3. 记录模型配置版本、调用结果码、耗时、用量和人工验收结果；不得把一次调用成功当作质量或生产准入证明。
4. 完成后停止 Core API，并在当前 PowerShell 进程中移除 `$env:RAG_AI_API_KEY` 和 `$env:RAG_QA_QUESTION_HMAC_KEY`；不要删除持久保存的 HMAC 密钥。

真实业务黄金集、复杂图片与扫描件 OCR、混合检索、性能与成本仍需分别验证。现有模拟网关和合成文件解析测试不能代替这些验收。
