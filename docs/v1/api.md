# TalkToAI V1 HTTP 接口草案

Base URL 由 Android `BuildConfig` 注入，正式测试环境必须为 HTTPS。所有响应包含 `requestId`。

## POST /v1/chat/completions

请求：`installationId`、`conversationId`、`messages[]`、`attachments[]`、`stream=true`。附件仅传已上传对象的受控引用与 MIME 元数据，不内联文件正文。

成功：`text/event-stream`，事件类型为 `meta`、`delta`、`citation`、`done`。`citation` 仅由模型正文中实际出现且可解析的 HTTPS URL 生成；没有链接时客户端明确提示没有可核验外部引用。客户端以 `done` 结束；断开连接即取消上游生成。

错误：JSON `{ "error": { "code", "message", "retryable", "resetAt"? }, "requestId" }`。稳定错误码：`INVALID_ARGUMENT`、`UNAUTHORIZED_INSTALLATION`、`AI_NOT_CONFIGURED`、`DAILY_QUOTA_EXCEEDED`、`UPSTREAM_TIMEOUT`、`UPSTREAM_UNAVAILABLE`、`INTERNAL_ERROR`。

## GET /v1/market/quote?symbol=600000.SH

返回标准化报价、`source`、`marketTime`、`fetchedAt`、`freshness` 和 `freshnessReason`。

## GET /v1/market/bars

参数：`symbol`；`period=intraday|day|week|month`；自定义区间可带 `from`、`to`。返回 OHLCV；分时数据的 OHLC 可相同。所有时间为带偏移的 ISO-8601。

## PUT /v1/attachments/{contentSha256Prefix}

请求头包含 `X-Installation-Id`、URL 编码的 `X-File-Name` 和受支持的 `Content-Type`，请求体为原始文件字节。仅接受 JPEG、PNG、WebP、GIF、CSV 和 TXT；图片最大 10MB，CSV/TXT 最大 2MB。服务端按安装标识哈希隔离对象路径并返回 `cloud://` 对象引用，不返回公开下载地址。测试环境未配置 CloudBase 服务端凭证时返回 503 `ATTACHMENT_STORAGE_NOT_CONFIGURED`。

## GET /health

不访问付费或第三方上游；只返回进程、版本和 `aiReady`、`marketReady`、`marketProvider`、附件存储状态，不返回环境变量或凭证。
