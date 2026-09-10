import {
  INSTALLATION_ID_PATTERN,
  ATTACHMENT_ID_PATTERN,
  MAX_ATTACHMENTS,
  MAX_IMAGE_ATTACHMENT_BYTES,
  MAX_MESSAGE_CHARS,
  MAX_MESSAGES,
  MAX_TEXT_ATTACHMENT_BYTES,
  DEFAULT_AI_MODEL,
  SUPPORTED_AI_MODELS,
  SYMBOL_PATTERN,
} from "./constants";
import type { ChatRequest, Period } from "./types";

const MIME_TYPES = new Set(["image/jpeg", "image/png", "image/webp", "image/gif", "text/csv", "text/plain"]);
const PERIODS = new Set<Period>(["intraday", "day", "week", "month"]);

export class RequestValidationError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "RequestValidationError";
  }
}

export function parseChatRequest(input: unknown): ChatRequest {
  if (!input || typeof input !== "object") throw new RequestValidationError("请求体必须是JSON对象");
  const value = input as Partial<ChatRequest>;
  if (!value.installationId || !INSTALLATION_ID_PATTERN.test(value.installationId)) {
    throw new RequestValidationError("installationId格式无效");
  }
  if (!value.conversationId || value.conversationId.length > 128) {
    throw new RequestValidationError("conversationId格式无效");
  }
  const model = value.model ?? DEFAULT_AI_MODEL;
  if (typeof model !== "string" || !SUPPORTED_AI_MODELS.has(model)) {
    throw new RequestValidationError("model不受支持");
  }
  if (!Array.isArray(value.messages) || value.messages.length < 1 || value.messages.length > MAX_MESSAGES) {
    throw new RequestValidationError(`messages数量必须为1至${MAX_MESSAGES}`);
  }
  for (const message of value.messages) {
    if (!message || (message.role !== "user" && message.role !== "assistant")) {
      throw new RequestValidationError("message.role无效");
    }
    if (typeof message.content !== "string" || !message.content.trim() || message.content.length > MAX_MESSAGE_CHARS) {
      throw new RequestValidationError(`message.content必须为1至${MAX_MESSAGE_CHARS}字符`);
    }
  }
  if (value.stream !== true) throw new RequestValidationError("V1仅接受stream=true");
  const attachments = value.attachments ?? [];
  if (!Array.isArray(attachments) || attachments.length > MAX_ATTACHMENTS) {
    throw new RequestValidationError(`attachments最多${MAX_ATTACHMENTS}个`);
  }
  for (const attachment of attachments) {
    const limit = attachment.mimeType?.startsWith("image/") ? MAX_IMAGE_ATTACHMENT_BYTES : MAX_TEXT_ATTACHMENT_BYTES;
    if (
      !ATTACHMENT_ID_PATTERN.test(attachment.id ?? "") ||
      !attachment.name || attachment.name.length > 80 ||
      !MIME_TYPES.has(attachment.mimeType) ||
      !Number.isInteger(attachment.sizeBytes) || attachment.sizeBytes <= 0 || attachment.sizeBytes > limit ||
      typeof attachment.objectRef !== "string" || !attachment.objectRef.startsWith("cloud://")
    ) {
      throw new RequestValidationError("附件元数据无效或类型不受支持");
    }
  }
  return { ...value, model } as ChatRequest;
}

export function parseSymbol(value: string | null): string {
  const symbol = (value ?? "").toUpperCase();
  if (!SYMBOL_PATTERN.test(symbol)) throw new RequestValidationError("仅支持形如600000.SH或000001.SZ的A股代码");
  return symbol;
}

export function parsePeriod(value: string | null): Period {
  if (!value || !PERIODS.has(value as Period)) throw new RequestValidationError("period必须为intraday/day/week/month");
  return value as Period;
}

export function parseIsoDate(value: string | null): string | undefined {
  if (!value) return undefined;
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value) || Number.isNaN(Date.parse(`${value}T00:00:00Z`))) {
    throw new RequestValidationError("日期必须为YYYY-MM-DD");
  }
  return value;
}
