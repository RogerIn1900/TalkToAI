import test from "node:test";
import assert from "node:assert/strict";
import { once } from "node:events";
import { createApp } from "../src/app";
import { FixtureMarketDataProvider } from "../src/fixtures";
import { MemoryQuotaStore } from "../src/quota";

async function withServer(run: (baseUrl: string) => Promise<void>): Promise<void> {
  const app = createApp({
    quota: new MemoryQuotaStore(1),
    market: new FixtureMarketDataProvider(),
    createAiModel: () => ({
      streamText: async () => ({
        textStream: (async function* () { yield "测试 https://example.com/source"; })(),
        usage: Promise.resolve({ totalTokens: 2 }),
      }),
    }),
    uploadAttachment: async (cloudPath, content) => ({ fileID: `cloud://${cloudPath}?bytes=${content.length}` }),
    now: () => new Date("2026-09-05T01:00:00Z"),
  });
  app.listen(0, "127.0.0.1");
  await once(app, "listening");
  const address = app.address();
  assert.ok(address && typeof address !== "string");
  try {
    await run(`http://127.0.0.1:${address.port}`);
  } finally {
    app.close();
    await once(app, "close");
  }
}

test("health does not expose credentials", () => withServer(async (baseUrl) => {
  const response = await fetch(`${baseUrl}/health`);
  const text = await response.text();
  assert.equal(response.status, 200);
  assert.equal(text.includes("CLOUDBASE"), false);
  assert.equal(JSON.parse(text).status, "ok");
}));

test("market rejects unsupported symbol", () => withServer(async (baseUrl) => {
  const response = await fetch(`${baseUrl}/v1/market/quote?symbol=AAPL`);
  assert.equal(response.status, 400);
  assert.equal((await response.json() as any).error.code, "INVALID_ARGUMENT");
}));

test("attachment upload validates metadata and returns a controlled object reference", () => withServer(async (baseUrl) => {
  const response = await fetch(`${baseUrl}/v1/attachments/abcdef0123456789`, {
    method: "PUT",
    headers: {
      "content-type": "text/plain",
      "x-installation-id": "install_1234567890abcdef",
      "x-file-name": encodeURIComponent("notes.txt"),
    },
    body: "fixture text",
  });
  const body = await response.json() as any;
  assert.equal(response.status, 201);
  assert.equal(body.sizeBytes, 12);
  assert.match(body.objectRef, /^cloud:\/\/talktoai\/v1\//);
}));

test("chat streams meta deltas and done", () => withServer(async (baseUrl) => {
  const response = await fetch(`${baseUrl}/v1/chat/completions`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({
      installationId: "install_1234567890abcdef",
      conversationId: "conversation-1",
      messages: [{ role: "user", content: "解释测试行情" }],
      stream: true,
    }),
  });
  const text = await response.text();
  assert.equal(response.status, 200);
  assert.match(response.headers.get("content-type") ?? "", /text\/event-stream/);
  assert.match(text, /event: meta/);
  assert.match(text, /event: delta/);
  assert.match(text, /event: citation/);
  assert.match(text, /event: done/);
}));

test("chat enforces daily quota", () => withServer(async (baseUrl) => {
  const body = JSON.stringify({
    installationId: "install_1234567890abcdef",
    conversationId: "conversation-1",
    messages: [{ role: "user", content: "第一次" }],
    stream: true,
  });
  await fetch(`${baseUrl}/v1/chat/completions`, { method: "POST", headers: { "content-type": "application/json" }, body });
  const response = await fetch(`${baseUrl}/v1/chat/completions`, { method: "POST", headers: { "content-type": "application/json" }, body });
  assert.equal(response.status, 429);
  assert.equal((await response.json() as any).error.code, "DAILY_QUOTA_EXCEEDED");
}));

test("unconfigured AI fails before consuming quota", async () => {
  let consumeCount = 0;
  const app = createApp({
    quota: {
      consume: async () => {
        consumeCount += 1;
        return { allowed: true, used: 1, limit: 500, resetAt: "2026-09-05T16:00:00.000Z" };
      },
    },
    market: new FixtureMarketDataProvider(),
    aiConfigured: false,
    createAiModel: () => { throw new Error("must not be called"); },
    now: () => new Date("2026-09-05T01:00:00Z"),
  });
  app.listen(0, "127.0.0.1");
  await once(app, "listening");
  const address = app.address();
  assert.ok(address && typeof address !== "string");
  try {
    const response = await fetch(`http://127.0.0.1:${address.port}/v1/chat/completions`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({
        installationId: "install_1234567890abcdef",
        conversationId: "conversation-1",
        messages: [{ role: "user", content: "测试未配置" }],
        stream: true,
      }),
    });
    assert.equal(response.status, 503);
    assert.equal((await response.json() as any).error.code, "AI_NOT_CONFIGURED");
    assert.equal(consumeCount, 0);
  } finally {
    app.close();
    await once(app, "close");
  }
});
