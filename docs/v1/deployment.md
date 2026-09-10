# CloudBase 测试环境部署

## 边界

只允许部署到 `stockai-test-d6gd0ho1z97f0bbde`。禁止把本配置用于生产环境；禁止在 Android、Git、文档或聊天中保存服务端密钥。

## 部署命令

```bash
cd /Users/jerry/JustDoIt/OHHHHH/TalkToAI
cd backend/functions/talktoai-api && npm test && npm run build && cd ../../..
tcb fn deploy talktoai-api --force
curl -fsS 'https://stockai-test-d6gd0ho1z97f0bbde.service.tcloudbase.com/talktoai/health'
```

网关只开放 HTTPS 路由 `/talktoai`，总 QPS 100、单客户端 IP QPS 5。匿名请求的 AI 日配额为每安装每天 500 次，按 `Asia/Shanghai` 自然日重置。

## AI 与存储身份

测试环境通过 CloudBase Node SDK 调用 `cloudbase / hy3`、数据库和云存储，也可由同一函数通过 HTTPS 调用 DeepSeek。Android、Git 与公开接口均不保存或返回服务端密钥。`/health` 只公开已配置模型的非敏感 ID；如运行环境身份策略变化，可在测试函数环境变量中配置 `CLOUDBASE_APIKEY`，代码会优先把它作为 SDK `accessKey`，但不得写入仓库。

DeepSeek 只读取测试函数环境变量 `DEEPSEEK_API_KEY`。必须在 CloudBase 控制台的函数环境变量或一次性未跟踪部署配置中设置；禁止写入 `cloudbaserc.json`、`.env`、Gradle 配置、APK、文档、测试夹具或终端历史。凭证管理页面显示的记录 ID 不是聊天接口参数，不进入运行时代码。部署后先用 `/health` 确认可用模型目录；由于真实生成会消耗模型额度，在没有费用授权时只执行 Mock 契约测试，不发起线上生成。

当前 `ALLOW_EPHEMERAL_QUOTA=true`，每日 500 次只在单个温实例内准确，冷启动或横向扩容会重新计数。本轮曾关闭该回退并部署测试：HTTP 聊天因运行时数据库身份不足返回 500；因此已恢复可工作的内存配额，未把失败配置留在测试环境。接入持久化 SQL/NoSQL 原子计数后必须删除该变量，才可把 500 次声明为跨实例强约束。

配额表与原子函数迁移位于 `backend/database/001_talktoai_quota.sql`。迁移已在测试 PostgreSQL 执行；切换到 `ALLOW_EPHEMERAL_QUOTA=false` 前，还必须让 HTTP 云函数具备调用 `$runSQL` 的服务端身份并通过远端 1/500/501 与并发验收。

行情生产接入不得使用当前三狐页面抓取：2026-09-07 实测该页面只有 HTTP 可访问，HTTPS 连接失败，且未发现公开官方 API 契约。只有取得数据授权、HTTPS Base URL、鉴权方式、字段字典、交易日历、限流与 SLA 后，才允许新增对应的 `MarketDataProvider`。

## 零预算开发数据源

测试函数默认只使用固定测试行情。配置 `TUSHARE_TOKEN` 后，日线、周线和月线优先读取 Tushare `daily` 未复权接口；周线和月线由后端按日线聚合。该来源属于个人开发研究用途，返回状态最多为 `DELAYED`，不得据此声明实时行情或生产可用。

AKShare 只作为第二补充源。由于 AKShare 是 Python 库而不是托管 API，Node.js 函数仅在配置 `AKSHARE_HTTPS_BASE_URL` 后访问自行部署并由 HTTPS 反向代理保护的 AKTools。不得配置 HTTP 地址，也不得将 AKShare 当成唯一数据源。两个开发源都失败或请求分时数据时，后端回退到固定测试行情。

| 环境变量 | 是否必需 | 用途 | 安全边界 |
|---|---|---|---|
| `TUSHARE_TOKEN` | 否 | 启用 Tushare 日线开发源 | 仅存 CloudBase 测试函数环境，不进入 APK、Git、健康接口或日志 |
| `AKSHARE_HTTPS_BASE_URL` | 否 | 启用 AKTools 补充源 | 必须为 HTTPS，且 URL 不允许嵌入用户名或密码 |

参考资料：[Tushare A股日线接口](https://tushare.pro/document/1?doc_id=27)、[Tushare 数据服务协议](https://tushare.pro/document/1?doc_id=405)、[AKShare 项目说明](https://github.com/akfamily/akshare/blob/main/docs/introduction.md)、[AKTools](https://github.com/akfamily/aktools)。
