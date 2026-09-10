"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.createApp = createApp;
exports.createProductionApp = createProductionApp;
const node_crypto_1 = require("node:crypto");
const node_http_1 = require("node:http");
const constants_1 = require("./constants");
const deepseek_1 = require("./deepseek");
const market_data_1 = require("./market-data");
const quota_1 = require("./quota");
const validation_1 = require("./validation");
class ApiError extends Error {
    status;
    code;
    retryable;
    resetAt;
    constructor(status, code, message, retryable = false, resetAt) {
        super(message);
        this.status = status;
        this.code = code;
        this.retryable = retryable;
        this.resetAt = resetAt;
    }
}
function json(res, status, body) {
    res.writeHead(status, { "content-type": "application/json; charset=utf-8", "cache-control": "no-store" });
    res.end(JSON.stringify(body));
}
async function readJson(req) {
    const chunks = [];
    let size = 0;
    for await (const chunk of req) {
        const buffer = Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk);
        size += buffer.length;
        if (size > constants_1.MAX_REQUEST_BYTES)
            throw new ApiError(413, "PAYLOAD_TOO_LARGE", "请求体过大");
        chunks.push(buffer);
    }
    try {
        return JSON.parse(Buffer.concat(chunks).toString("utf8"));
    }
    catch {
        throw new ApiError(400, "INVALID_ARGUMENT", "请求体不是有效JSON");
    }
}
async function readBuffer(req, maxBytes) {
    const chunks = [];
    let size = 0;
    for await (const chunk of req) {
        const buffer = Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk);
        size += buffer.length;
        if (size > maxBytes)
            throw new ApiError(413, "ATTACHMENT_TOO_LARGE", "附件超过大小限制");
        chunks.push(buffer);
    }
    if (size === 0)
        throw new ApiError(400, "INVALID_ARGUMENT", "附件内容为空");
    return Buffer.concat(chunks);
}
async function handleAttachmentUpload(req, res, deps, id, requestId) {
    if (!constants_1.ATTACHMENT_ID_PATTERN.test(id))
        throw new validation_1.RequestValidationError("附件id格式无效");
    const installationId = req.headers["x-installation-id"]?.toString() ?? "";
    if (!/^[A-Za-z0-9_-]{16,128}$/.test(installationId))
        throw new validation_1.RequestValidationError("installationId格式无效");
    const mimeType = req.headers["content-type"]?.split(";")[0]?.toLowerCase() ?? "";
    const isImage = new Set(["image/jpeg", "image/png", "image/webp", "image/gif"]).has(mimeType);
    const isText = new Set(["text/csv", "text/plain"]).has(mimeType);
    if (!isImage && !isText)
        throw new validation_1.RequestValidationError("附件类型不受支持");
    if (!deps.uploadAttachment) {
        throw new ApiError(503, "ATTACHMENT_STORAGE_NOT_CONFIGURED", "测试环境附件存储尚未配置", false);
    }
    const encodedName = req.headers["x-file-name"]?.toString() ?? "attachment";
    const name = decodeURIComponent(encodedName).replace(/[^A-Za-z0-9._()\-\u4e00-\u9fa5]/g, "_").slice(0, 80) || "attachment";
    const content = await readBuffer(req, isImage ? constants_1.MAX_IMAGE_ATTACHMENT_BYTES : constants_1.MAX_TEXT_ATTACHMENT_BYTES);
    const owner = (0, node_crypto_1.createHash)("sha256").update(installationId).digest("hex");
    const cloudPath = `talktoai/v1/${owner}/${id}/${name}`;
    const result = await deps.uploadAttachment(cloudPath, content);
    json(res, 201, { id, name, mimeType, sizeBytes: content.length, objectRef: result.fileID, requestId });
}
function sse(res, event, data) {
    res.write(`event: ${event}\ndata: ${JSON.stringify(data)}\n\n`);
}
const OVERVIEW_INDICES = ["000001.SH", "399001.SZ", "399006.SZ"];
function inferMarketRequest(messages) {
    const content = [...messages].reverse().find((message) => message.role === "user")?.content ?? "";
    const mentionsMarket = /(大盘|行情|走势|指数|K\s*线|成交量|A\s*股)/i.test(content)
        || /(?:今日数据|今天数据)/.test(content.replace(/\s/g, ""))
        || /(?:今日|今天).*(?:市场|盘面|涨跌)/.test(content)
        || /(?:市场|盘面|涨跌).*(?:今日|今天)/.test(content)
        || /(?:today\s*market|market\s*today|a-?share|stock\s*index|kline)/i.test(content);
    if (!mentionsMarket)
        return undefined;
    const explicit = content.match(/\b([036]\d{5})(?:\.(SH|SZ))?\b/i);
    const code = explicit?.[1];
    const suffix = explicit?.[2]?.toUpperCase() ?? (code?.startsWith("6") ? "SH" : "SZ");
    return {
        symbol: code ? `${code}.${suffix}` : "000001.SH",
        overview: !code && /大盘|市场|盘面|today.*market|market.*today/i.test(content),
        period: /分时|盘中/.test(content) ? "intraday" : /月线|月K/i.test(content) ? "month" : /周线|周K/i.test(content) ? "week" : "day",
    };
}
async function toAiMessages(messages, market, attachments = [], installationId = "", downloadAttachment) {
    const marketContext = market
        ? `\n\n${formatMarketContext(market)}`
        : "";
    const lastUserMessageIndex = messages.map((message) => message.role).lastIndexOf("user");
    const groundedUserContent = lastUserMessageIndex >= 0 && market
        ? [
            messages[lastUserMessageIndex].content,
            "以下只读行情工具数据已经成功返回。回答必须以它为依据，不得声称 MARKET_CONTEXT 缺失；历史消息中与该工具结果冲突的表述无效。",
            marketContext.trim(),
        ].join("\n\n")
        : lastUserMessageIndex >= 0
            ? messages[lastUserMessageIndex].content
            : marketContext.trim();
    // CloudBase hy3 expects a single leading system message.
    // Repeat the server-normalized market context in the latest user turn. Long conversations can contain a stale
    // assistant denial about tool availability, while the latest tool result must remain the grounding authority.
    const output = [
        { role: "system", content: `${constants_1.SYSTEM_PROMPT}${marketContext}` },
        ...messages.map((message, index) => ({
            role: message.role,
            content: index === lastUserMessageIndex ? groundedUserContent : message.content,
        })),
    ];
    if (attachments.length === 0)
        return output;
    if (!downloadAttachment) {
        throw new ApiError(503, "ATTACHMENT_READ_NOT_CONFIGURED", "测试环境附件读取尚未配置", true);
    }
    const owner = (0, node_crypto_1.createHash)("sha256").update(installationId).digest("hex");
    const expectedPath = `/talktoai/v1/${owner}/`;
    const contentBlocks = [
        { type: "text", text: groundedUserContent || "请分析附件" },
    ];
    let textBudget = constants_1.MAX_TEXT_ATTACHMENT_CONTEXT_BYTES;
    for (const attachment of attachments) {
        if (!attachment.objectRef.includes(expectedPath)) {
            throw new ApiError(403, "ATTACHMENT_FORBIDDEN", "附件不属于当前安装", false);
        }
        const bytes = await downloadAttachment(attachment.objectRef);
        if (bytes.length !== attachment.sizeBytes) {
            throw new ApiError(409, "ATTACHMENT_CHANGED", "附件内容与上传记录不一致", true);
        }
        if (attachment.mimeType.startsWith("image/")) {
            contentBlocks.push({
                type: "text",
                text: `下面图片的来源标记为【附件：${attachment.name}】。`,
            });
            contentBlocks.push({
                type: "image_url",
                image_url: { url: `data:${attachment.mimeType};base64,${bytes.toString("base64")}` },
            });
        }
        else {
            const selected = bytes.subarray(0, Math.max(0, textBudget));
            textBudget -= selected.length;
            const suffix = selected.length < bytes.length ? "\n[内容已按上下文上限截断]" : "";
            contentBlocks.push({
                type: "text",
                text: `【附件：${attachment.name}；类型：${attachment.mimeType}】\n${selected.toString("utf8")}${suffix}`,
            });
        }
    }
    const lastUserIndex = output.map((message) => message.role).lastIndexOf("user");
    if (lastUserIndex >= 0)
        output[lastUserIndex] = { role: "user", content: contentBlocks };
    return output;
}
function formatMarketContext(market) {
    const rows = market.data.slice(-20).map((bar) => `${bar.time},O=${bar.open},H=${bar.high},L=${bar.low},C=${bar.close},V=${bar.volume}`);
    return [
        "MARKET_CONTEXT（只读工具结果，禁止推断为实时）",
        `symbol=${market.symbol}`,
        `source=${market.source}`,
        `marketTime=${market.marketTime}`,
        `fetchedAt=${market.fetchedAt}`,
        `freshness=${market.freshness}`,
        `freshnessReason=${market.freshnessReason}`,
        ...rows,
        ...(market.overview?.filter((item) => item.symbol !== market.symbol).map(formatMarketContext) ?? []),
        ...(market.unavailable?.length ? [`不可用指数：${market.unavailable.join(",")}；不得补造数据。`] : []),
    ].join("\n");
}
async function handleChat(req, res, deps, requestId) {
    const input = (0, validation_1.parseChatRequest)(await readJson(req));
    if (deps.aiConfigured === false) {
        throw new ApiError(503, "AI_NOT_CONFIGURED", "测试环境尚未配置AI服务凭证", false);
    }
    if (input.model === constants_1.DEEPSEEK_AI_MODEL && deps.deepseekConfigured === false) {
        throw new ApiError(503, "AI_MODEL_NOT_CONFIGURED", "DeepSeek测试模型尚未配置", false);
    }
    if (input.model === constants_1.DEEPSEEK_AI_MODEL &&
        input.attachments?.some((attachment) => attachment.mimeType.startsWith("image/"))) {
        throw new ApiError(400, "MODEL_ATTACHMENT_UNSUPPORTED", "DeepSeek文本模型暂不支持图片附件", false);
    }
    const marketRequest = inferMarketRequest(input.messages);
    let market = marketRequest && !marketRequest.overview
        ? await deps.market.bars(marketRequest.symbol, marketRequest.period, undefined, undefined, deps.now())
        : undefined;
    if (marketRequest?.overview) {
        const additional = await Promise.allSettled(OVERVIEW_INDICES.map((symbol) => deps.market.bars(symbol, marketRequest.period, undefined, undefined, deps.now())));
        const available = additional.flatMap((result) => result.status === "fulfilled" && result.value.data.length ? [result.value] : []);
        if (!available.length)
            throw new ApiError(503, "MARKET_UNAVAILABLE", "主要指数数据暂不可用，请重试；不会生成虚构行情", true);
        market = {
            ...available[0],
            overview: available,
            unavailable: additional.flatMap((result, index) => result.status === "rejected" || !result.value.data.length ? [OVERVIEW_INDICES[index]] : []),
        };
    }
    // Resolve and authorize attachment content before quota consumption and before SSE headers are sent.
    // Validation failures therefore remain ordinary HTTP errors and never consume an AI request.
    const modelMessages = await toAiMessages(input.messages, market, input.attachments, input.installationId, deps.downloadAttachment);
    const quota = await deps.quota.consume(input.installationId, deps.now());
    if (!quota.allowed)
        throw new ApiError(429, "DAILY_QUOTA_EXCEEDED", "今日AI请求次数已用完", false, quota.resetAt);
    res.writeHead(200, {
        "content-type": "text/event-stream; charset=utf-8",
        "cache-control": "no-cache, no-store",
        connection: "keep-alive",
        "x-accel-buffering": "no",
    });
    sse(res, "meta", { requestId, quota: { used: quota.used, limit: quota.limit, resetAt: quota.resetAt } });
    try {
        if (market)
            sse(res, "market", market);
        for (const attachment of input.attachments ?? []) {
            sse(res, "citation", { kind: "attachment", attachmentId: attachment.id, title: attachment.name });
        }
        const result = await deps.createAiModel().streamText({
            model: input.model,
            messages: modelMessages,
        });
        let fullText = "";
        for await (const text of result.textStream) {
            if (res.destroyed)
                break;
            if (text) {
                fullText += text;
                sse(res, "delta", { text });
            }
        }
        if (!res.destroyed) {
            for (const url of extractHttpsUrls(fullText)) {
                sse(res, "citation", { url, title: new URL(url).hostname });
            }
            sse(res, "done", { requestId, usage: await result.usage });
            res.end();
        }
    }
    catch (error) {
        if (!res.destroyed) {
            sse(res, "error", { code: "UPSTREAM_UNAVAILABLE", message: "AI服务暂时不可用", retryable: true });
            res.end();
        }
        console.error(JSON.stringify({ level: "error", event: "ai_upstream_failed", requestId, errorName: error instanceof Error ? error.name : "Unknown" }));
    }
}
function extractHttpsUrls(text) {
    const matches = text.match(/https:\/\/[^\s)\]}>，。]+/g) ?? [];
    return [...new Set(matches)].slice(0, 10).filter((value) => {
        try {
            return new URL(value).protocol === "https:";
        }
        catch {
            return false;
        }
    });
}
function createApp(deps) {
    return (0, node_http_1.createServer)(async (req, res) => {
        const requestId = (0, node_crypto_1.randomUUID)();
        try {
            const url = new URL(req.url ?? "/", "http://localhost");
            const path = url.pathname.replace(/^\/talktoai/, "");
            if (req.method === "GET" && path === "/health") {
                return json(res, 200, {
                    status: "ok",
                    service: "talktoai-api",
                    version: "1.0.0",
                    capabilities: {
                        aiReady: deps.aiConfigured !== false,
                        models: deps.availableModels ?? [constants_1.DEFAULT_AI_MODEL],
                        marketReady: true,
                        marketProvider: deps.marketProvider ?? "injected",
                        marketProviders: deps.marketProviders ?? [],
                        attachments: deps.uploadAttachment && deps.downloadAttachment ? "ready" : "configuration_required",
                    },
                    requestId,
                });
            }
            if (req.method === "POST" && path === "/v1/chat/completions") {
                return await handleChat(req, res, deps, requestId);
            }
            const attachmentMatch = path.match(/^\/v1\/attachments\/([a-f0-9]+)$/);
            if (req.method === "PUT" && attachmentMatch) {
                return await handleAttachmentUpload(req, res, deps, attachmentMatch[1], requestId);
            }
            if (req.method === "GET" && path === "/v1/market/quote") {
                const result = await deps.market.quote((0, validation_1.parseSymbol)(url.searchParams.get("symbol")), deps.now());
                return json(res, 200, { ...result, requestId });
            }
            if (req.method === "GET" && path === "/v1/market/bars") {
                const symbol = (0, validation_1.parseSymbol)(url.searchParams.get("symbol"));
                const period = (0, validation_1.parsePeriod)(url.searchParams.get("period"));
                const from = (0, validation_1.parseIsoDate)(url.searchParams.get("from"));
                const to = (0, validation_1.parseIsoDate)(url.searchParams.get("to"));
                if (from && to && from > to)
                    throw new validation_1.RequestValidationError("from不得晚于to");
                const result = await deps.market.bars(symbol, period, from, to, deps.now());
                return json(res, 200, { ...result, requestId });
            }
            return json(res, 404, { error: { code: "NOT_FOUND", message: "接口不存在", retryable: false }, requestId });
        }
        catch (error) {
            if (res.headersSent)
                return;
            if (error instanceof validation_1.RequestValidationError) {
                return json(res, 400, { error: { code: "INVALID_ARGUMENT", message: error.message, retryable: false }, requestId });
            }
            if (error instanceof ApiError) {
                return json(res, error.status, {
                    error: { code: error.code, message: error.message, retryable: error.retryable, ...(error.resetAt ? { resetAt: error.resetAt } : {}) },
                    requestId,
                });
            }
            console.error(JSON.stringify({ level: "error", event: "request_failed", requestId, errorName: error instanceof Error ? error.name : "Unknown" }));
            return json(res, 500, { error: { code: "INTERNAL_ERROR", message: "服务内部错误", retryable: true }, requestId });
        }
    });
}
function createCloudBaseDependencies() {
    const limit = Number.parseInt(process.env.DAILY_AI_LIMIT || `${constants_1.DEFAULT_DAILY_AI_LIMIT}`, 10);
    const accessKey = process.env.CLOUDBASE_APIKEY;
    const deepseekApiKey = process.env.DEEPSEEK_API_KEY?.trim();
    // eslint-disable-next-line @typescript-eslint/no-require-imports
    const cloudbase = require("@cloudbase/node-sdk");
    const initOptions = {
        env: process.env.CLOUDBASE_ENV_ID,
        timeout: 120_000,
    };
    if (accessKey)
        initOptions.accessKey = accessKey;
    const app = cloudbase.init(initOptions);
    const marketData = (0, market_data_1.createDevelopmentMarketData)();
    // CloudBase injects a runtime credential into cloud functions. The Node SDK uses
    // that identity for AI, database and storage, so a second long-lived API key is
    // optional rather than a prerequisite in the deployed function.
    const quota = process.env.ALLOW_EPHEMERAL_QUOTA === "true"
        ? new quota_1.MemoryQuotaStore(limit)
        : process.env.QUOTA_STORE === "sql"
            ? new quota_1.CloudBaseSqlQuotaStore(app.models, limit)
            : new quota_1.CloudBaseQuotaStore(app.database(), limit);
    return {
        quota,
        market: marketData.provider,
        marketProvider: marketData.providerId,
        marketProviders: marketData.providers,
        aiConfigured: true,
        uploadAttachment: (cloudPath, content) => app.uploadFile({ cloudPath, fileContent: content }),
        downloadAttachment: async (fileID) => {
            const result = await app.downloadFile({ fileID });
            if (result.fileContent === undefined)
                throw new Error("attachment-download-empty");
            return Buffer.isBuffer(result.fileContent) ? result.fileContent : Buffer.from(result.fileContent);
        },
        deepseekConfigured: Boolean(deepseekApiKey),
        availableModels: [constants_1.DEFAULT_AI_MODEL, ...(deepseekApiKey ? [constants_1.DEEPSEEK_AI_MODEL] : [])],
        createAiModel: () => ({
            streamText: (input) => input.model === constants_1.DEEPSEEK_AI_MODEL
                ? new deepseek_1.DeepSeekModel(deepseekApiKey ?? "").streamText(input)
                : app.ai().createModel(process.env.AI_PROVIDER || constants_1.DEFAULT_AI_PROVIDER)
                    .streamText({ ...input, model: process.env.AI_MODEL || constants_1.DEFAULT_AI_MODEL }),
        }),
        now: () => new Date(),
    };
}
function createProductionApp() {
    return createApp(createCloudBaseDependencies());
}
