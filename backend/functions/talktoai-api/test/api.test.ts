import test from "node:test";
import assert from "node:assert/strict";
import { once } from "node:events";
import { createApp } from "../src/app";
import { FixtureMarketDataProvider } from "../src/fixtures";
import type { MarketProviderStatus } from "../src/market-data";
import { MemoryQuotaStore } from "../src/quota";

async function withServer(
  run: (baseUrl: string) => Promise<void>,
  onModelMessages: (messages: Array<{ role: string; content: unknown }>) => void = () => undefined,
  marketProviders: MarketProviderStatus[] = [],
  market = new FixtureMarketDataProvider(),
): Promise<void> {
  const uploaded = new Map<string, Buffer>();
  const app = createApp({
    quota: new MemoryQuotaStore(1),
    market,
    marketProvider: marketProviders.length > 0 ? "tushare+fixture" : "fixture",
    marketProviders,
    createAiModel: () => ({
      streamText: async ({ messages }) => {
        onModelMessages(messages);
        return {
          textStream: (async function* () { yield "测试 https://example.com/source"; })(),
          usage: Promise.resolve({ totalTokens: 2 }),
        };
      },
    }),
    uploadAttachment: async (cloudPath, content) => {
      const fileID = `cloud://${cloudPath}`;
      uploaded.set(fileID, content);
      return { fileID };
    },
    downloadAttachment: async (fileID) => {
      const content = uploaded.get(fileID);
      if (!content) throw new Error("fixture-attachment-not-found");
      return content;
    },
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

test("overview retains available indices when the first source fails", () => withServer(async (baseUrl) => {
  const response = await fetch(`${baseUrl}/v1/chat/completions`, {
    method: "POST", headers: { "content-type": "application/json" },
    body: JSON.stringify({ installationId: "install_1234567890abcdef", conversationId: "overview-partial", messages: [{ role: "user", content: "今天大盘数据" }], stream: true }),
  });
  assert.equal(response.status, 200);
  const text = await response.text();
  assert.match(text, /399001\.SZ/);
  assert.match(text, /"unavailable":\["000001.SH"\]/);
}, () => undefined, [], new class extends FixtureMarketDataProvider {
  override async bars(...args: Parameters<FixtureMarketDataProvider["bars"]>) {
    if (args[0] === "000001.SH") throw new Error("fixture upstream unavailable");
    return super.bars(...args);
  }
}()));

test("unavailable overview returns retryable error without consuming the model quota", () => withServer(async (baseUrl) => {
  const request = (content: string) => fetch(`${baseUrl}/v1/chat/completions`, {
    method: "POST", headers: { "content-type": "application/json" },
    body: JSON.stringify({ installationId: "install_1234567890abcdef", conversationId: "overview-failure", messages: [{ role: "user", content }], stream: true }),
  });
  const unavailable = await request("今天大盘数据");
  assert.equal(unavailable.status, 503);
  assert.equal((await unavailable.json() as any).error.code, "MARKET_UNAVAILABLE");
  const normal = await request("解释市盈率");
  assert.equal(normal.status, 200);
  assert.match(await normal.text(), /event: done/);
}, () => undefined, [], new class extends FixtureMarketDataProvider {
  override async bars(..._args: Parameters<FixtureMarketDataProvider["bars"]>): ReturnType<FixtureMarketDataProvider["bars"]> {
    throw new Error("fixture upstream unavailable");
  }
}()));

test("health does not expose credentials", () => withServer(async (baseUrl) => {
  const response = await fetch(`${baseUrl}/health`);
  const text = await response.text();
  assert.equal(response.status, 200);
  assert.equal(text.includes("CLOUDBASE"), false);
  assert.equal(JSON.parse(text).status, "ok");
}));

test("health publishes the development source catalog without credentials", () => withServer(async (baseUrl) => {
  const response = await fetch(`${baseUrl}/health`);
  const text = await response.text();
  const body = JSON.parse(text);
  assert.equal(response.status, 200);
  assert.equal(body.capabilities.marketProvider, "tushare+fixture");
  assert.equal(body.capabilities.marketProviders[0].status, "development_only");
  assert.equal(text.includes("secret-token"), false);
}, () => undefined, [{
  id: "tushare",
  name: "Tushare 日线",
  status: "development_only",
  detail: "已配置 · 延时数据 · 仅开发研究",
}]));

test("market intent streams chart data before AI and injects freshness context", () => {
  let modelMessages: Array<{ role: string; content: unknown }> = [];
  return withServer(async (baseUrl) => {
    const response = await fetch(`${baseUrl}/v1/chat/completions`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({
        installationId: "install_1234567890abcdef",
        conversationId: "conversation-market",
        messages: [{ role: "user", content: "今日大盘数据怎么样" }],
        stream: true,
      }),
    });
    const text = await response.text();
    assert.equal(response.status, 200);
    assert.ok(text.indexOf("event: market") < text.indexOf("event: delta"));
    assert.match(text, /000001\.SH/);
    assert.match(text, /399001\.SZ/);
    assert.match(text, /399006\.SZ/);
    assert.match(text, /"overview"/);
    assert.match(text, /STALE/);
    assert.equal(modelMessages.some((message) => typeof message.content === "string" && message.content.includes("MARKET_CONTEXT") && message.content.includes("STALE")), true);
    const latestUserMessage = [...modelMessages].reverse().find((message) => message.role === "user");
    assert.equal(typeof latestUserMessage?.content, "string");
    assert.match(latestUserMessage?.content as string, /行情工具数据已经成功返回/);
    assert.match(latestUserMessage?.content as string, /MARKET_CONTEXT/);
  }, (messages) => { modelMessages = messages; });
});

test("latest market tool result overrides a stale assistant denial in long conversation context", () => {
  let modelMessages: Array<{ role: string; content: unknown }> = [];
  return withServer(async (baseUrl) => {
    const response = await fetch(`${baseUrl}/v1/chat/completions`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({
        installationId: "install_long_market_123456",
        conversationId: "conversation-long-market",
        messages: [
          { role: "user", content: "帮我看看行情" },
          { role: "assistant", content: "当前没有 MARKET_CONTEXT，无法读取数据。" },
          { role: "user", content: "今日大盘数据怎么样" },
        ],
        stream: true,
      }),
    });
    assert.equal(response.status, 200);
    await response.text();
    const latestUserMessage = [...modelMessages].reverse().find((message) => message.role === "user");
    assert.equal(typeof latestUserMessage?.content, "string");
    assert.match(latestUserMessage?.content as string, /不得声称 MARKET_CONTEXT 缺失/);
    assert.match(latestUserMessage?.content as string, /symbol=000001\.SH/);
    assert.equal(modelMessages.some((message) => message.role === "assistant" && message.content === "当前没有 MARKET_CONTEXT，无法读取数据。"), true);
  }, (messages) => { modelMessages = messages; });
});

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

test("uploaded TXT enters model context with an attachment source marker", async () => {
  let modelMessages: Array<{ role: string; content: unknown }> = [];
  await withServer(async (baseUrl) => {
    const installationId = "install_1234567890abcdef";
    const attachmentId = "abcdef0123456789";
    const upload = await fetch(`${baseUrl}/v1/attachments/${attachmentId}`, {
      method: "PUT",
      headers: {
        "content-type": "text/plain",
        "x-installation-id": installationId,
        "x-file-name": encodeURIComponent("notes.txt"),
      },
      body: "浦发银行收盘价为10.25",
    });
    const attachment = await upload.json() as any;
    const response = await fetch(`${baseUrl}/v1/chat/completions`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({
        installationId,
        conversationId: "conversation-attachment",
        messages: [{ role: "user", content: "总结附件" }],
        attachments: [{
          id: attachment.id,
          name: attachment.name,
          mimeType: attachment.mimeType,
          sizeBytes: attachment.sizeBytes,
          objectRef: attachment.objectRef,
        }],
        stream: true,
      }),
    });
    const text = await response.text();
    assert.equal(response.status, 200);
    assert.match(text, /"kind":"attachment"/);
    const latestUser = [...modelMessages].reverse().find((message) => message.role === "user");
    assert.ok(Array.isArray(latestUser?.content));
    assert.match(JSON.stringify(latestUser?.content), /浦发银行收盘价为10\.25/);
    assert.match(JSON.stringify(latestUser?.content), /附件：notes\.txt/);
  }, (messages) => { modelMessages = messages; });
});

test("chat rejects attachment object references owned by another installation", async () => {
  await withServer(async (baseUrl) => {
    const response = await fetch(`${baseUrl}/v1/chat/completions`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({
        installationId: "install_1234567890abcdef",
        conversationId: "conversation-foreign-attachment",
        messages: [{ role: "user", content: "读取附件" }],
        attachments: [{
          id: "abcdef0123456789",
          name: "notes.txt",
          mimeType: "text/plain",
          sizeBytes: 12,
          objectRef: "cloud://talktoai/v1/not-the-owner/abcdef0123456789/notes.txt",
        }],
        stream: true,
      }),
    });
    const body = await response.json() as any;
    assert.equal(response.status, 403);
    assert.equal(body.error.code, "ATTACHMENT_FORBIDDEN");
  });
});

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
