const BASE_URL = "https://openrouter.ai/api/v1";

function headers(apiKey) {
  return {
    Authorization: `Bearer ${apiKey}`,
    "Content-Type": "application/json",
    "HTTP-Referer": "https://localhost/mascot3",
    "X-Title": "Mascot 3",
  };
}

async function request(fetchImpl, url, { apiKey, method = "GET", payload, timeoutMs = 30000 } = {}) {
  if (!apiKey) throw new Error("OPENROUTER_API_KEY is required for paid generation");
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    const response = await fetchImpl(url, {
      method,
      headers: headers(apiKey),
      body: payload === undefined ? undefined : JSON.stringify(payload),
      signal: controller.signal,
    });
    const text = await response.text();
    if (!response.ok) throw new Error(`OpenRouter ${response.status}: ${text.slice(0, 1000)}`);
    return { data: text ? JSON.parse(text) : {}, requestId: response.headers.get("x-request-id") };
  } finally {
    clearTimeout(timer);
  }
}

function createOpenRouterDirectBridgeFromEnv({ env = process.env, fetchImpl = fetch } = {}) {
  const apiKey = env.OPENROUTER_API_KEY;
  return {
    async listModels({ timeoutMs } = {}) {
      return request(fetchImpl, `${BASE_URL}/models`, { apiKey, timeoutMs });
    },
    async chatCompletions({ payload, timeoutMs } = {}) {
      const result = await request(fetchImpl, `${BASE_URL}/chat/completions`, { apiKey, method: "POST", payload, timeoutMs });
      return { ...result, transportMode: "direct" };
    },
  };
}

function createOpenRouterProxyBridgeFromEnv({ env = process.env, allowDirectFallback = false, fetchImpl = fetch } = {}) {
  const proxyUrl = String(env.PET_V2_OPENROUTER_PROXY_URL || "").replace(/\/$/, "");
  if (!proxyUrl) {
    if (allowDirectFallback) return createOpenRouterDirectBridgeFromEnv({ env, fetchImpl });
    throw new Error("PET_V2_OPENROUTER_PROXY_URL is not configured; use --direct-openrouter=yes with OPENROUTER_API_KEY");
  }
  const apiKey = env.PET_V2_OPENROUTER_PROXY_KEY || env.OPENROUTER_API_KEY;
  return {
    async listModels({ timeoutMs } = {}) {
      return request(fetchImpl, `${proxyUrl}/models`, { apiKey, timeoutMs });
    },
    async chatCompletions({ payload, timeoutMs } = {}) {
      const result = await request(fetchImpl, `${proxyUrl}/chat/completions`, { apiKey, method: "POST", payload, timeoutMs });
      return { ...result, transportMode: "proxy" };
    },
  };
}

module.exports = { createOpenRouterDirectBridgeFromEnv, createOpenRouterProxyBridgeFromEnv };
