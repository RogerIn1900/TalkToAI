import test from "node:test";
import assert from "node:assert/strict";
import { DeepSeekModel } from "../src/deepseek";

test("DeepSeek adapter sends the official HTTPS streaming contract without exposing the key", async () => {
  let requestedUrl = "";
  let requestedInit: RequestInit | undefined;
  const model = new DeepSeekModel("test-secret", async (input, init) => {
    requestedUrl = input.toString();
    requestedInit = init;
    const body = [
      'data: {"choices":[{"delta":{"content":"你"}}]}',
      'data: {"choices":[{"delta":{"content":"好"}}],"usage":{"total_tokens":3}}',
      "data: [DONE]",
      "",
    ].join("\n\n");
    return new Response(body, { status: 200, headers: { "content-type": "text/event-stream" } });
  });

  const result = await model.streamText({
    model: "deepseek-flash",
    messages: [{ role: "user", content: "测试" }],
  });
  let text = "";
  for await (const delta of result.textStream) text += delta;

  assert.equal(requestedUrl, "https://api.deepseek.com/chat/completions");
  assert.equal(requestedInit?.method, "POST");
  assert.equal(new Headers(requestedInit?.headers).get("authorization"), "Bearer test-secret");
  const payload = JSON.parse(requestedInit?.body as string);
  assert.equal(payload.model, "deepseek-flash");
  assert.equal(payload.stream, true);
  assert.equal(payload.thinking.type, "disabled");
  assert.equal(text, "你好");
  assert.deepEqual(await result.usage, { total_tokens: 3 });
});

test("DeepSeek adapter rejects non-HTTPS endpoints and image content before sending", async () => {
  assert.throws(() => new DeepSeekModel("test-secret", fetch, "http://api.example.test"), /https/);
  let requests = 0;
  const model = new DeepSeekModel("test-secret", async () => {
    requests += 1;
    return new Response(null, { status: 200 });
  });
  await assert.rejects(
    model.streamText({
      model: "deepseek-flash",
      messages: [{ role: "user", content: [{ type: "image_url", image_url: { url: "data:image/png;base64,AA==" } }] }],
    }),
    /does-not-support-image/,
  );
  assert.equal(requests, 0);
});

test("DeepSeek upstream errors only expose status and never response credentials", async () => {
  const model = new DeepSeekModel("never-log-this", async () =>
    new Response('{"error":{"message":"credential never-log-this"}}', { status: 401 }));
  await assert.rejects(
    model.streamText({ model: "deepseek-flash", messages: [{ role: "user", content: "测试" }] }),
    (error: Error) => error.message === "deepseek-http-401" && !error.message.includes("never-log-this"),
  );
});

test("DeepSeek adapter flushes a final SSE event without a trailing blank separator", async () => {
  const model = new DeepSeekModel("test-secret", async () =>
    new Response('data: {"choices":[{"delta":{"content":"完整"}}]}', { status: 200 }));
  const result = await model.streamText({
    model: "deepseek-flash",
    messages: [{ role: "user", content: "测试" }],
  });

  let text = "";
  for await (const delta of result.textStream) text += delta;

  assert.equal(text, "完整");
});
