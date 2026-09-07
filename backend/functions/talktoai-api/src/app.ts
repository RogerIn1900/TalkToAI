import { createHash, randomUUID } from "node:crypto";
import { createServer, type IncomingMessage, type ServerResponse } from "node:http";
import {
  DEFAULT_AI_MODEL,
  DEFAULT_AI_PROVIDER,
  DEFAULT_DAILY_AI_LIMIT,
  ATTACHMENT_ID_PATTERN,
  MAX_IMAGE_ATTACHMENT_BYTES,
  MAX_REQUEST_BYTES,
  MAX_TEXT_ATTACHMENT_BYTES,
  SYSTEM_PROMPT,
} from "./constants";
import { FixtureMarketDataProvider } from "./fixtures";
import { CloudBaseQuotaStore, CloudBaseSqlQuotaStore, MemoryQuotaStore } from "./quota";
import type { ChatMessage, MarketDataProvider, QuotaStore } from "./types";
import { parseChatRequest, parseIsoDate, parsePeriod, parseSymbol, RequestValidationError } from "./validation";

interface AiTextStream {
  textStream: AsyncIterable<string>;
  usage: Promise<unknown>;
}

interface AiModel {
  streamText(input: { model: string; messages: Array<{ role: string; content: string }> }): Promise<AiTextStream>;
}

interface AppDependencies {
  quota: QuotaStore;
  market: MarketDataProvider;
  createAiModel: () => AiModel;
  now: () => Date;
  aiConfigured?: boolean;
  marketProvider?: string;
  uploadAttachment?: (cloudPath: string, content: Buffer) => Promise<{ fileID: string }>;
}

class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string,
    readonly retryable = false,
    readonly resetAt?: string,
  ) {
    super(message);
  }
}

function json(res: ServerResponse, status: number, body: unknown): void {
  res.writeHead(status, { "content-type": "application/json; charset=utf-8", "cache-control": "no-store" });
  res.end(JSON.stringify(body));
}

async function readJson(req: IncomingMessage): Promise<unknown> {
  const chunks: Buffer[] = [];
  let size = 0;
  for await (const chunk of req) {
    const buffer = Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk);
    size += buffer.length;
    if (size > MAX_REQUEST_BYTES) throw new ApiError(413, "PAYLOAD_TOO_LARGE", "请求体过大");
    chunks.push(buffer);
  }
  try {
    return JSON.parse(Buffer.concat(chunks).toString("utf8"));
  } catch {
    throw new ApiError(400, "INVALID_ARGUMENT", "请求体不是有效JSON");
  }
}

async function readBuffer(req: IncomingMessage, maxBytes: number): Promise<Buffer> {
  const chunks: Buffer[] = [];
  let size = 0;
  for await (const chunk of req) {
    const buffer = Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk);
    size += buffer.length;
    if (size > maxBytes) throw new ApiError(413, "ATTACHMENT_TOO_LARGE", "附件超过大小限制");
    chunks.push(buffer);
  }
  if (size === 0) throw new ApiError(400, "INVALID_ARGUMENT", "附件内容为空");
  return Buffer.concat(chunks);
}

async function handleAttachmentUpload(
  req: IncomingMessage,
  res: ServerResponse,
  deps: AppDependencies,
  id: string,
  requestId: string,
): Promise<void> {
  if (!ATTACHMENT_ID_PATTERN.test(id)) throw new RequestValidationError("附件id格式无效");
  const installationId = req.headers["x-installation-id"]?.toString() ?? "";
  if (!/^[A-Za-z0-9_-]{16,128}$/.test(installationId)) throw new RequestValidationError("installationId格式无效");
  const mimeType = req.headers["content-type"]?.split(";")[0]?.toLowerCase() ?? "";
  const isImage = new Set(["image/jpeg", "image/png", "image/webp", "image/gif"]).has(mimeType);
  const isText = new Set(["text/csv", "text/plain"]).has(mimeType);
  if (!isImage && !isText) throw new RequestValidationError("附件类型不受支持");
  if (!deps.uploadAttachment) {
    throw new ApiError(503, "ATTACHMENT_STORAGE_NOT_CONFIGURED", "测试环境附件存储尚未配置", false);
  }
  const encodedName = req.headers["x-file-name"]?.toString() ?? "attachment";
  const name = decodeURIComponent(encodedName).replace(/[^A-Za-z0-9._()\-\u4e00-\u9fa5]/g, "_").slice(0, 80) || "attachment";
  const content = await readBuffer(req, isImage ? MAX_IMAGE_ATTACHMENT_BYTES : MAX_TEXT_ATTACHMENT_BYTES);
  const owner = createHash("sha256").update(installationId).digest("hex");
  const cloudPath = `talktoai/v1/${owner}/${id}/${name}`;
  const result = await deps.uploadAttachment(cloudPath, content);
  json(res, 201, { id, name, mimeType, sizeBytes: content.length, objectRef: result.fileID, requestId });
}

function sse(res: ServerResponse, event: string, data: unknown): void {
  res.write(`event: ${event}\ndata: ${JSON.stringify(data)}\n\n`);
}

function toAiMessages(messages: ChatMessage[]): Array<{ role: string; content: string }> {
  return [{ role: "system", content: SYSTEM_PROMPT }, ...messages];
}

async function handleChat(req: IncomingMessage, res: ServerResponse, deps: AppDependencies, requestId: string): Promise<void> {
  const input = parseChatRequest(await readJson(req));
  if (deps.aiConfigured === false) {
    throw new ApiError(503, "AI_NOT_CONFIGURED", "测试环境尚未配置AI服务凭证", false);
  }
  const quota = await deps.quota.consume(input.installationId, deps.now());
  if (!quota.allowed) throw new ApiError(429, "DAILY_QUOTA_EXCEEDED", "今日AI请求次数已用完", false, quota.resetAt);

  res.writeHead(200, {
    "content-type": "text/event-stream; charset=utf-8",
    "cache-control": "no-cache, no-store",
    connection: "keep-alive",
    "x-accel-buffering": "no",
  });
  sse(res, "meta", { requestId, quota: { used: quota.used, limit: quota.limit, resetAt: quota.resetAt } });

  try {
    const result = await deps.createAiModel().streamText({
      model: process.env.AI_MODEL || DEFAULT_AI_MODEL,
      messages: toAiMessages(input.messages),
    });
    let fullText = "";
    for await (const text of result.textStream) {
      if (res.destroyed) break;
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
  } catch (error) {
    if (!res.destroyed) {
      sse(res, "error", { code: "UPSTREAM_UNAVAILABLE", message: "AI服务暂时不可用", retryable: true });
      res.end();
    }
    console.error(JSON.stringify({ level: "error", event: "ai_upstream_failed", requestId, errorName: error instanceof Error ? error.name : "Unknown" }));
  }
}

function extractHttpsUrls(text: string): string[] {
  const matches = text.match(/https:\/\/[^\s)\]}>，。]+/g) ?? [];
  return [...new Set(matches)].slice(0, 10).filter((value) => {
    try {
      return new URL(value).protocol === "https:";
    } catch {
      return false;
    }
  });
}

export function createApp(deps: AppDependencies) {
  return createServer(async (req, res) => {
    const requestId = randomUUID();
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
        return await handleAttachmentUpload(req, res, deps, attachmentMatch[1]!, requestId);
      }
      if (req.method === "GET" && path === "/v1/market/quote") {
        const result = await deps.market.quote(parseSymbol(url.searchParams.get("symbol")), deps.now());
        return json(res, 200, { ...result, requestId });
      }
      if (req.method === "GET" && path === "/v1/market/bars") {
        const symbol = parseSymbol(url.searchParams.get("symbol"));
        const period = parsePeriod(url.searchParams.get("period"));
        const from = parseIsoDate(url.searchParams.get("from"));
        const to = parseIsoDate(url.searchParams.get("to"));
        if (from && to && from > to) throw new RequestValidationError("from不得晚于to");
        const result = await deps.market.bars(symbol, period, from, to, deps.now());
        return json(res, 200, { ...result, requestId });
      }
      return json(res, 404, { error: { code: "NOT_FOUND", message: "接口不存在", retryable: false }, requestId });
    } catch (error) {
      if (res.headersSent) return;
      if (error instanceof RequestValidationError) {
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

function createCloudBaseDependencies(): AppDependencies {
  const limit = Number.parseInt(process.env.DAILY_AI_LIMIT || `${DEFAULT_DAILY_AI_LIMIT}`, 10);
  const accessKey = process.env.CLOUDBASE_APIKEY;
  // eslint-disable-next-line @typescript-eslint/no-require-imports
  const cloudbase = require("@cloudbase/node-sdk") as { init(input: Record<string, unknown>): any };
  const initOptions: Record<string, unknown> = {
    env: process.env.CLOUDBASE_ENV_ID,
    timeout: 120_000,
  };
  if (accessKey) initOptions.accessKey = accessKey;
  const app = cloudbase.init(initOptions);

  // CloudBase injects a runtime credential into cloud functions. The Node SDK uses
  // that identity for AI, database and storage, so a second long-lived API key is
  // optional rather than a prerequisite in the deployed function.
  const quota = process.env.ALLOW_EPHEMERAL_QUOTA === "true"
    ? new MemoryQuotaStore(limit)
    : process.env.QUOTA_STORE === "sql"
      ? new CloudBaseSqlQuotaStore(app.models, limit)
      : new CloudBaseQuotaStore(app.database(), limit);
  return {
    quota,
    market: new FixtureMarketDataProvider(),
    marketProvider: "fixture",
    aiConfigured: true,
    uploadAttachment: (cloudPath, content) => app.uploadFile({ cloudPath, fileContent: content }),
    createAiModel: () => app.ai().createModel(process.env.AI_PROVIDER || DEFAULT_AI_PROVIDER) as AiModel,
    now: () => new Date(),
  };
}

export function createProductionApp() {
  return createApp(createCloudBaseDependencies());
}
