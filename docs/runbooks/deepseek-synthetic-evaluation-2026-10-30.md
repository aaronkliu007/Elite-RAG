# DeepSeek 合成资料联调记录（2026-10-30）

## 范围与结论

使用 DeepSeek 官方 API 的 `deepseek-flash`、真实 PostgreSQL、AIStor 和 OpenSearch，以及隔离的虚构数据进行受控验证。**当前黄金集质量门槛未通过，不具备生产准入依据。** 本记录不包含客户资料或模型密钥；结果只反映此次合成样例和当时的模型服务，不能推断真实业务质量或稳定复现相同答案。

## 已验证

- 模型接口鉴权成功；项目适配器分别完成虚构的有依据问答、文档需求提取和章节生成。
- 真实数据库和检索链路中，有依据问答返回经服务端引用核验的答案；无证据问题在模型调用前拒答。文档候选可生成、核对来源并采纳，撤权后再次采纳被拒绝。
- 检索采用 BM25，未配置 Embedding 和 Reranker，结果带降级状态；没有验证混合检索。

## 50 条合成黄金样例

首次运行的工程门槛虽然通过，但 12 条应拒答案例中只有 11 条正确拒答。未拒答案例的两份资料表达了同一方案已批准和未批准；检索同时找到了两份资料，旧冲突检测未识别这类表述。

增加明确审批状态冲突检测后重跑：

| 指标 | 结果 | 解释 |
| --- | --- | --- |
| 已授权期望证据召回 | 38/42（约 90.5%） | 统计最终鉴权和选择后的前 10 条 |
| 应拒答案例正确拒答 | 12/12 | 冲突检测使 50 条问题全部拒答，不能据此推断拒答准确率普遍达标 |
| 越权泄漏 | 0 | 仅覆盖本次虚构案例和观测范围 |
| 引用有效性、声明支持门槛 | 未通过 | 38 条应回答案例均被拒答，没有形成可供验证的正常回答 |

根因是这套相似度很高的合成资料中，两个互相矛盾的片段混入了其他问题的候选证据；冲突门禁对整组证据执行，因而发生过度拒答。冲突门禁前拒答的请求不会调用模型。不得通过关闭冲突检测、忽略冲突或放宽拒答条件来宣称通过；后续需要提高面向问题的检索相关性，并用真实脱敏样本评估。

评测观察的召回标识已改为在冲突拒答前记录经过最终鉴权的前 10 条证据身份，不保存正文；历史评测结果不会自动重算。引用编号有效也不等同于语义支持，真实业务结论仍需人工抽检。

## 不调用模型的检索诊断

在保留规范化去重修复后，使用隔离的 PostgreSQL、AIStor 和 OpenSearch 对相同的 50 条合成样例重新诊断，不连接 DeepSeek：

- 38 条应回答问题中，38 条都在最终证据中同时召回了那两份审批冲突材料；冲突检测在这 38 条上均触发。最终证据数量均达到当前问答最低数量 8。
- 42 条标注的期望证据中，最终前 10 条召回 38 条。38 条应回答问题的期望证据标注数都少于 8；**标注数不是完整相关证据数**，不能由此推断实际只有这么多相关证据。
- 试验性地将全文检索改成所有分词必须匹配后，38 条应回答问题的原始命中数全部少于 8。该对照只检查 OpenSearch 原始命中，尚未经过最终数据库鉴权和证据选择，不能当作完整问答质量结果。
- 在独立的虚构预算反例中，宽松检索可找出金额分别为 100 万元、120 万元的两条材料；全词必须匹配时两条均未命中。因此不能以此作为生产修复，否则可能把真实冲突一并漏掉。黄金集冲突标签对应的 4 个问题在此次全词匹配原始结果中仍找到两份冲突材料，但不代表该方案对其他表述安全。

运行以下命令会创建临时测试容器，不需要模型密钥，也不调用模型：

```powershell
$env:RAG_GOLDEN_DIAGNOSTIC = 'true'
try {
    .\mvnw.cmd -B -pl apps/core-api -am '-Dtest=PostgresOpenSearchRetrievalAcceptanceTest#diagnosesGoldenRetrievalWithoutCallingModel+goldenConflictCaseRetrievesBothSidesAndRefusesConflictingEvidence+strictLexicalAndCanMissBothSidesOfARealConflict' '-Dsurefire.failIfNoSpecifiedTests=false' test
} finally {
    Remove-Item Env:RAG_GOLDEN_DIAGNOSTIC -ErrorAction SilentlyContinue
}
```

诊断输出 `SYNTHETIC_RETRIEVAL_DIAGNOSTIC` 只包含汇总数量；测试通过表示诊断和反例成功运行，**不表示黄金集质量门槛通过或过度拒答已经解决**。下一步需补充问题与证据相关性的独立标注及覆盖对照，比较候选方案的相关冲突召回、误拒答和不足证据拒答，不应仅针对这套合成资料调参。

## 复现边界

`DeepSeekLiveIntegrationTest` 和 `PostgresOpenSearchRetrievalAcceptanceTest` 的真实模型用例默认关闭。显式设置 `RAG_DEEPSEEK_LIVE_TEST=true` 才执行小样本真实模型测试；显式设置 `RAG_DEEPSEEK_GOLDEN_TEST=true` 才执行 50 条黄金样例。两者读取进程内的 `RAG_AI_API_KEY`，每次执行可能产生费用。基础设施验收使用临时 Testcontainers，不能指向真实业务数据；密钥注入方法见 [DeepSeek 联调说明](deepseek-local-integration.md)。

黄金样例用例用于收集结果，即使质量门槛失败，测试进程也可能正常结束；必须检查输出中的 `gate` 和失败项目，不能把 `BUILD SUCCESS` 解释成评测达标。该用例本身不是发布脚本或上线门禁。
