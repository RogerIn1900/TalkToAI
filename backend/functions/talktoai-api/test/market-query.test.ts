import test from "node:test";
import assert from "node:assert/strict";
import { inferMarketQuery } from "../src/market-query";

const user = (content: string) => [{ role: "user" as const, content }];

test("query planner recognizes an A-share company name without inventing a symbol", () => {
  assert.deepEqual(inferMarketQuery(user("分析一下贵州茅台最近走势")), {
    symbols: ["600519.SH"],
    period: "day",
    overview: false,
    matchedBy: "name",
  });
});

test("query planner keeps multiple securities in mention order", () => {
  assert.deepEqual(inferMarketQuery(user("对比 600519 和宁德时代的周K")), {
    symbols: ["600519.SH", "300750.SZ"],
    period: "week",
    overview: false,
    matchedBy: "code",
  });
});

test("query planner returns the bounded index set for a market overview", () => {
  const plan = inferMarketQuery(user("今天大盘怎么样"));
  assert.deepEqual(plan?.symbols, ["000001.SH", "399001.SZ", "399006.SZ"]);
  assert.equal(plan?.overview, true);
  assert.equal(plan?.matchedBy, "market_overview");
});

test("query planner ignores a general finance explanation", () => {
  assert.equal(inferMarketQuery(user("解释一下市盈率")), undefined);
});
