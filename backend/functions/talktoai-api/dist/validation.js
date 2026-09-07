"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.RequestValidationError = void 0;
exports.parseChatRequest = parseChatRequest;
exports.parseSymbol = parseSymbol;
exports.parsePeriod = parsePeriod;
exports.parseIsoDate = parseIsoDate;
const constants_1 = require("./constants");
const MIME_TYPES = new Set(["image/jpeg", "image/png", "image/webp", "image/gif", "text/csv", "text/plain"]);
const PERIODS = new Set(["intraday", "day", "week", "month"]);
class RequestValidationError extends Error {
    constructor(message) {
        super(message);
        this.name = "RequestValidationError";
    }
}
exports.RequestValidationError = RequestValidationError;
function parseChatRequest(input) {
    if (!input || typeof input !== "object")
        throw new RequestValidationError("请求体必须是JSON对象");
    const value = input;
    if (!value.installationId || !constants_1.INSTALLATION_ID_PATTERN.test(value.installationId)) {
        throw new RequestValidationError("installationId格式无效");
    }
    if (!value.conversationId || value.conversationId.length > 128) {
        throw new RequestValidationError("conversationId格式无效");
    }
    if (!Array.isArray(value.messages) || value.messages.length < 1 || value.messages.length > constants_1.MAX_MESSAGES) {
        throw new RequestValidationError(`messages数量必须为1至${constants_1.MAX_MESSAGES}`);
    }
    for (const message of value.messages) {
        if (!message || (message.role !== "user" && message.role !== "assistant")) {
            throw new RequestValidationError("message.role无效");
        }
        if (typeof message.content !== "string" || !message.content.trim() || message.content.length > constants_1.MAX_MESSAGE_CHARS) {
            throw new RequestValidationError(`message.content必须为1至${constants_1.MAX_MESSAGE_CHARS}字符`);
        }
    }
    if (value.stream !== true)
        throw new RequestValidationError("V1仅接受stream=true");
    const attachments = value.attachments ?? [];
    if (!Array.isArray(attachments) || attachments.length > constants_1.MAX_ATTACHMENTS) {
        throw new RequestValidationError(`attachments最多${constants_1.MAX_ATTACHMENTS}个`);
    }
    for (const attachment of attachments) {
        const limit = attachment.mimeType?.startsWith("image/") ? constants_1.MAX_IMAGE_ATTACHMENT_BYTES : constants_1.MAX_TEXT_ATTACHMENT_BYTES;
        if (!constants_1.ATTACHMENT_ID_PATTERN.test(attachment.id ?? "") ||
            !attachment.name || attachment.name.length > 80 ||
            !MIME_TYPES.has(attachment.mimeType) ||
            !Number.isInteger(attachment.sizeBytes) || attachment.sizeBytes <= 0 || attachment.sizeBytes > limit ||
            typeof attachment.objectRef !== "string" || !attachment.objectRef.startsWith("cloud://")) {
            throw new RequestValidationError("附件元数据无效或类型不受支持");
        }
    }
    return value;
}
function parseSymbol(value) {
    const symbol = (value ?? "").toUpperCase();
    if (!constants_1.SYMBOL_PATTERN.test(symbol))
        throw new RequestValidationError("仅支持形如600000.SH或000001.SZ的A股代码");
    return symbol;
}
function parsePeriod(value) {
    if (!value || !PERIODS.has(value))
        throw new RequestValidationError("period必须为intraday/day/week/month");
    return value;
}
function parseIsoDate(value) {
    if (!value)
        return undefined;
    if (!/^\d{4}-\d{2}-\d{2}$/.test(value) || Number.isNaN(Date.parse(`${value}T00:00:00Z`))) {
        throw new RequestValidationError("日期必须为YYYY-MM-DD");
    }
    return value;
}
