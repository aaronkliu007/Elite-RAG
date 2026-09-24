# 企业级 RAG 交付总路线图

> **面向执行者：** 必须技能：对每份链接的实施计划使用 `superpowers:subagent-driven-development`（推荐）或 `superpowers:executing-plans`。本文档是索引，不是可直接执行的任务清单。

**目标：** 通过四个可独立评审、可独立发布的增量，交付已批准的企业级 RAG 方案生成试点。

**架构：** Vue 3 Web 客户端调用 Java 21/Spring Boot 模块化单体。PostgreSQL 是事实源，MinIO 保存不可变原始文件，OpenSearch 是可重建的检索投影，无状态 Python Worker 封装文档解析/OCR。所有云模型流量都通过 Spring Boot 模型网关出口。

**技术栈：** Java 21、Spring Boot 4.1.1、Spring AI 2.0.1、Maven、PostgreSQL、Flyway、MinIO、OpenSearch、Python、FastAPI、Docling、PaddleOCR、Vue 3、TypeScript、Vite、Vitest、Playwright、Docker。

**设计规格：** `docs/superpowers/specs/2026-09-24-enterprise-rag-solution-generation-design.md`

## 全局约束

- 原始文档、解析内容、索引、权限、方案和审计记录均保留在公司内网。
- 只有模型网关可以访问已审批的云模型 API。
- Java/Spring Boot 负责所有业务规则和 AI 编排；Python 仅作为无状态解析/OCR/模型计算 Worker。
- PostgreSQL 是权威数据源；OpenSearch 可重建，不得成为权限事实源。
- 权限默认拒绝，采用白名单；有效权限是知识空间授权、文档授权和有效发布状态的交集。
- 检索前执行 ACL 过滤，返回证据前再通过 PostgreSQL 复核。
- 生产检索仅使用当前已发布文档版本。
- 需求确认和大纲确认是强制人工门槛。
- 基于事实的陈述必须带引用；证据不足时必须拒绝或标记需人工确认。
- 每次发布必须通过权限和检索质量回归测试。

## 锁定的仓库结构

```text
apps/
  core-api/                 Spring Boot 模块化单体
  web/                      Vue 3 方案与知识管理界面
workers/
  document-parser/          Docling/OCR 无状态 FastAPI Worker
contracts/
  parser/                   Worker 共享的版本化 JSON Schema
infra/
  compose/                  本地 PostgreSQL、MinIO、OpenSearch、模型模拟环境
  opensearch/               索引模板和搜索管线
docs/
  adr/                      架构决策
  runbooks/                 运维和恢复手册
  superpowers/specs/        已批准设计
  superpowers/plans/        可执行阶段计划
pom.xml                     Maven 聚合根工程
pnpm-workspace.yaml         前端工作区声明
```

## 执行顺序与准入门槛

1. [`2026-09-24-knowledge-foundation.md`](2026-09-24-knowledge-foundation.md)
   - 交付钉钉身份边界、ACL、不可变文档、解析、编目、发布、审计和最小 Web 界面。
   - 退出门槛：授权用户能发布并查看文档；未授权用户无法获得元数据、文件和解析内容。
2. [`2026-09-24-trusted-retrieval.md`](2026-09-24-trusted-retrieval.md)
   - 交付 OpenSearch 投影、混合检索、RRF、重排、引用、ACL 二次校验和离线评估。
   - 退出门槛：权限测试 100% 通过，达到批准的检索与引用基线。
3. [`2026-09-24-solution-workbench.md`](2026-09-24-solution-workbench.md)
   - 交付需求确认、模板大纲、分章生成、引用核验、编辑、版本、Word 导出和反馈。
   - 退出门槛：试点用户能完成从需求到 Word 的全流程，且证据可追溯。
4. [`2026-09-24-pilot-hardening.md`](2026-09-24-pilot-hardening.md)
   - 交付安全、可观测性、恢复、负载测试、运维手册和试点验收报告。
   - 退出门槛：安全、恢复、性能和业务验收全部通过。

## 依赖规则

- 后续阶段只能使用前置阶段计划明确产出的接口。
- 不得为了让演示更完整，将后续阶段功能提前塞入前置阶段。
- 如需修改前置接口，必须先更新其计划契约、添加兼容性测试并提交，再由后续阶段消费。
- 每个阶段进入下一阶段前，都要经过独立评审并创建可发布标签。

## 建议标签

- `pilot-foundation-v1`
- `pilot-retrieval-v1`
- `pilot-workbench-v1`
- `pilot-ready-v1`
