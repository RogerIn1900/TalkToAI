import { FixtureMarketDataProvider } from "./fixtures";
import type { Bar, Freshness, MarketDataProvider, MarketEnvelope, Period } from "./types";

const TUSHARE_API_URL = "https://api.tushare.pro";
const TUSHARE_SOURCE = "Tushare Pro 未复权日线（开发研究/延时数据，非实时）";
const AKSHARE_SOURCE = "AKShare via AKTools（开发研究/测试数据，非实时）";
const REQUEST_TIMEOUT_MS = 10_000;
const QUOTE_LOOKBACK_DAYS = 45;
const DAY_LOOKBACK_DAYS = 120;
const WEEK_LOOKBACK_DAYS = 370;
const MONTH_LOOKBACK_DAYS = 800;
const CONSERVATIVE_RECENCY_MS = 4 * 24 * 60 * 60 * 1_000;

type Fetcher = (input: string | URL, init?: RequestInit) => Promise<Response>;

export interface MarketProviderStatus {
  id: "tushare" | "akshare" | "fixture";
  name: string;
  status: "development_only" | "test_fixture" | "configuration_required";
  detail: string;
}

interface DailyRow extends Bar {
  preClose?: number;
}

interface TushareResponse {
  code?: unknown;
  msg?: unknown;
  data?: {
    fields?: unknown;
    items?: unknown;
  };
}

export class TushareDailyMarketDataProvider implements MarketDataProvider {
  constructor(
    private readonly token: string,
    private readonly fetcher: Fetcher = fetch,
  ) {
    if (!token.trim()) throw new Error("TUSHARE_TOKEN is required");
  }

  async quote(symbol: string, now: Date): Promise<MarketEnvelope<Record<string, number | string>>> {
    const rows = await this.dailyRows(symbol, undefined, undefined, now, QUOTE_LOOKBACK_DAYS);
    const latest = requireLatest(rows);
    const previousClose = latest.preClose ?? rows.at(-2)?.close ?? latest.open;
    const change = latest.close - previousClose;
    return envelope(symbol, TUSHARE_SOURCE, latest.time, now, {
      price: latest.close,
      change: round(change),
      changePercent: previousClose === 0 ? 0 : round((change / previousClose) * 100),
      volume: latest.volume,
      dataLabel: "延时日线数据",
    });
  }

  async bars(
    symbol: string,
    period: Period,
    from: string | undefined,
    to: string | undefined,
    now: Date,
  ): Promise<MarketEnvelope<Bar[]>> {
    if (period === "intraday") throw new Error("Tushare zero-budget source does not provide intraday data");
    const lookbackDays = period === "month" ? MONTH_LOOKBACK_DAYS : period === "week" ? WEEK_LOOKBACK_DAYS : DAY_LOOKBACK_DAYS;
    const rows = await this.dailyRows(symbol, from, to, now, lookbackDays);
    const data = period === "day" ? rows : aggregateDailyRows(rows, period);
    const latest = requireLatest(data);
    return envelope(symbol, TUSHARE_SOURCE, latest.time, now, data);
  }

  private async dailyRows(
    symbol: string,
    from: string | undefined,
    to: string | undefined,
    now: Date,
    lookbackDays: number,
  ): Promise<DailyRow[]> {
    const endDate = compactDate(to ?? shanghaiDate(now));
    const startDate = compactDate(from ?? shanghaiDate(new Date(now.getTime() - lookbackDays * 24 * 60 * 60 * 1_000)));
    const response = await this.fetcher(TUSHARE_API_URL, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({
        api_name: ["000001.SH", "399001.SZ", "399006.SZ"].includes(symbol) ? "index_daily" : "daily",
        token: this.token,
        params: { ts_code: symbol, start_date: startDate, end_date: endDate },
        fields: "ts_code,trade_date,open,high,low,close,pre_close,vol",
      }),
      signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS),
    });
    if (!response.ok) throw new Error(`Tushare HTTP ${response.status}`);
    const payload = await response.json() as TushareResponse;
    if (payload.code !== 0) throw new Error(`Tushare API ${String(payload.code ?? "invalid")}`);
    const fields = payload.data?.fields;
    const items = payload.data?.items;
    if (!Array.isArray(fields) || !Array.isArray(items)) throw new Error("Tushare response schema is invalid");
    const indexes = fieldIndexes(fields);
    return items.map((item) => parseTushareRow(item, indexes)).sort((a, b) => a.time.localeCompare(b.time));
  }
}

export class AkShareDailyMarketDataProvider implements MarketDataProvider {
  private readonly endpoint: URL;

  constructor(baseUrl: string, private readonly fetcher: Fetcher = fetch) {
    const parsed = new URL(baseUrl);
    if (parsed.protocol !== "https:" || parsed.username || parsed.password) {
      throw new Error("AKShare AKTools gateway must use HTTPS without embedded credentials");
    }
    this.endpoint = new URL("api/public/stock_zh_a_hist", ensureTrailingSlash(parsed));
  }

  async quote(symbol: string, now: Date): Promise<MarketEnvelope<Record<string, number | string>>> {
    const rows = await this.dailyRows(symbol, undefined, undefined, now, QUOTE_LOOKBACK_DAYS);
    const latest = requireLatest(rows);
    const previousClose = rows.at(-2)?.close ?? latest.open;
    const change = latest.close - previousClose;
    return envelope(symbol, AKSHARE_SOURCE, latest.time, now, {
      price: latest.close,
      change: round(change),
      changePercent: previousClose === 0 ? 0 : round((change / previousClose) * 100),
      volume: latest.volume,
      dataLabel: "测试日线数据",
    });
  }

  async bars(
    symbol: string,
    period: Period,
    from: string | undefined,
    to: string | undefined,
    now: Date,
  ): Promise<MarketEnvelope<Bar[]>> {
    if (period === "intraday") throw new Error("AKShare development source does not provide approved intraday data");
    const lookbackDays = period === "month" ? MONTH_LOOKBACK_DAYS : period === "week" ? WEEK_LOOKBACK_DAYS : DAY_LOOKBACK_DAYS;
    const rows = await this.dailyRows(symbol, from, to, now, lookbackDays);
    const data = period === "day" ? rows : aggregateDailyRows(rows, period);
    const latest = requireLatest(data);
    return envelope(symbol, AKSHARE_SOURCE, latest.time, now, data);
  }

  private async dailyRows(
    symbol: string,
    from: string | undefined,
    to: string | undefined,
    now: Date,
    lookbackDays: number,
  ): Promise<DailyRow[]> {
    const url = new URL(this.endpoint);
    url.searchParams.set("symbol", symbol.slice(0, 6));
    url.searchParams.set("period", "daily");
    url.searchParams.set("start_date", compactDate(from ?? shanghaiDate(new Date(now.getTime() - lookbackDays * 24 * 60 * 60 * 1_000))));
    url.searchParams.set("end_date", compactDate(to ?? shanghaiDate(now)));
    url.searchParams.set("adjust", "");
    const response = await this.fetcher(url, { signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS) });
    if (!response.ok) throw new Error(`AKTools HTTP ${response.status}`);
    const payload = await response.json() as unknown;
    if (!Array.isArray(payload)) throw new Error("AKTools response schema is invalid");
    return payload.map(parseAkShareRow).sort((a, b) => a.time.localeCompare(b.time));
  }
}

export class DevelopmentMarketDataProvider implements MarketDataProvider {
  constructor(
    private readonly dailyProviders: MarketDataProvider[],
    private readonly fixture = new FixtureMarketDataProvider(),
  ) {}

  async quote(symbol: string, now: Date): Promise<MarketEnvelope<Record<string, number | string>>> {
    for (const provider of this.dailyProviders) {
      try {
        return await provider.quote(symbol, now);
      } catch {
        // Research sources are best-effort. The deterministic fixture preserves the
        // development UI loop while its source label prevents a real-time claim.
      }
    }
    return this.fixture.quote(symbol, now);
  }

  async bars(symbol: string, period: Period, from: string | undefined, to: string | undefined, now: Date): Promise<MarketEnvelope<Bar[]>> {
    if (period !== "intraday") {
      for (const provider of this.dailyProviders) {
        try {
          return await provider.bars(symbol, period, from, to, now);
        } catch {
          // Continue to the next development source, then the fixed fixture.
        }
      }
    }
    return this.fixture.bars(symbol, period, from, to, now);
  }
}

export function createDevelopmentMarketData(env: NodeJS.ProcessEnv = process.env): {
  provider: MarketDataProvider;
  providerId: string;
  providers: MarketProviderStatus[];
} {
  const dailyProviders: MarketDataProvider[] = [];
  const statuses: MarketProviderStatus[] = [];
  const tushareToken = env.TUSHARE_TOKEN?.trim();
  if (tushareToken) {
    dailyProviders.push(new TushareDailyMarketDataProvider(tushareToken));
    statuses.push({ id: "tushare", name: "Tushare 日线", status: "development_only", detail: "已配置 · 未复权延时数据 · 仅开发研究" });
  } else {
    statuses.push({ id: "tushare", name: "Tushare 日线", status: "configuration_required", detail: "未配置 TUSHARE_TOKEN · 不会请求上游" });
  }

  const akShareBaseUrl = env.AKSHARE_HTTPS_BASE_URL?.trim();
  if (akShareBaseUrl) {
    dailyProviders.push(new AkShareDailyMarketDataProvider(akShareBaseUrl));
    statuses.push({ id: "akshare", name: "AKShare 补充源", status: "development_only", detail: "已配置 AKTools HTTPS 网关 · 仅开发研究" });
  } else {
    statuses.push({ id: "akshare", name: "AKShare 补充源", status: "configuration_required", detail: "未配置 AKSHARE_HTTPS_BASE_URL · 默认禁用" });
  }
  statuses.push({ id: "fixture", name: "固定测试行情", status: "test_fixture", detail: "始终可用 · 非实时 · 上游失败时回退" });

  return {
    provider: new DevelopmentMarketDataProvider(dailyProviders),
    providerId: [...dailyProviders.map((provider) => provider instanceof TushareDailyMarketDataProvider ? "tushare" : "akshare"), "fixture"].join("+"),
    providers: statuses,
  };
}

function fieldIndexes(fields: unknown[]): Record<string, number> {
  const required = ["trade_date", "open", "high", "low", "close", "vol"];
  const indexes = Object.fromEntries(fields.map((field, index) => [String(field), index]));
  for (const name of required) if (indexes[name] === undefined) throw new Error(`Tushare field missing: ${name}`);
  return indexes;
}

function parseTushareRow(item: unknown, indexes: Record<string, number>): DailyRow {
  if (!Array.isArray(item)) throw new Error("Tushare row is invalid");
  return {
    time: marketCloseIso(String(item[indexes.trade_date!] ?? "")),
    open: finiteNumber(item[indexes.open!], "open"),
    high: finiteNumber(item[indexes.high!], "high"),
    low: finiteNumber(item[indexes.low!], "low"),
    close: finiteNumber(item[indexes.close!], "close"),
    preClose: indexes.pre_close === undefined ? undefined : finiteNumber(item[indexes.pre_close], "pre_close"),
    volume: finiteNumber(item[indexes.vol!], "vol"),
  };
}

function parseAkShareRow(item: unknown): DailyRow {
  if (!item || typeof item !== "object") throw new Error("AKTools row is invalid");
  const row = item as Record<string, unknown>;
  return {
    time: marketCloseIso(String(row["日期"] ?? row.date ?? "")),
    open: finiteNumber(row["开盘"] ?? row.open, "open"),
    high: finiteNumber(row["最高"] ?? row.high, "high"),
    low: finiteNumber(row["最低"] ?? row.low, "low"),
    close: finiteNumber(row["收盘"] ?? row.close, "close"),
    volume: finiteNumber(row["成交量"] ?? row.volume, "volume"),
  };
}

function aggregateDailyRows(rows: DailyRow[], period: "week" | "month"): Bar[] {
  const grouped = new Map<string, DailyRow[]>();
  for (const row of rows) {
    const date = new Date(row.time);
    const key = period === "month" ? row.time.slice(0, 7) : isoWeekKey(date);
    const group = grouped.get(key) ?? [];
    group.push(row);
    grouped.set(key, group);
  }
  return [...grouped.values()].map((group) => {
    const first = group[0]!;
    const last = group.at(-1)!;
    return {
      time: last.time,
      open: first.open,
      high: Math.max(...group.map((row) => row.high)),
      low: Math.min(...group.map((row) => row.low)),
      close: last.close,
      volume: group.reduce((sum, row) => sum + row.volume, 0),
    };
  });
}

function isoWeekKey(date: Date): string {
  const target = new Date(Date.UTC(date.getUTCFullYear(), date.getUTCMonth(), date.getUTCDate()));
  const day = target.getUTCDay() || 7;
  target.setUTCDate(target.getUTCDate() + 4 - day);
  const yearStart = new Date(Date.UTC(target.getUTCFullYear(), 0, 1));
  const week = Math.ceil((((target.getTime() - yearStart.getTime()) / 86_400_000) + 1) / 7);
  return `${target.getUTCFullYear()}-${String(week).padStart(2, "0")}`;
}

function envelope<T>(symbol: string, source: string, marketTime: string, now: Date, data: T): MarketEnvelope<T> {
  const freshness = conservativeFreshness(marketTime, now);
  return {
    symbol,
    source,
    marketTime,
    fetchedAt: now.toISOString(),
    freshness: freshness.status,
    freshnessReason: freshness.reason,
    data,
  };
}

function conservativeFreshness(marketTime: string, now: Date): { status: Freshness; reason: string } {
  const age = now.getTime() - new Date(marketTime).getTime();
  if (!Number.isFinite(age) || age < 0) return { status: "UNKNOWN", reason: "开发数据源时间不可验证" };
  if (age <= CONSERVATIVE_RECENCY_MS) return { status: "DELAYED", reason: "开发研究日线源，不能作为实时行情" };
  return { status: "STALE", reason: "开发研究日线早于当前日期，不能作为实时行情" };
}

function requireLatest<T extends Bar>(rows: T[]): T {
  const latest = rows.at(-1);
  if (!latest) throw new Error("Market data source returned no rows");
  return latest;
}

function marketCloseIso(value: string): string {
  const normalized = /^\d{8}$/.test(value)
    ? `${value.slice(0, 4)}-${value.slice(4, 6)}-${value.slice(6, 8)}`
    : value.slice(0, 10);
  if (!/^\d{4}-\d{2}-\d{2}$/.test(normalized)) throw new Error("Market date is invalid");
  const date = new Date(`${normalized}T15:00:00+08:00`);
  if (Number.isNaN(date.getTime())) throw new Error("Market date is invalid");
  return date.toISOString();
}

function shanghaiDate(date: Date): string {
  const parts = new Intl.DateTimeFormat("en-US", {
    timeZone: "Asia/Shanghai",
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
  }).formatToParts(date);
  const values = Object.fromEntries(parts.map((part) => [part.type, part.value]));
  return `${values.year}-${values.month}-${values.day}`;
}

function compactDate(value: string): string {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value)) throw new Error("ISO date is required");
  return value.replaceAll("-", "");
}

function finiteNumber(value: unknown, name: string): number {
  const number = typeof value === "number" ? value : Number(value);
  if (!Number.isFinite(number)) throw new Error(`Market field ${name} is invalid`);
  return number;
}

function ensureTrailingSlash(url: URL): URL {
  const copy = new URL(url);
  if (!copy.pathname.endsWith("/")) copy.pathname += "/";
  return copy;
}

function round(value: number): number {
  return Number(value.toFixed(4));
}
