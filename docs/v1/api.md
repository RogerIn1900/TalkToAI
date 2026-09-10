# TalkToAI V1 HTTP 接口草案

Base URL 由 Android `BuildConfig` 注入，正式测试环境必须为 HTTPS。所有响应包含 `requestId`。

## POST /v1/chat/completions

请求：`installationId`、`conversationId`、`model`、`messages[]`、`attachments[]`、`stream=true`。`model` 仅接受 `hy3` 或 `deepseek-v4-flash`；旧客户端未传时兼容为 `hy3`。附件仅传已上传对象的受控引用与 MIME 元数据，不由 Android 内联文件正文。服务端重新校验对象归属、声明大小和真实字节数；CSV/TXT 最多抽取 64 KiB 作为不可信上下文，图片转换成仅供支持多模态的 CloudBase 模型调用的 data URL 内容块。当前 DeepSeek 接入是文本模型，选择它时图片附件返回明确错误且不消耗 AI 日配额。

成功：`text/event-stream`，事件类型为 `meta`、可选 `market`、`delta`、`citation`、`done`。当最后一条用户消息命中行情意图时，服务端先调用只读 `MarketDataProvider`，并保证 `market` 事件早于首个 `delta`；该事件包含标准化 OHLCV、来源、数据时间、获取时间、新鲜度和原因，客户端据此先展示图表。`citation` 可来自模型正文中的 HTTPS URL，也可为 `{kind:"attachment", label:"文件名"}` 的附件来源。客户端以 `done` 结束；断开连接即取消上游生成。

模型正文传输协议仍为 Markdown 文本，不是 JSON 或 XML。Android 端使用 KuiklyMarkdown 渲染完成态回答；满足数值要求的 Markdown 表格额外转换为结构化图表（默认折线，可切换柱状，非负单序列才允许饼图）。只有用户明确要求原始结构化数据时，模型才可返回 JSON/XML 代码块。

错误：JSON `{ "error": { "code", "message", "retryable", "resetAt"? }, "requestId" }`。SSE 上游错误可额外返回仅含字母、数字和有限标点的 `upstreamCode`，用于诊断提供方状态；绝不透传上游 message、请求内容或凭证。DeepSeek HTTP 402 映射为不可重试的 `AI_PROVIDER_PAYMENT_REQUIRED`，模型未启用映射为不可重试的 `AI_MODEL_NOT_AVAILABLE`，其余临时上游错误返回可重试的 `UPSTREAM_UNAVAILABLE`。稳定错误码：`INVALID_ARGUMENT`、`UNAUTHORIZED_INSTALLATION`、`AI_NOT_CONFIGURED`、`AI_MODEL_NOT_CONFIGURED`、`MODEL_ATTACHMENT_UNSUPPORTED`、`DAILY_QUOTA_EXCEEDED`、`AI_PROVIDER_PAYMENT_REQUIRED`、`AI_MODEL_NOT_AVAILABLE`、`UPSTREAM_TIMEOUT`、`UPSTREAM_UNAVAILABLE`、`INTERNAL_ERROR`。

### 市场概览扩展（交互优化 2.3）

“今天大盘数据”等未指定股票代码的大盘查询，`market` 事件额外包含 `overview: MarketEnvelope<Bar[]>[]` 和 `unavailable: string[]`。并行查询上证指数、深证成指、创业板指；某项失败不丢弃其他成功项，全部失败返回 503 `MARKET_UNAVAILABLE`。顶层保留首个成功行情包，兼容旧客户端。每张卡片分别保留来源、行情时间和新鲜度，不能用获取时间代替行情时间。

Android 将整个行情包存入对应 AI 消息的 `marketDataJson`（Room v2，v1 非破坏迁移），在 AI 正文之前渲染指数卡片和折线图，不依赖模型生成 Markdown 表格；停止生成、重启或重新打开会话后仍可读取该快照。旧消息没有该字段时不补造图表。重查由“查看详情”进入行情页完成，不篡改历史回答的数据依据。

市场温度只统计返回指数样本的涨跌，不是全市场上涨/下跌股票家数，也不据此给出未经定义的强弱评级。测试夹具始终标记非实时；真实行情数据源未授权或未配置时不宣称为今日行情。

Tushare 已识别的三个指数使用 `index_daily`，个股使用 `daily`。指数接口需相应权限；未开通时不自动购买或回退冒充数据。[Tushare 指数日线官方文档](https://tushare.pro/document/1?doc_id=95)。

## GET /v1/market/quote?symbol=600000.SH

返回标准化报价、`source`、`marketTime`、`fetchedAt`、`freshness` 和 `freshnessReason`。

## GET /v1/market/bars

参数：`symbol`；`period=intraday|day|week|month`；自定义区间可带 `from`、`to`。返回 OHLCV；分时数据的 OHLC 可相同。所有时间为带偏移的 ISO-8601。

## PUT /v1/attachments/{contentSha256Prefix}

请求头包含 `X-Installation-Id`、URL 编码的 `X-File-Name` 和受支持的 `Content-Type`，请求体为原始文件字节。仅接受 JPEG、PNG、WebP、GIF、CSV 和 TXT；图片最大 10MB，CSV/TXT 最大 2MB。服务端按安装标识哈希隔离对象路径并返回 `cloud://` 对象引用，不返回公开下载地址。测试环境未配置 CloudBase 服务端凭证时返回 503 `ATTACHMENT_STORAGE_NOT_CONFIGURED`。

## GET /health

不访问付费或第三方上游；返回进程、版本、`aiReady`、可用模型 ID、`marketReady`、当前 `marketProvider`、不含凭证的 `marketProviders[]` 数据源状态，以及附件存储状态。Tushare 和 AKShare 只能报告为开发数据或未配置，固定夹具报告为测试数据；接口不返回环境变量、Token 或网关凭证。
