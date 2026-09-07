import test from "node:test";
import assert from "node:assert/strict";
import { evaluateFreshness } from "../src/freshness";

test("intraday is fresh within 60 seconds during trading", () => {
  const result = evaluateFreshness({
    period: "intraday",
    now: new Date("2026-09-04T02:01:00Z"),
    marketTime: new Date("2026-09-04T02:00:10Z"),
    fetchedAt: new Date("2026-09-04T02:01:00Z"),
    isTradingSession: true,
    latestTradingDay: "2026-09-04",
  });
  assert.equal(result.status, "FRESH");
});

test("intraday is stale after five minutes during trading", () => {
  const result = evaluateFreshness({
    period: "intraday",
    now: new Date("2026-09-04T02:06:00Z"),
    marketTime: new Date("2026-09-04T02:00:00Z"),
    fetchedAt: new Date("2026-09-04T02:06:00Z"),
    isTradingSession: true,
    latestTradingDay: "2026-09-04",
  });
  assert.equal(result.status, "STALE");
});

test("unknown is returned when calendar is unavailable", () => {
  const result = evaluateFreshness({
    period: "day",
    now: new Date("2026-09-04T10:00:00Z"),
    marketTime: new Date("2026-09-04T07:00:00Z"),
    fetchedAt: new Date("2026-09-04T10:00:00Z"),
    isTradingSession: false,
    latestTradingDay: null,
  });
  assert.equal(result.status, "UNKNOWN");
});
