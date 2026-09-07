import test from "node:test";
import assert from "node:assert/strict";
import { CloudBaseSqlQuotaStore, MemoryQuotaStore, quotaDocumentId } from "../src/quota";

const installationId = "install_1234567890abcdef";

test("quota permits requests up to limit and then rejects", async () => {
  const store = new MemoryQuotaStore(2);
  const now = new Date("2026-09-04T15:59:59Z");
  assert.equal((await store.consume(installationId, now)).allowed, true);
  assert.equal((await store.consume(installationId, now)).allowed, true);
  const rejected = await store.consume(installationId, now);
  assert.equal(rejected.allowed, false);
  assert.equal(rejected.used, 2);
});

test("quota resets at Asia Shanghai natural day", async () => {
  const store = new MemoryQuotaStore(1);
  const beforeMidnight = await store.consume(installationId, new Date("2026-09-04T15:59:59Z"));
  const afterMidnight = await store.consume(installationId, new Date("2026-09-04T16:00:01Z"));
  assert.equal(beforeMidnight.allowed, true);
  assert.equal(afterMidnight.allowed, true);
});

test("quota document id hashes installation id", () => {
  const id = quotaDocumentId(installationId, new Date("2026-09-04T10:00:00Z"));
  assert.equal(id.includes(installationId), false);
  assert.match(id, /^2026-09-04_[a-f0-9]{64}$/);
});

test("SQL quota maps atomic database result", async () => {
  const calls: Array<Record<string, unknown>> = [];
  const quota = new CloudBaseSqlQuotaStore({
    $runSQL: async (_sql, params) => {
      calls.push(params);
      return { data: { executeResultList: [{ used: "3", allowed: "true" }] } };
    },
  }, 500);

  const result = await quota.consume("installation-1234", new Date("2026-09-07T04:00:00.000Z"));

  assert.deepEqual(result, {
    allowed: true,
    used: 3,
    limit: 500,
    resetAt: "2026-09-07T16:00:00.000Z",
  });
  assert.equal(calls.length, 1);
  assert.match(String(calls[0]?.id), /^2026-09-07_[a-f0-9]{64}$/);
});

test("SQL quota rejects malformed database result", async () => {
  const quota = new CloudBaseSqlQuotaStore({
    $runSQL: async () => ({ data: { executeResultList: [{ used: "invalid", allowed: true }] } }),
  });

  await assert.rejects(() => quota.consume("installation-1234", new Date()), /invalid response/);
});
