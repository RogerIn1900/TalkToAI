import test from "node:test";
import assert from "node:assert/strict";
import { FixtureMarketDataProvider } from "../src/fixtures";

test("fixture never labels historical data as real-time fresh", async () => {
  const result = await new FixtureMarketDataProvider().bars(
    "600000.SH", "intraday", undefined, undefined, new Date("2026-09-07T03:00:00Z"),
  );
  assert.equal(result.freshness, "STALE");
  assert.match(result.source, /非实时行情/);
});

test("weekly fixture aggregates OHLCV into one bar", async () => {
  const result = await new FixtureMarketDataProvider().bars(
    "600000.SH", "week", undefined, undefined, new Date("2026-09-07T03:00:00Z"),
  );
  assert.equal(result.data.length, 1);
  assert.equal(result.data[0]?.open, 10.02);
  assert.equal(result.data[0]?.close, 10.31);
  assert.equal(result.data[0]?.volume, 8066700);
});
