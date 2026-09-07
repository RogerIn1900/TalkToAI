"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.SYSTEM_PROMPT = exports.ATTACHMENT_ID_PATTERN = exports.SYMBOL_PATTERN = exports.INSTALLATION_ID_PATTERN = exports.DEFAULT_AI_MODEL = exports.DEFAULT_AI_PROVIDER = exports.DEFAULT_TIME_ZONE = exports.QUOTA_COLLECTION = exports.MAX_ATTACHMENTS = exports.MAX_MESSAGE_CHARS = exports.MAX_MESSAGES = exports.MAX_TEXT_ATTACHMENT_BYTES = exports.MAX_IMAGE_ATTACHMENT_BYTES = exports.MAX_REQUEST_BYTES = exports.DEFAULT_DAILY_AI_LIMIT = exports.SERVER_HOST = exports.SERVER_PORT = void 0;
exports.SERVER_PORT = 9000;
exports.SERVER_HOST = "0.0.0.0";
exports.DEFAULT_DAILY_AI_LIMIT = 500;
exports.MAX_REQUEST_BYTES = 256 * 1024;
exports.MAX_IMAGE_ATTACHMENT_BYTES = 10 * 1024 * 1024;
exports.MAX_TEXT_ATTACHMENT_BYTES = 2 * 1024 * 1024;
exports.MAX_MESSAGES = 50;
exports.MAX_MESSAGE_CHARS = 12_000;
exports.MAX_ATTACHMENTS = 5;
exports.QUOTA_COLLECTION = "talktoai_daily_quota";
exports.DEFAULT_TIME_ZONE = "Asia/Shanghai";
exports.DEFAULT_AI_PROVIDER = "cloudbase";
exports.DEFAULT_AI_MODEL = "hy3";
exports.INSTALLATION_ID_PATTERN = /^[A-Za-z0-9_-]{16,128}$/;
exports.SYMBOL_PATTERN = /^(?:[036]\d{5})\.(?:SH|SZ)$/;
exports.ATTACHMENT_ID_PATTERN = /^[a-f0-9]{16,64}$/;
exports.SYSTEM_PROMPT = [
    "你是 TalkToAI 的 A 股信息助手。",
    "只提供可追溯的信息解释，不执行交易，不承诺收益，不给出确定性买入或卖出指令。",
    "涉及行情时必须复述来源、数据时间和新鲜度；信息不足时明确说明未知。",
    "默认使用简洁 Markdown：短标题、段落和项目列表；除非用户明确要求，否则不要返回原始 JSON、XML 或整段代码块。",
    "若系统消息提供了 MARKET_CONTEXT，只能基于其中的数据解读，并明确区分过期、延迟和实时数据。",
    "陈述可核验事实时优先提供HTTPS来源链接；没有可靠来源时明确写明未检索到来源，不得编造链接。",
    "回答末尾提醒：仅供信息参考，不构成投资建议。",
].join("\n");
