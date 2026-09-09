import test from "node:test";
import assert from "node:assert/strict";
import {
  AkShareDailyMarketDataProvider,
  createDevelopmentMarketData,
  DevelopmentMarketDataProvider,
  TushareDailyMarketDataProvider,
} from "../src/market-data";
import { FixtureMarketDataProvider } from "../src/fixtures";

const NOW = new Date("2026-09-08T08:00:00.000Z");

test("index bars use index_daily instead of the equity daily endpoint", async () => {
  let apiName = "";
  const provider = new TushareDailyMarketDataProvider("test-token", async (_input, init) => {
    apiName = JSON.parse(String(init?.body)).api_name;
    return Response.json({ code: 0, data: {
      fields: ["ts_code", "trade_date", "open", "high", "low", "close", "pre_close", "vol"],
      items: [["000001.SH", "20260908", 3000, 3020, 2990, 3010, 3000, 100]],
    }});
  });
  await provider.bars("000001.SH", "day", undefined, undefined, NOW);
  assert.equal(apiName, "index_daily");
});

test("Tushare provider posts the daily contract over HTTPS and labels data delayed", async () => {
  let requestUrl = "";
  let requestBody: Record<string, any> = {};
  const provider = new TushareDailyMarketDataProvider("secret-token", async (input, init) => {
    requestUrl = input.toString();
    requestBody = JSON.parse(String(init?.body));
    return Response.json({
      code: 0,
      msg: null,
      data: {
        fields: ["ts_code", "trade_date", "open", "high", "low", "close", "pre_close", "vol"],
        items: [
          ["600000.SH", "20260908", 10.1, 10.5, 10.0, 10.4, 10.0, 1200],
          ["600000.SH", "20260907", 9.8, 10.2, 9.7, 10.0, 9.9, 1000],
        ],
      },
    });
  });

  const result = await provider.bars("600000.SH", "day", "2026-09-01", "2026-09-08", NOW);

  assert.equal(requestUrl, "https://api.tushare.pro");
  assert.equal(requestBody.api_name, "daily");
  assert.deepEqual(requestBody.params, { ts_code: "600000.SH", start_date: "20260901", end_date: "20260908" });
  assert.equal(result.data[0]?.close, 10.0, "descending upstream rows must be normalized to ascending time");
  assert.equal(result.data[1]?.close, 10.4);
  assert.equal(result.freshness, "DELAYED");
  assert.match(result.source, /未复权日线（开发研究\/延时数据，非实时/);
  assert.equal(result.source.includes("secret-token"), false);
});

test("Tushare provider aggregates daily OHLCV for week view", async () => {
  const provider = new TushareDailyMarketDataProvider("token", async () => Response.json({
    code: 0,
    data: {
      fields: ["trade_date", "open", "high", "low", "close", "pre_close", "vol"],
      items: [
        ["20260908", 10.2, 10.6, 10.1, 10.5, 10.2, 300],
        ["20260907", 10.0, 10.3, 9.8, 10.2, 9.9, 200],
      ],
    },
  }));

  const result = await provider.bars("600000.SH", "week", "2026-09-07", "2026-09-08", NOW);

  assert.equal(result.data.length, 1);
  assert.deepEqual(result.data[0], {
    time: "2026-09-08T07:00:00.000Z",
    open: 10.0,
    high: 10.6,
    low: 9.8,
    close: 10.5,
    volume: 500,
  });
});

test("Tushare default query dates use the Asia Shanghai calendar day", async () => {
  let requestBody: Record<string, any> = {};
  const provider = new TushareDailyMarketDataProvider("token", async (_input, init) => {
    requestBody = JSON.parse(String(init?.body));
    return Response.json({
      code: 0,
      data: {
        fields: ["trade_date", "open", "high", "low", "close", "pre_close", "vol"],
        items: [["20260908", 10, 10.2, 9.9, 10.1, 10, 100]],
      },
    });
  });

  await provider.quote("600000.SH", new Date("2026-09-08T16:30:00.000Z"));

  assert.equal(requestBody.params.end_date, "20260909", "00:30 in Shanghai belongs to the next calendar day");
});

test("AKShare provider requires an HTTPS AKTools gateway", () => {
  assert.throws(
    () => new AkShareDailyMarketDataProvider("http://127.0.0.1:8080/"),
    /must use HTTPS/,
  );
});

test("AKShare provider maps AKTools Chinese daily fields and keeps a test label", async () => {
  let requestUrl = "";
  const provider = new AkShareDailyMarketDataProvider("https://aktools.example.test/", async (input) => {
    requestUrl = input.toString();
    return Response.json([
      { "日期": "2026-09-07", "开盘": 10, "最高": 10.4, "最低": 9.9, "收盘": 10.2, "成交量": 900 },
      { "日期": "2026-09-08", "开盘": 10.2, "最高": 10.6, "最低": 10.1, "收盘": 10.5, "成交量": 1100 },
    ]);
  });

  const result = await provider.bars("600000.SH", "day", "2026-09-01", "2026-09-08", NOW);

  const url = new URL(requestUrl);
  assert.equal(url.protocol, "https:");
  assert.equal(url.pathname, "/api/public/stock_zh_a_hist");
  assert.equal(url.searchParams.get("symbol"), "600000");
  assert.equal(url.searchParams.get("period"), "daily");
  assert.equal(result.data[1]?.volume, 1100);
  assert.equal(result.freshness, "DELAYED");
  assert.match(result.source, /测试数据，非实时/);
});

test("development provider falls back to deterministic fixture when research source fails", async () => {
  const failing = new TushareDailyMarketDataProvider("token", async () => {
    throw new Error("network unavailable");
  });
  const provider = new DevelopmentMarketDataProvider([failing], new FixtureMarketDataProvider());

  const result = await provider.bars("600000.SH", "day", undefined, undefined, NOW);

  assert.match(result.source, /固定数据（非实时行情）/);
  assert.equal(result.freshness, "STALE");
});

test("development registry reports configured and disabled sources without exposing credentials", () => {
  const unconfigured = createDevelopmentMarketData({});
  assert.equal(unconfigured.providerId, "fixture");
  assert.equal(unconfigured.providers.find((item) => item.id === "tushare")?.status, "configuration_required");

  const configured = createDevelopmentMarketData({
    TUSHARE_TOKEN: "do-not-expose",
    AKSHARE_HTTPS_BASE_URL: "https://aktools.example.test/",
  });
  assert.equal(configured.providerId, "tushare+akshare+fixture");
  assert.equal(configured.providers.find((item) => item.id === "tushare")?.status, "development_only");
  assert.equal(JSON.stringify(configured.providers).includes("do-not-expose"), false);
});
