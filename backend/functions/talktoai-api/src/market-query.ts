import type { ChatMessage, Period } from "./types";

export const OVERVIEW_SYMBOLS = ["000001.SH", "399001.SZ", "399006.SZ"] as const;

export interface MarketQueryPlan {
  symbols: string[];
  period: Period;
  overview: boolean;
  matchedBy: "code" | "name" | "market_overview";
}

/**
 * Conservative, deterministic aliases used before any future LLM entity resolver.
 * Values are provider-normalized A-share symbols; unknown names are never guessed.
 */
const SECURITY_ALIASES: ReadonlyArray<readonly [string, string]> = [
  ["上证指数", "000001.SH"],
  ["上证综指", "000001.SH"],
  ["深证成指", "399001.SZ"],
  ["创业板指", "399006.SZ"],
  ["浦发银行", "600000.SH"],
  ["贵州茅台", "600519.SH"],
  ["茅台", "600519.SH"],
  ["平安银行", "000001.SZ"],
  ["宁德时代", "300750.SZ"],
  ["比亚迪", "002594.SZ"],
  ["招商银行", "600036.SH"],
  ["中国平安", "601318.SH"],
  ["中芯国际", "688981.SH"],
  ["东方财富", "300059.SZ"],
];

const MARKET_INTENT = /(大盘|行情|走势|指数|K\s*线|成交量|涨跌|盘面|股价|股票|A\s*股)/i;
const TODAY_MARKET_INTENT = /(?:今日数据|今天数据)/;
const MARKET_TODAY_INTENT = /(?:今日|今天).*(?:市场|盘面|涨跌)|(?:市场|盘面|涨跌).*(?:今日|今天)/;
const ENGLISH_MARKET_INTENT = /(?:today\s*market|market\s*today|a-?share|stock\s*index|kline)/i;
const MARKET_OVERVIEW_INTENT = /大盘|市场|盘面|today\s*market|market\s*today/i;
const EXPLICIT_SYMBOL = /\b([036]\d{5})(?:\.(SH|SZ))?\b/gi;

export function inferMarketQuery(messages: ChatMessage[]): MarketQueryPlan | undefined {
  const content = [...messages].reverse().find((message) => message.role === "user")?.content.trim() ?? "";
  if (!content) return undefined;

  const explicitSymbols = [...content.matchAll(EXPLICIT_SYMBOL)].map((match) => {
    const code = match[1]!;
    const exchange = match[2]?.toUpperCase() ?? (code.startsWith("6") ? "SH" : "SZ");
    return `${code}.${exchange}`;
  });
  const aliasSymbols = SECURITY_ALIASES
    .filter(([alias]) => content.includes(alias))
    .map(([, symbol]) => symbol);
  const symbols = [...new Set([...explicitSymbols, ...aliasSymbols])];
  const mentionsMarket = MARKET_INTENT.test(content)
    || TODAY_MARKET_INTENT.test(content.replace(/\s/g, ""))
    || MARKET_TODAY_INTENT.test(content)
    || ENGLISH_MARKET_INTENT.test(content);

  if (!mentionsMarket && symbols.length === 0) return undefined;

  const overview = symbols.length === 0 && MARKET_OVERVIEW_INTENT.test(content);
  return {
    symbols: overview ? [...OVERVIEW_SYMBOLS] : symbols.length > 0 ? symbols : ["000001.SH"],
    overview,
    matchedBy: explicitSymbols.length > 0 ? "code" : aliasSymbols.length > 0 ? "name" : "market_overview",
    period: /分时|盘中/.test(content)
      ? "intraday"
      : /月线|月K/i.test(content)
        ? "month"
        : /周线|周K/i.test(content)
          ? "week"
          : "day",
  };
}

export function displayNameForSymbol(symbol: string): string | undefined {
  return SECURITY_ALIASES.find(([, value]) => value === symbol)?.[0];
}
