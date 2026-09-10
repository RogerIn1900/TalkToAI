interface DeepSeekMessage {
  role: string;
  content: string | Array<
    | { type: "text"; text: string }
    | { type: "image_url"; image_url: { url: string } }
  >;
}

interface DeepSeekStream {
  textStream: AsyncIterable<string>;
  usage: Promise<unknown>;
}

type Fetch = (input: string | URL | Request, init?: RequestInit) => Promise<Response>;

class DeepSeekHttpError extends Error {
  readonly code: string;

  constructor(status: number) {
    super(`deepseek-http-${status}`);
    this.name = "DeepSeekHttpError";
    this.code = `DEEPSEEK_HTTP_${status}`;
  }
}

/** Minimal OpenAI-compatible streaming adapter. The API key never enters errors or logs. */
export class DeepSeekModel {
  constructor(
    private readonly apiKey: string,
    private readonly request: Fetch = fetch,
    private readonly baseUrl = "https://api.deepseek.com",
  ) {
    if (!apiKey.trim()) throw new Error("deepseek-api-key-missing");
    const parsed = new URL(baseUrl);
    if (parsed.protocol !== "https:") throw new Error("deepseek-base-url-must-use-https");
  }

  async streamText(input: { model: string; messages: DeepSeekMessage[] }): Promise<DeepSeekStream> {
    const messages = input.messages.map((message) => ({
      role: message.role,
      content: typeof message.content === "string"
        ? message.content
        : message.content.map((part) => {
          if (part.type !== "text") {
            throw new Error("deepseek-text-model-does-not-support-image-content");
          }
          return part.text;
        }).join("\n\n"),
    }));
    const response = await this.request(`${this.baseUrl.replace(/\/$/, "")}/chat/completions`, {
      method: "POST",
      headers: {
        authorization: `Bearer ${this.apiKey}`,
        "content-type": "application/json",
      },
      body: JSON.stringify({
        model: input.model,
        messages,
        stream: true,
        stream_options: { include_usage: true },
        thinking: { type: "disabled" },
      }),
    });
    if (!response.ok) {
      await response.body?.cancel();
      throw new DeepSeekHttpError(response.status);
    }
    if (!response.body) throw new Error("deepseek-empty-response");

    let resolveUsage: (value: unknown) => void = () => undefined;
    const usage = new Promise<unknown>((resolve) => { resolveUsage = resolve; });
    const reader = response.body.getReader();
    const decoder = new TextDecoder();
    const textStream = (async function* () {
      let pending = "";
      let finalUsage: unknown;
      try {
        while (true) {
          const chunk = await reader.read();
          pending += decoder.decode(chunk.value, { stream: !chunk.done });
          // A compliant SSE server normally terminates events with a blank line. Flush the
          // final event defensively so a proxy-truncated trailing separator cannot lose text.
          if (chunk.done && pending) pending += "\n\n";
          const blocks = pending.split(/\r?\n\r?\n/);
          pending = blocks.pop() ?? "";
          for (const block of blocks) {
            for (const line of block.split(/\r?\n/)) {
              if (!line.startsWith("data:")) continue;
              const data = line.slice(5).trim();
              if (!data || data === "[DONE]") continue;
              const parsed = JSON.parse(data) as {
                choices?: Array<{ delta?: { content?: string } }>;
                usage?: unknown;
              };
              if (parsed.usage !== undefined) finalUsage = parsed.usage;
              const delta = parsed.choices?.[0]?.delta?.content;
              if (delta) yield delta;
            }
          }
          if (chunk.done) break;
        }
      } finally {
        resolveUsage(finalUsage);
        await reader.cancel().catch(() => undefined);
      }
    })();
    return { textStream, usage };
  }
}
