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

测试环境通过 CloudBase Node SDK 在云函数中调用 `cloudbase / hy3`、数据库和云存储。Android、Git 与公开接口均不保存或返回服务端密钥。`/health` 已实测返回 `aiReady=true` 和 `attachments=ready`；如运行环境身份策略变化，可在测试函数环境变量中配置 `CLOUDBASE_APIKEY`，代码会优先把它作为 SDK `accessKey`，但不得写入仓库。

当前 `ALLOW_EPHEMERAL_QUOTA=true`，每日 500 次只在单个温实例内准确，冷启动或横向扩容会重新计数。接入持久化 SQL/NoSQL 原子计数后必须删除该变量，才可把 500 次声明为跨实例强约束。

行情生产接入不得使用当前三狐页面抓取：2026-09-07 实测该页面只有 HTTP 可访问，HTTPS 连接失败，且未发现公开官方 API 契约。只有取得数据授权、HTTPS Base URL、鉴权方式、字段字典、交易日历、限流与 SLA 后，才允许新增对应的 `MarketDataProvider`。
