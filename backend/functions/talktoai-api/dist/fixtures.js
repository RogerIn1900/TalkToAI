"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.FixtureMarketDataProvider = void 0;
const freshness_1 = require("./freshness");
const FIXTURE_SOURCE = "TalkToAI 测试固定数据（非实时行情）";
const BASE_BARS = [
    { time: "2026-09-01T15:00:00+08:00", open: 10.02, high: 10.18, low: 9.96, close: 10.12, volume: 1863200 },
    { time: "2026-09-02T15:00:00+08:00", open: 10.11, high: 10.26, low: 10.05, close: 10.21, volume: 2015600 },
    { time: "2026-09-03T15:00:00+08:00", open: 10.20, high: 10.24, low: 10.01, close: 10.08, volume: 1748800 },
    { time: "2026-09-04T15:00:00+08:00", open: 10.09, high: 10.35, low: 10.07, close: 10.31, volume: 2439100 },
];
class FixtureMarketDataProvider {
    async quote(symbol, now) {
        const last = BASE_BARS[BASE_BARS.length - 1];
        return this.envelope(symbol, "day", now, {
            name: symbol === "600000.SH" ? "浦发银行（测试夹具）" : "A股测试标的",
            price: last.close,
            change: Number((last.close - last.open).toFixed(2)),
            changePercent: Number((((last.close - last.open) / last.open) * 100).toFixed(2)),
            volume: last.volume,
        });
    }
    async bars(symbol, period, from, to, now) {
        let bars = period === "intraday" ? this.intradayBars() : period === "day" ? BASE_BARS : [aggregateBars(BASE_BARS, period)];
        if (from)
            bars = bars.filter((bar) => bar.time.slice(0, 10) >= from);
        if (to)
            bars = bars.filter((bar) => bar.time.slice(0, 10) <= to);
        return this.envelope(symbol, period, now, bars);
    }
    intradayBars() {
        return [
            { time: "2026-09-04T09:30:00+08:00", open: 10.09, high: 10.09, low: 10.09, close: 10.09, volume: 120000 },
            { time: "2026-09-04T10:30:00+08:00", open: 10.18, high: 10.18, low: 10.18, close: 10.18, volume: 510000 },
            { time: "2026-09-04T11:30:00+08:00", open: 10.22, high: 10.22, low: 10.22, close: 10.22, volume: 880000 },
            { time: "2026-09-04T14:00:00+08:00", open: 10.27, high: 10.27, low: 10.27, close: 10.27, volume: 1710000 },
            { time: "2026-09-04T15:00:00+08:00", open: 10.31, high: 10.31, low: 10.31, close: 10.31, volume: 2439100 },
        ];
    }
    envelope(symbol, period, now, data) {
        const marketTime = new Date("2026-09-04T15:00:00+08:00");
        const freshness = (0, freshness_1.evaluateFreshness)({
            period,
            now,
            marketTime,
            fetchedAt: now,
            isTradingSession: false,
            // The fixture has no authoritative exchange calendar. Passing today's date
            // makes old fixture data stale instead of incorrectly claiming it is live.
            latestTradingDay: now.toISOString().slice(0, 10),
        });
        return {
            symbol,
            source: FIXTURE_SOURCE,
            marketTime: marketTime.toISOString(),
            fetchedAt: now.toISOString(),
            freshness: freshness.status,
            freshnessReason: freshness.reason,
            data,
        };
    }
}
exports.FixtureMarketDataProvider = FixtureMarketDataProvider;
function aggregateBars(bars, period) {
    const first = bars[0];
    const last = bars[bars.length - 1];
    return {
        time: last.time,
        open: first.open,
        high: Math.max(...bars.map((bar) => bar.high)),
        low: Math.min(...bars.map((bar) => bar.low)),
        close: last.close,
        volume: bars.reduce((sum, bar) => sum + bar.volume, 0),
    };
}
