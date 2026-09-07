import { createHash } from "node:crypto";
import { DEFAULT_DAILY_AI_LIMIT, DEFAULT_TIME_ZONE, QUOTA_COLLECTION } from "./constants";
import type { QuotaDecision, QuotaStore } from "./types";

function datePart(now: Date, timeZone: string): string {
  return new Intl.DateTimeFormat("en-CA", {
    timeZone,
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
  }).format(now);
}

function nextShanghaiMidnight(now: Date): string {
  const parts = datePart(now, DEFAULT_TIME_ZONE).split("-").map(Number);
  const year = parts[0] ?? now.getUTCFullYear();
  const month = parts[1] ?? 1;
  const day = parts[2] ?? 1;
  return new Date(Date.UTC(year, month - 1, day + 1) - 8 * 60 * 60 * 1000).toISOString();
}

export function quotaDocumentId(installationId: string, now: Date): string {
  const anonymousHash = createHash("sha256").update(installationId).digest("hex");
  return `${datePart(now, DEFAULT_TIME_ZONE)}_${anonymousHash}`;
}

export class MemoryQuotaStore implements QuotaStore {
  private readonly counts = new Map<string, number>();

  constructor(private readonly limit = DEFAULT_DAILY_AI_LIMIT) {}

  async consume(installationId: string, now: Date): Promise<QuotaDecision> {
    const key = quotaDocumentId(installationId, now);
    const used = this.counts.get(key) ?? 0;
    if (used >= this.limit) {
      return { allowed: false, used, limit: this.limit, resetAt: nextShanghaiMidnight(now) };
    }
    const next = used + 1;
    this.counts.set(key, next);
    return { allowed: true, used: next, limit: this.limit, resetAt: nextShanghaiMidnight(now) };
  }
}

type CloudDatabase = {
  runTransaction<T>(fn: (transaction: CloudTransaction) => Promise<T>): Promise<{ result: T }>;
};
type CloudTransaction = {
  collection(name: string): { doc(id: string): CloudDocument };
};
type CloudDocument = {
  get(): Promise<{ data?: { used?: number; limit?: number } | null }>;
  set(input: { data: Record<string, unknown> }): Promise<unknown>;
};

export class CloudBaseQuotaStore implements QuotaStore {
  constructor(
    private readonly database: CloudDatabase,
    private readonly limit = DEFAULT_DAILY_AI_LIMIT,
  ) {}

  async consume(installationId: string, now: Date): Promise<QuotaDecision> {
    const id = quotaDocumentId(installationId, now);
    const resetAt = nextShanghaiMidnight(now);
    const result = await this.database.runTransaction(async (transaction) => {
      const document = transaction.collection(QUOTA_COLLECTION).doc(id);
      const snapshot = await document.get();
      const used = snapshot.data?.used ?? 0;
      if (used >= this.limit) {
        return { allowed: false, used, limit: this.limit, resetAt };
      }
      const next = used + 1;
      await document.set({
        data: {
          used: next,
          limit: this.limit,
          updatedAt: now.toISOString(),
          resetAt,
        },
      });
      return { allowed: true, used: next, limit: this.limit, resetAt };
    });
    return result.result;
  }
}
