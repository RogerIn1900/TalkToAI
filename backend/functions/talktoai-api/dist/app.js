"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.createApp = createApp;
exports.createProductionApp = createProductionApp;
const node_crypto_1 = require("node:crypto");
const node_http_1 = require("node:http");
const constants_1 = require("./constants");
const fixtures_1 = require("./fixtures");
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
function toAiMessages(messages) {
    return [{ role: "system", content: constants_1.SYSTEM_PROMPT }, ...messages];
}
async function handleChat(req, res, deps, requestId) {
    const input = (0, validation_1.parseChatRequest)(await readJson(req));
    if (deps.aiConfigured === false) {
        throw new ApiError(503, "AI_NOT_CONFIGURED", "测试环境尚未配置AI服务凭证", false);
    }
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
        const result = await deps.createAiModel().streamText({
            model: process.env.AI_MODEL || constants_1.DEFAULT_AI_MODEL,
            messages: toAiMessages(input.messages),
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
                        marketReady: true,
                        marketProvider: deps.marketProvider ?? "injected",
                        attachments: deps.uploadAttachment ? "ready" : "configuration_required",
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
    if (!accessKey && process.env.ALLOW_EPHEMERAL_QUOTA !== "true") {
        throw new Error("CLOUDBASE_APIKEY is required; ephemeral quota is disabled");
    }
    // eslint-disable-next-line @typescript-eslint/no-require-imports
    const cloudbase = require("@cloudbase/node-sdk");
    const app = cloudbase.init({
        env: process.env.CLOUDBASE_ENV_ID,
        accessKey,
        timeout: 120_000,
    });
    const quota = accessKey ? new quota_1.CloudBaseQuotaStore(app.database(), limit) : new quota_1.MemoryQuotaStore(limit);
    return {
        quota,
        market: new fixtures_1.FixtureMarketDataProvider(),
        marketProvider: "fixture",
        aiConfigured: Boolean(accessKey),
        uploadAttachment: accessKey
            ? (cloudPath, content) => app.uploadFile({ cloudPath, fileContent: content })
            : undefined,
        createAiModel: () => {
            if (!accessKey)
                throw new ApiError(503, "AI_NOT_CONFIGURED", "测试环境尚未配置AI服务凭证");
            return app.ai().createModel(process.env.AI_PROVIDER || constants_1.DEFAULT_AI_PROVIDER);
        },
        now: () => new Date(),
    };
}
function createProductionApp() {
    return createApp(createCloudBaseDependencies());
}
