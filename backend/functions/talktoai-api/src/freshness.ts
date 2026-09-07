import type { Freshness, Period } from "./types";

const ONE_MINUTE_MS = 60_000;
const FIVE_MINUTES_MS = 5 * ONE_MINUTE_MS;
const ONE_DAY_MS = 24 * 60 * ONE_MINUTE_MS;

export interface FreshnessInput {
  period: Period;
  now: Date;
  marketTime: Date | null;
  fetchedAt: Date;
  isTradingSession: boolean;
  latestTradingDay: string | null;
}

export function evaluateFreshness(input: FreshnessInput): { status: Freshness; reason: string } {
  if (!input.marketTime || Number.isNaN(input.marketTime.getTime()) || !input.latestTradingDay) {
    return { status: "UNKNOWN", reason: "交易日历或数据时间不可验证" };
  }
  const age = input.now.getTime() - input.marketTime.getTime();
  if (age < 0) return { status: "UNKNOWN", reason: "数据时间晚于设备时间" };

  if (input.period === "intraday") {
    if (input.isTradingSession) {
      if (age <= ONE_MINUTE_MS) return { status: "FRESH", reason: "交易时段内数据延迟不超过60秒" };
      if (age <= FIVE_MINUTES_MS) return { status: "DELAYED", reason: "交易时段内数据延迟为1至5分钟" };
      return { status: "STALE", reason: "交易时段内数据延迟超过5分钟" };
    }
    const marketDay = input.marketTime.toISOString().slice(0, 10);
    if (marketDay === input.latestTradingDay && input.fetchedAt.getTime() - input.marketTime.getTime() <= ONE_DAY_MS) {
      return { status: "FRESH", reason: "非交易时段，数据属于最近交易日" };
    }
    return { status: "STALE", reason: "非交易时段，数据早于最近交易日" };
  }

  const marketDay = input.marketTime.toISOString().slice(0, 10);
  if (marketDay < input.latestTradingDay) return { status: "STALE", reason: "缺少最近交易日数据" };
  return { status: "FRESH", reason: `${input.period}周期包含最近交易日` };
}
