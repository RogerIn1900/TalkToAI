"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.CloudBaseSqlQuotaStore = exports.CloudBaseQuotaStore = exports.MemoryQuotaStore = void 0;
exports.quotaDocumentId = quotaDocumentId;
const node_crypto_1 = require("node:crypto");
const constants_1 = require("./constants");
function datePart(now, timeZone) {
    return new Intl.DateTimeFormat("en-CA", {
        timeZone,
        year: "numeric",
        month: "2-digit",
        day: "2-digit",
    }).format(now);
}
function nextShanghaiMidnight(now) {
    const parts = datePart(now, constants_1.DEFAULT_TIME_ZONE).split("-").map(Number);
    const year = parts[0] ?? now.getUTCFullYear();
    const month = parts[1] ?? 1;
    const day = parts[2] ?? 1;
    return new Date(Date.UTC(year, month - 1, day + 1) - 8 * 60 * 60 * 1000).toISOString();
}
function quotaDocumentId(installationId, now) {
    const anonymousHash = (0, node_crypto_1.createHash)("sha256").update(installationId).digest("hex");
    return `${datePart(now, constants_1.DEFAULT_TIME_ZONE)}_${anonymousHash}`;
}
class MemoryQuotaStore {
    limit;
    counts = new Map();
    constructor(limit = constants_1.DEFAULT_DAILY_AI_LIMIT) {
        this.limit = limit;
    }
    async consume(installationId, now) {
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
exports.MemoryQuotaStore = MemoryQuotaStore;
class CloudBaseQuotaStore {
    database;
    limit;
    constructor(database, limit = constants_1.DEFAULT_DAILY_AI_LIMIT) {
        this.database = database;
        this.limit = limit;
    }
    async consume(installationId, now) {
        const id = quotaDocumentId(installationId, now);
        const resetAt = nextShanghaiMidnight(now);
        const result = await this.database.runTransaction(async (transaction) => {
            const document = transaction.collection(constants_1.QUOTA_COLLECTION).doc(id);
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
exports.CloudBaseQuotaStore = CloudBaseQuotaStore;
const CONSUME_QUOTA_SQL = `select * from public.consume_talktoai_quota(
  {{id}}, {{limit}}, {{updatedAt}}::timestamptz, {{resetAt}}::timestamptz
)`;
class CloudBaseSqlQuotaStore {
    models;
    limit;
    constructor(models, limit = constants_1.DEFAULT_DAILY_AI_LIMIT) {
        this.models = models;
        this.limit = limit;
    }
    async consume(installationId, now) {
        if (!this.models.$runSQL)
            throw new Error("CloudBase SQL runner is unavailable");
        const resetAt = nextShanghaiMidnight(now);
        const response = await this.models.$runSQL(CONSUME_QUOTA_SQL, {
            id: quotaDocumentId(installationId, now),
            limit: this.limit,
            updatedAt: now.toISOString(),
            resetAt,
        });
        const row = response.data?.executeResultList?.[0];
        const used = Number(row?.used);
        const rawAllowed = row?.allowed;
        const allowed = rawAllowed === true || rawAllowed === "true"
            ? true
            : rawAllowed === false || rawAllowed === "false"
                ? false
                : undefined;
        if (!Number.isInteger(used) || used < 0 || typeof allowed !== "boolean") {
            throw new Error("CloudBase SQL quota returned an invalid response");
        }
        return { allowed, used, limit: this.limit, resetAt };
    }
}
exports.CloudBaseSqlQuotaStore = CloudBaseSqlQuotaStore;
