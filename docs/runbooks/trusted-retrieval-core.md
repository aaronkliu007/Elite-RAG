# 可信检索内核运行手册

## 1. 适用范围

本手册用于验证和排查基础设施中立的可信检索内核。当前实现只包含纯 Java 领域契约、全文/向量召回端口、RRF 融合、可降级重排、返回前权威授权、证据多样性和脱敏审计。

本手册主要说明检索内核，具体环境中的 PostgreSQL、OpenSearch 和模型配置及联调状态应结合相应运行手册核实，不能仅凭本手册判定已具备生产条件。

## 2. 数据流与安全边界

一次检索严格按以下顺序执行：

1. 使用服务端 `Clock` 记录开始时间；
2. 使用 `PrincipalResolverPort` 展开用户当前有效主体，且主体集合必须包含该用户自身；
3. 构造不可变 `RetrievalFilter`，强制目标空间、`currentPublishedOnly=true`、主体集合和服务端时间；
4. 在共同截止时间内并行调用全文和向量召回端口；
5. 验证端口结果并执行确定性 RRF 融合；
6. 对融合候选执行可选重排，异常或非法结果完整回退 RRF；
7. 使用第二个服务端时刻批量调用 `RetrievalAuthorizationPort`，重新确认当前空间、发布版本、归档、ACL 和有效期；
8. 只对权威授权通过的候选执行规范化后完全相同文本的去重、单文档上限和最终数量选择；

9. 生成不透明 Evidence ID，记录成功审计后返回证据包。

规范化后文本不同的片段会保留：否定词或数值的微小差别可能改变结论。此策略可能让内容相似的片段占用更多证据名额；召回数量、单文档上限和最终证据数量仍有限，因此不保证发现所有冲突，也不解决无关冲突材料被召回的问题。

召回阶段的 ACL 过滤只是第一道隔离。最终授权必须读取权威源的当前状态，不能复用召回时的主体快照。授权源异常、返回 null 或返回请求集合之外的版本时，整次请求失败关闭。

## 3. 默认策略

| 参数 | 默认值 |
|---|---:|
| 全文召回上限 | 50 |
| 向量召回上限 | 50 |
| 融合候选上限 | 30 |
| RRF `k` | 60 |
| 单文档证据上限 | 3 |
| 旧中文 3-gram Jaccard 阈值 | 0.85，兼容字段，不参与去重 |
| 证据请求范围 | 8–15 |
| 召回共同截止时间 | 1200 ms |
| 重排截止时间 | 800 ms |
| Evidence 摘要上限 | 1200 个 Unicode code point |

所有策略集中在不可变 `RetrievalPolicy`，适配器不得复制或覆盖这些业务规则。

## 4. 降级与失败语义

- 全文单路异常、超时或非法结果：使用向量分支并标记 `LEXICAL_UNAVAILABLE`；
- 向量单路异常、超时或非法结果：使用全文分支并标记 `VECTOR_UNAVAILABLE`；
- 两路都不可用：抛出 `RetrievalUnavailableException`，不返回空包伪装成功；
- 两路使用同一候选 ID 却返回冲突的文档、版本、节点、标题、正文或定位：整体失败；
- 重排异常、超时、未知/重复/缺失 ID 或非有限分数：完整回退 RRF 并标记 `RERANKER_UNAVAILABLE`；
- 主体解析或返回前授权异常：抛出 `RetrievalAuthorizationUnavailableException`，不生成证据；
- 权威授权明确拒绝全部候选：返回成功的空证据包；
- 成功审计写入失败：不返回未审计的成功结果；失败审计写入失败时保留原业务异常，并把审计异常作为 suppressed exception。

## 5. 审计字段

`RETRIEVE` 事件只允许以下元数据：

- `requestId`、`spaceId`；
- 查询文本的 Unicode 码点长度 `queryCodePoints`（不保存可离线碰撞的查询摘要）；
- `lexicalCount`、`vectorCount`、`fusedCount`；
- `reauthorizedCount`、`selectedCount`；
- `degradationReasons`、`durationMs`。

查询正文、候选正文、Evidence 摘要、主体集合、供应商错误正文和异常堆栈不得进入审计元数据或业务异常消息。

## 6. 本地验证

Windows 当前验证环境使用 Azul JDK 21 和 Maven 3.9.11：

```powershell
$env:JAVA_HOME = 'C:\Program Files\Zulu\zulu-21'
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
$env:MAVEN_OPTS = '-Dmaven.repo.local=C:\Users\NO.1\.m2\repository'

mvn.cmd -q -pl apps/core-api -Dtest=TrustedRetrievalAcceptanceTest test
mvn.cmd -q -pl apps/core-api verify
```

第一条命令运行纯内存纵向验收，第二条命令运行全部 Java 测试并生成 JAR。验收夹具全部位于 `src/test`，不会作为生产内存仓储或绕过安全边界的后门发布。

## 7. 故障定位

1. 查看 EvidencePack 的 `degradationReasons` 和各阶段数量，先判断是单路降级、重排回退还是授权后归零；
2. 使用 `requestId` 关联审计事件，`queryCodePoints` 仅用于长度分布排查，不在日志中搜索原始查询正文；
3. `lexicalCount=0` 或 `vectorCount=0` 且存在对应降级原因时，检查端口异常、结果契约和超时；
4. `fusedCount>0` 且 `reauthorizedCount=0` 时，检查当前发布指针、归档状态、ACL、组织成员关系和有效期；
5. `reauthorizedCount>selectedCount` 时，检查规范化后的相同文本、单文档 3 条上限和请求数量；`nearDuplicateThreshold` 暂为兼容保留，不再用于删除文本不同的证据；
6. 授权或主体异常一律按安全故障处理，不通过关闭复核、复用旧主体或返回部分结果临时绕过。

## 8. 后续门槛

只有以下能力全部完成并通过负样本和故障注入后，才可以评估创建 `pilot-retrieval-v1`：

- PostgreSQL/Flyway、发布事务 Outbox 和真实批量授权；
- OpenSearch 全文/向量索引、可重建投影和别名切换；
- 经审批的 Embedding/Reranker 网关、密钥托管和出网审计；
- 引用 API、来源查看器和短时授权预览；
- 批准的脱敏黄金集、离线质量基线和性能容量报告。

当前明确禁止创建 `pilot-retrieval-v1` 标签。
