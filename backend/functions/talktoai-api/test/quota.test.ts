import test from "node:test";
import assert from "node:assert/strict";
import { MemoryQuotaStore, quotaDocumentId } from "../src/quota";

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
