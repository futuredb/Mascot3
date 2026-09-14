const { createOpenRouterDirectBridgeFromEnv } = require("../openrouter/bridge.js");

function createNanobananaBridgeFromEnv({ env = process.env, fetchImpl = fetch } = {}) {
  const bridge = createOpenRouterDirectBridgeFromEnv({ env, fetchImpl });
  const renderRaw = ({ payload, timeoutMs } = {}) => bridge.chatCompletions({
    payload: {
      ...payload,
      model: payload.model || env.IMAGE_RENDER_MODEL || "google/gemini-3.1-flash-image-preview",
      modalities: payload.modalities || ["image", "text"],
    },
    timeoutMs,
  });
  return {
    listModels: bridge.listModels,
    renderRaw,
    render: renderRaw,
  };
}

module.exports = { createNanobananaBridgeFromEnv };
