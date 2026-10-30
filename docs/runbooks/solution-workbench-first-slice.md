# 方案工作台第一条纵向闭环运行手册

## 范围与状态

本手册覆盖“空间授权 → 创建方案 → 录入、修订需求 → 异人确认需求 → 编写、修订大纲 → 异人确认大纲”的人工闭环。方案、不可变版本、确认、审计及精简幂等结果保存在 PostgreSQL；重新启动 Core API 后从数据库恢复。当前仅供虚构数据受控验收，**尚未生产准入**。分章生成、风险确认、定稿和 Word 导出的后续能力及限制见仓库 README 和相应运行手册；真实钉钉同步仍未交付。

一家公司独立部署一套实例。Python Worker 不参与本手册所述的人工方案编辑；人工编辑和确认命令不调用模型，自动生成候选的运行边界见[需求候选运行说明](solution-model-proposals.md)。

## 启动与配置

按仓库 [README](../../README.md) 准备 JDK 21、Docker Desktop、Node.js/Corepack、PostgreSQL 及仓库外的 AIStor 许可证，先启动基础设施，再以 `local` profile 启动 Spring Boot Core API，最后启动 Vue。Core API 使用迁移账号执行 Flyway V008/V009，再由独立的运行账号访问业务表；V009 为已有大纲版本补上“历史版本，原因未记录”，新写入必须提供实际原因。不得将迁移账号、数据库密码、许可证或真实客户资料提交到仓库。

方案工作台默认要求编辑者与确认者是不同用户。配置项 `rag.solution.review.allow-self-confirmation` 默认 `false`；受控验收和正常部署保持默认值。若企业策略需要改变，须先完成独立的权限和审计评审，不能仅通过前端按钮修改。

```powershell
Set-Location 'E:\git_repo\Elite-RAG'
docker compose --env-file infra/compose/.env -f infra/compose/compose.yaml up -d --wait
# 按 README 的“Core API 本地启动与健康检查”导入应用账号变量。
$env:SPRING_PROFILES_ACTIVE = 'local'
.\mvnw.cmd -pl apps/core-api spring-boot:run
```

另一终端启动页面：

```powershell
Set-Location 'E:\git_repo\Elite-RAG'
corepack pnpm --dir apps/web dev
```

检查 `http://127.0.0.1:8080/actuator/health/readiness` 为 `UP`，再使用已有登录流程访问 `/solutions`。空间列表与方案详情按当前 Session、空间 ACL、角色和来源授权实时计算。编辑需要空间 `EDITOR` 或 `SPACE_ADMIN`；确认需要 `REVIEWER_PUBLISHER` 或 `SPACE_ADMIN`，且确认人不能是该版本的编辑者。系统管理员仍需空间读取 ACL。

## 操作与结果

1. 在有权知识空间创建方案，录入需求分类、文本及来源。已知事实必须关联同空间、当前已发布且本人可读的文档版本和受支持的定位；暂时无法证实的需求可标记为 `UNKNOWN`。
2. 保存需求后，由另一位有权用户核对页面展示的需求版本并单独确认。确认后才能编辑大纲。
3. 编辑大纲章节、层级、顺序、必填标志、目标、检索提示和风险类型，填写非空修改原因，再由另一位有权用户核对大纲版本并单独确认。
4. 需求再次修订会重置需求及大纲确认，须重新走人工确认。编辑时页面使用当前修订号；旧修订号提交返回 `409`，应重新读取详情并人工合并，不能自动覆盖。

需求来源显示保存的文档 ID、版本和定位（页码、幻灯片、表格行列或 Markdown 标题）。可在新标签页打开文档，访问时仍由服务器重新鉴权。该入口打开的是文档当前版本，不会自动跳到保存的位置；审阅时应人工核对显示的版本和位置。来源换版或撤权后按下述规则隐藏历史内容并要求重新核对。

浏览器为每次写请求提交 `Idempotency-Key`；重复提交同一键和相同内容只返回首次已提交的版本引用，改内容复用旧键返回 `409`。创建、修订与确认的 `POST`/`PUT` 必须带已有 Session 和 CSRF 凭据。方案列表按空间分页，详情按方案 ID 获取；无权或不存在统一返回 `404`。权限依赖不可用返回 `503`，流程前置条件不满足返回 `422`。保存期间编辑器暂时锁定；未保存草稿在路由离开、刷新或关闭时提示。错误后保留页面草稿，由用户检查权限、来源和版本号后重试。

## 撤权、恢复与故障

撤销空间 ACL 后，该用户不能读取方案或重放已完成命令。撤销文档来源 ACL、归档文档或使来源版本不再是当前已发布版本后，详情不返回需求与大纲正文，历史确认显示为无效；已完成命令的重放返回无正文的 `IDEMPOTENT_RESULT_UNAVAILABLE` 冲突。恢复授权后必须重新读取并核对当前版本，不能把旧页面视为有效确认。

重启 Core API 不应清空 PostgreSQL。重启后按原方案 ID 查询，确认当前需求/大纲版本 ID、修订号和确认记录；再以原幂等键重放，核对结果引用一致。若 Flyway V008/V009 校验失败，停止接流量并核对迁移历史，勿直接编辑已执行迁移或手工改版本指针。恢复数据时使用同一时点的 PostgreSQL 备份，先在隔离环境验证迁移、版本关系、ACL、审计和幂等表，再开放服务。AIStor 文档来源及 OpenSearch 检索能力按各自运行手册恢复。

排障顺序：检查 readiness、数据库连接和 Flyway 状态；确认 Session/CSRF；检查空间 ACL、角色和当前文档版本；最后检查方案修订号与审计事件。日志、截图和工单仅使用虚构或脱敏数据，不记录请求正文、许可证或凭据。不得为排障绕过服务端鉴权或直接修改数据库业务表。

## 验证与生产准入边界

```powershell
$env:JAVA_HOME = 'C:\Program Files\Zulu\zulu-21'
.\mvnw.cmd -pl apps/core-api clean verify
corepack pnpm --dir apps/web test --run
corepack pnpm --dir apps/web build
corepack pnpm --dir apps/web exec playwright test
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/verify.ps1
```

`PostgresSolutionWorkbenchAcceptanceTest` 使用真实 PostgreSQL Testcontainers 和虚构数据验证创建、双人确认、新连接恢复、修订冲突及撤权；HTTP 安全集成测试和浏览器测试分别覆盖接口边界与页面路径。门禁未运行或被环境阻断必须逐项记录，不能写成通过。生产准入仍需真实审批模型联调、真实脱敏黄金集、容量/费用/灾备/渗透测试、备份恢复演练和业务验收；不得创建试点标签。
