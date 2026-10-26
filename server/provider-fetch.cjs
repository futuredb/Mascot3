const { ProxyAgent, fetch: proxyFetch } = require('undici');
let dispatcher;
module.exports = function providerFetch(input, init) {
  const proxy = process.env.OPENROUTER_PROXY_URL;
  const url = typeof input === 'string' || input instanceof URL ? input : input.url;
  if (!proxy || new URL(url).hostname !== 'openrouter.ai') return fetch(input, init);
  dispatcher ??= new ProxyAgent(proxy);
  return proxyFetch(url, { ...init, dispatcher });
};
