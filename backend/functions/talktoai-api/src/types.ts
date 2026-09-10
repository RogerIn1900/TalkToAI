export type Freshness = "FRESH" | "DELAYED" | "STALE" | "UNKNOWN";
export type Period = "intraday" | "day" | "week" | "month";

export interface ChatMessage {
  role: "user" | "assistant";
  content: string;
}

export interface ChatRequest {
  installationId: string;
  conversationId: string;
  model: string;
  messages: ChatMessage[];
  attachments?: AttachmentRef[];
  stream: true;
}

export interface AttachmentRef {
  id: string;
  name: string;
  mimeType: "image/jpeg" | "image/png" | "image/webp" | "image/gif" | "text/csv" | "text/plain";
  sizeBytes: number;
  objectRef: string;
}

export interface Bar {
  time: string;
  open: number;
  high: number;
  low: number;
  close: number;
  volume: number;
}

export interface MarketEnvelope<T> {
  symbol: string;
  source: string;
  marketTime: string;
  fetchedAt: string;
  freshness: Freshness;
  freshnessReason: string;
  data: T;
}

export interface QuotaDecision {
  allowed: boolean;
  used: number;
  limit: number;
  resetAt: string;
}

export interface QuotaStore {
  consume(installationId: string, now: Date): Promise<QuotaDecision>;
}

export interface MarketDataProvider {
  quote(symbol: string, now: Date): Promise<MarketEnvelope<Record<string, number | string>>>;
  bars(symbol: string, period: Period, from: string | undefined, to: string | undefined, now: Date): Promise<MarketEnvelope<Bar[]>>;
}
