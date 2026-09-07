export const SERVER_PORT = 9000;
export const SERVER_HOST = "0.0.0.0";
export const DEFAULT_DAILY_AI_LIMIT = 500;
export const MAX_REQUEST_BYTES = 256 * 1024;
export const MAX_IMAGE_ATTACHMENT_BYTES = 10 * 1024 * 1024;
export const MAX_TEXT_ATTACHMENT_BYTES = 2 * 1024 * 1024;
export const MAX_MESSAGES = 50;
export const MAX_MESSAGE_CHARS = 12_000;
export const MAX_ATTACHMENTS = 5;
export const QUOTA_COLLECTION = "talktoai_daily_quota";
export const DEFAULT_TIME_ZONE = "Asia/Shanghai";
export const DEFAULT_AI_PROVIDER = "cloudbase";
export const DEFAULT_AI_MODEL = "hy3";
export const INSTALLATION_ID_PATTERN = /^[A-Za-z0-9_-]{16,128}$/;
export const SYMBOL_PATTERN = /^(?:[036]\d{5})\.(?:SH|SZ)$/;
export const ATTACHMENT_ID_PATTERN = /^[a-f0-9]{16,64}$/;

export const SYSTEM_PROMPT = [
  "你是 TalkToAI 的 A 股信息助手。",
  "只提供可追溯的信息解释，不执行交易，不承诺收益，不给出确定性买入或卖出指令。",
  "涉及行情时必须复述来源、数据时间和新鲜度；信息不足时明确说明未知。",
  "默认使用简洁 Markdown：短标题、段落和项目列表；除非用户明确要求，否则不要返回原始 JSON、XML 或整段代码块。",
  "若系统消息提供了 MARKET_CONTEXT，只能基于其中的数据解读，并明确区分过期、延迟和实时数据。",
  "陈述可核验事实时优先提供HTTPS来源链接；没有可靠来源时明确写明未检索到来源，不得编造链接。",
  "回答末尾提醒：仅供信息参考，不构成投资建议。",
].join("\n");
