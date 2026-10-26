/** FutureDB telemetry v1.0.0. Standalone ESM; no provider credentials or content leave this adapter. */
import { randomUUID, createHash } from "node:crypto";
import * as fs from "node:fs/promises";
import path from "node:path";
const allowed = new Set(
  "visitorId sessionId identityKind action outcome route device durationMs statusCode errorCode operationId attemptId provider model credentialId providerRequestId billingScope inputTokens outputTokens cachedTokens reasoningTokens images videoSeconds cost currency costSource dropped usageTotal usageDaily usageMonthly remaining credentialFingerprint balanceObservedAt".split(
    " ",
  ),
);
const endpoint = process.env.DASHBOARD_INGEST_URL || "";
const resourceId = process.env.DASHBOARD_RESOURCE_ID || "";
const token = process.env.DASHBOARD_INGEST_TOKEN || "";
const directory = process.env.DASHBOARD_QUEUE_DIR || "";
const enabled = Boolean(endpoint && resourceId && token && directory);
const sendFetch = globalThis.fetch.bind(globalThis);
let writing = Promise.resolve(),
  flushing = false,
  initPromise = null,
  dropped = 0,
  nextFlush = 0,
  failures = 0;
function clean(fields) {
  const out = {};
  for (const [key, value] of Object.entries(fields || {}))
    if (
      allowed.has(key) &&
      value !== undefined &&
      (value !== null || ["cost", "remaining"].includes(key)) &&
      (value === null || ["string", "number", "boolean"].includes(typeof value))
    ) {
      if (typeof value === "number" && !Number.isFinite(value)) continue;
      out[key] = value;
    }
  return out;
}
export function anonymousId(value) {
  return createHash("sha256")
    .update(`${resourceId}:${String(value)}`)
    .digest("hex")
    .slice(0, 32);
}
async function initialize() {
  if (!enabled) return;
  if (!initPromise)
    initPromise = (async () => {
      await fs.mkdir(directory, { recursive: true, mode: 0o700 });
      try {
        dropped =
          JSON.parse(
            await fs.readFile(path.join(directory, "health.json"), "utf8"),
          ).dropped || 0;
      } catch {}
      const flushTimer = setInterval(() => void flush(), 10000);
      flushTimer.unref();
      const healthTimer = setInterval(
        () => void emit("source_health", { dropped }),
        300000,
      );
      healthTimer.unref();
    })().catch(() => {
      initPromise = null;
    });
  return initPromise;
}
export async function emit(kind, fields = {}, options = {}) {
  if (!enabled) return false;
  const event = {
    version: 1,
    eventId: options.eventId || randomUUID(),
    resourceId,
    occurredAt: options.occurredAt || new Date().toISOString(),
    kind,
    sdkVersion: "1.0.0",
    billingScope: "team",
    ...clean(fields),
  };
  writing = writing
    .then(async () => {
      await initialize();
      const files = (await fs.readdir(directory)).filter((f) =>
        /^\d+_[a-f0-9-]+\.json$/.test(f),
      );
      if (files.length >= 10000) {
        dropped++;
        await fs.writeFile(
          path.join(directory, "health.json"),
          JSON.stringify({ dropped }),
          { mode: 0o600 },
        );
        return;
      }
      const file = path.join(directory, `${Date.now()}_${event.eventId}.json`);
      const serialized = JSON.stringify(event);
      if (serialized.length > 8000) {
        dropped++;
        return;
      }
      await fs.writeFile(`${file}.pending`, serialized, { mode: 0o600 });
      await fs.rename(`${file}.pending`, file);
    })
    .catch(() => {
      dropped++;
    });
  await writing;
  return true;
}
export async function flush() {
  if (!enabled || flushing || Date.now() < nextFlush) return;
  flushing = true;
  try {
    await initialize();
    await writing;
    const names = (await fs.readdir(directory))
      .filter((f) => /^\d+_[a-f0-9-]+\.json$/.test(f))
      .sort()
      .slice(0, 50);
    if (!names.length) return;
    const events = await Promise.all(
      names.map(async (file) =>
        JSON.parse(await fs.readFile(path.join(directory, file), "utf8")),
      ),
    );
    const response = await sendFetch(endpoint, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        Authorization: `Bearer ${token}`,
      },
      body: JSON.stringify({ events }),
      signal: AbortSignal.timeout(5000),
      redirect: "error",
    });
    if (!response.ok) throw Error("COLLECTOR_UNAVAILABLE");
    const result = await response.json();
    if (result.accepted + result.duplicates !== names.length)
      throw Error("INCOMPLETE_ACK");
    for (const file of names) await fs.unlink(path.join(directory, file));
    failures = 0;
    nextFlush = 0;
  } catch {
    failures++;
    nextFlush =
      Date.now() + Math.min(300000, 1000 * 2 ** Math.min(failures, 8));
  } finally {
    flushing = false;
  }
}
export function usageFields(raw) {
  const usage = raw?.usage || raw?.response?.usage || {};
  const numeric = (...values) =>
    values.find((v) => typeof v === "number" && Number.isFinite(v) && v >= 0);
  const cost = numeric(usage.cost, raw?.total_cost, raw?.data?.total_cost);
  return clean({
    inputTokens: numeric(
      usage.prompt_tokens,
      usage.input_tokens,
      raw?.native_tokens_prompt,
    ),
    outputTokens: numeric(
      usage.completion_tokens,
      usage.output_tokens,
      raw?.native_tokens_completion,
    ),
    cachedTokens: numeric(
      usage.prompt_tokens_details?.cached_tokens,
      usage.input_tokens_details?.cached_tokens,
    ),
    reasoningTokens: numeric(
      usage.completion_tokens_details?.reasoning_tokens,
      usage.output_tokens_details?.reasoning_tokens,
    ),
    cost:
      cost === undefined
        ? null
        : cost.toLocaleString("en-US", {
            useGrouping: false,
            maximumFractionDigits: 15,
          }),
    currency: cost === undefined ? undefined : "USD",
    costSource: cost === undefined ? "unknown" : "provider",
    providerRequestId: typeof raw?.id === "string" ? raw.id : undefined,
  });
}
export async function beginAI(context) {
  const attempt = {
    ...clean(context),
    attemptId: context.attemptId || randomUUID(),
    startedAt: Date.now(),
  };
  await emit("ai_attempt", {
    ...attempt,
    outcome: "started",
    cost: null,
    costSource: "unknown",
  });
  return attempt;
}
export async function finishAI(attempt, raw = {}, extra = {}) {
  await emit("ai_attempt", {
    ...attempt,
    ...usageFields(raw),
    durationMs: Math.min(86400000, Math.max(0, Date.now() - attempt.startedAt)),
    outcome: "success",
    ...extra,
  });
}
async function readMetadata(response) {
  if (!response.body) return {};
  const reader = response.body.getReader();
  let tail = "",
    head = "",
    size = 0;
  const decoder = new TextDecoder();
  let usage = null,
    id = null;
  const type = response.headers.get("content-type") || "";
  try {
    for (;;) {
      const chunk = await reader.read();
      if (chunk.done) break;
      size += chunk.value.length;
      const text = decoder.decode(chunk.value, { stream: true });
      if (head.length < 4096) head = (head + text).slice(0, 4096);
      tail = (tail + text).slice(-262144);
      if (type.includes("text/event-stream")) {
        for (const match of tail.matchAll(/^data:\s*(\{[^\n]+\})/gm)) {
          try {
            const data = JSON.parse(match[1]);
            if (data.usage || data.response?.usage) usage = data;
            id ??= data.id;
          } catch {}
        }
      }
      if (size > 64 * 1024 * 1024) {
        void reader.cancel();
        break;
      }
    }
  } finally {
    reader.releaseLock();
  }
  if (usage) return usage;
  if (size <= 262144) {
    try {
      return JSON.parse(tail);
    } catch {
      return {};
    }
  }
  // Large image responses: extract only the bounded usage object and top-level ID.
  const start = tail.lastIndexOf('"usage"');
  if (start >= 0) {
    const open = tail.indexOf("{", start);
    if (open >= 0) {
      let depth = 0;
      for (let i = open; i < tail.length; i++) {
        if (tail[i] === "{") depth++;
        else if (tail[i] === "}" && --depth === 0) {
          try {
            usage = JSON.parse(tail.slice(open, i + 1));
          } catch {}
          break;
        }
      }
    }
  }
  const match = head.match(/^\s*\{\s*"id"\s*:\s*"([A-Za-z0-9_.:-]+)"/);
  return { usage: usage || {}, id: match?.[1] };
}
export function instrumentFetch(fetcher, context = {}) {
  return async function (input, init) {
    if (!enabled) return fetcher(input, init);
    let requestBody = {};
    try {
      if (typeof init?.body === "string") requestBody = JSON.parse(init.body);
    } catch {}
    const model = context.model || requestBody.model || "unknown";
    const url = new URL(
      typeof input === "string"
        ? input
        : input instanceof URL
          ? input.href
          : input.url,
    );
    const method = (
      init?.method ||
      (typeof input === "object" ? input.method : undefined) ||
      "GET"
    ).toUpperCase();
    const billable =
      method === "POST" &&
      /\/(chat\/completions|responses|images(?:\/generations)?|videos(?:\/generations)?|embeddings)$/.test(
        url.pathname,
      );
    const jobId =
      method === "GET" && /\/videos\/[^/]+$/.test(url.pathname)
        ? url.pathname.split("/").at(-1)
        : null;
    const jobPath = (id) =>
      path.join(
        directory,
        "jobs",
        createHash("sha256").update(String(id)).digest("hex") + ".json",
      );
    let pending = null;
    if (jobId)
      try {
        pending = JSON.parse(await fs.readFile(jobPath(jobId), "utf8"));
      } catch {}
    if (!billable && !pending) return fetcher(input, init);
    const attempt =
      pending ||
      (await beginAI({
        provider: context.provider || "openrouter",
        model,
        credentialId:
          context.credentialId ||
          process.env.DASHBOARD_KEY_ALIAS ||
          "team-main",
        ...context,
      }));
    try {
      const response = await fetcher(input, init);
      const copy = response.clone();
      void readMetadata(copy)
        .then(async (raw) => {
          const status = raw?.status;
          if (
            billable &&
            /\/videos(?:\/generations)?$/.test(url.pathname) &&
            response.ok &&
            typeof raw.id === "string" &&
            !["completed", "failed"].includes(status)
          ) {
            await fs.mkdir(path.join(directory, "jobs"), {
              recursive: true,
              mode: 0o700,
            });
            await fs.writeFile(jobPath(raw.id), JSON.stringify(attempt), {
              mode: 0o600,
            });
            return;
          }
          if (pending && !["completed", "failed"].includes(status)) return;
          await finishAI(attempt, raw, {
            outcome: response.ok && status !== "failed" ? "success" : "error",
            statusCode: response.status,
            errorCode: response.ok ? undefined : `HTTP_${response.status}`,
          });
          if (pending) await fs.unlink(jobPath(jobId)).catch(() => {});
        })
        .catch(() => {
          if (billable)
            void finishAI(
              attempt,
              {},
              { outcome: response.ok ? "unknown" : "error" },
            );
        });
      return response;
    } catch (error) {
      if (billable)
        await finishAI(
          attempt,
          {},
          {
            outcome: error?.name === "AbortError" ? "cancelled" : "error",
            errorCode:
              error?.name === "AbortError" ? "ABORTED" : "PROVIDER_UNAVAILABLE",
          },
        );
      throw error;
    }
  };
}
export function httpMiddleware(actionForRequest) {
  return (request, response, next) => {
    const start = Date.now();
    response.once("finish", () => {
      if (
        /\/(?:healthz?|api\/health)(?:\?|$)|\.(?:js|css|png|webp|woff2?)(?:\?|$)/.test(
          request.url || "",
        )
      )
        return;
      const route = String(request.telemetryRoute || request.route?.path || "/")
        .replace(/[a-f0-9-]{24,}/gi, ":id")
        .replace(/[^a-zA-Z0-9_./:\[\]-]/g, "")
        .slice(0, 200);
      if (/health|dashboard|telemetry|\.(js|css|png|woff)/.test(route)) return;
      void emit("api_request", {
        route,
        statusCode: response.statusCode,
        durationMs: Date.now() - start,
        outcome: response.statusCode >= 400 ? "error" : "success",
      });
      const action = actionForRequest?.(request);
      if (action)
        void emit("action", {
          action,
          outcome: response.statusCode >= 400 ? "error" : "success",
          route,
          ...(request.principalId
            ? {
                visitorId: anonymousId(request.principalId),
                identityKind: "api_client",
                device: "api",
              }
            : {}),
        });
    });
    next?.();
  };
}
export async function keySnapshot(fetcher = sendFetch) {
  const key = process.env.OPENROUTER_API_KEY?.trim();
  if (!enabled || !key) return;
  const identity = {
    provider: "openrouter",
    credentialId: process.env.DASHBOARD_KEY_ALIAS || "team-main",
    credentialFingerprint: createHash("sha256").update(key).digest("hex"),
    currency: "USD",
  };
  try {
    const response = await fetcher("https://openrouter.ai/api/v1/key", {
      headers: { Authorization: `Bearer ${key}` },
      signal: AbortSignal.timeout(8000),
      redirect: "error",
    });
    if (!response.ok) {
      await response.body?.cancel();
      await emit("key_snapshot", { ...identity, outcome: "error", statusCode: response.status, errorCode: `HTTP_${response.status}` });
      return;
    }
    const { data } = await response.json();
    if (!data || typeof data.usage !== "number" || !Number.isFinite(data.usage)) throw Error("INVALID_KEY_RESPONSE");
    const decimal = (v) => typeof v === "number" && Number.isFinite(v) && v >= 0
      ? v.toLocaleString("en-US", { useGrouping: false, maximumFractionDigits: 15 }) : undefined;
    await emit("key_snapshot", {
      ...identity, outcome: "success", balanceObservedAt: new Date().toISOString(),
      usageTotal: decimal(data.usage), usageDaily: decimal(data.usage_daily), usageMonthly: decimal(data.usage_monthly),
      remaining: data.limit_remaining == null ? null : String(data.limit_remaining),
    });
  } catch {
    await emit("key_snapshot", { ...identity, outcome: "error", errorCode: "KEY_SYNC_UNAVAILABLE" });
  }
}

if (enabled)
  void initialize()
    .then(() => emit("source_health", { dropped }))
    .then(() => flush())
    .catch(() => {});

/** A framework-neutral Next/Fetch route wrapper. It records only the fixed route and outcome. */
export function withTelemetry(handler, route, action) {
  return async function (...args) {
    const start = Date.now();
    let statusCode = 500;
    try {
      const response = await handler.apply(this, args);
      statusCode = response.status;
      return response;
    } finally {
      void emit("api_request", {
        route,
        statusCode,
        durationMs: Date.now() - start,
        outcome: statusCode >= 400 ? "error" : "success",
      });
      if (action)
        void emit("action", {
          action,
          route,
          outcome: statusCode >= 400 ? "error" : "success",
        });
    }
  };
}
let keySnapshotTimer;
export function scheduleKeySnapshots(fetcher = sendFetch) {
  if (!enabled || keySnapshotTimer) return;
  void keySnapshot(fetcher);
  keySnapshotTimer = setInterval(() => void keySnapshot(fetcher), 900000);
  keySnapshotTimer.unref();
}
