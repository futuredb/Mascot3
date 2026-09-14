const BASE_URL = "https://openrouter.ai/api/v1";

function createVeoBridgeFromEnv({ env = process.env, fetchImpl = fetch } = {}) {
  const apiKey = env.OPENROUTER_API_KEY;
  if (!apiKey) throw new Error("OPENROUTER_API_KEY is required for video generation");
  const auth = { Authorization: `Bearer ${apiKey}` };
  async function jsonRequest(url, options = {}, timeoutMs = 30000) {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), timeoutMs);
    try {
      const response = await fetchImpl(url, { ...options, headers: { ...auth, ...(options.headers || {}) }, signal: controller.signal });
      const text = await response.text();
      if (!response.ok) throw new Error(`OpenRouter video ${response.status}: ${text.slice(0, 1000)}`);
      return { data: text ? JSON.parse(text) : {} };
    } finally { clearTimeout(timer); }
  }
  function jobUrl(idOrUrl) {
    if (/^https?:\/\//.test(idOrUrl)) return idOrUrl;
    if (idOrUrl.startsWith("/")) return new URL(idOrUrl, "https://openrouter.ai").toString();
    return `${BASE_URL}/videos/${idOrUrl}`;
  }
  return {
    submitVideo: ({ payload, timeoutMs } = {}) => jsonRequest(`${BASE_URL}/videos`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(payload),
    }, timeoutMs),
    getVideoJob: (idOrUrl, { timeoutMs } = {}) => jsonRequest(jobUrl(idOrUrl), {}, timeoutMs),
    async downloadVideo(id, { timeoutMs } = {}) {
      const response = await fetchImpl(`${BASE_URL}/videos/${id}/content?index=0`, { headers: auth, signal: AbortSignal.timeout(timeoutMs || 240000) });
      if (!response.ok) throw new Error(`OpenRouter video download ${response.status}: ${(await response.text()).slice(0, 1000)}`);
      const type = response.headers.get("content-type") || "video/mp4";
      return { buffer: Buffer.from(await response.arrayBuffer()), extension: type.includes("webm") ? ".webm" : ".mp4" };
    },
  };
}

module.exports = { createVeoBridgeFromEnv };
