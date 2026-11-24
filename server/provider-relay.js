const { fetch, ProxyAgent } = require("undici");
const { Readable, Transform } = require("node:stream");
const { pipeline } = require("node:stream/promises");

const PREFIX = "/v1/provider/";
const MAX_BODY = 48 * 1024 * 1024;
const REDIRECTS = new Set([301, 302, 303, 307, 308]);

function mediaHost(host) {
  return host === "fal.media" || host.endsWith(".fal.media") ||
    host === "fal.ai" || host.endsWith(".fal.ai");
}

function allows(url, method) {
  if (url.protocol !== "https:" || url.port || url.username || url.password || url.hash) return false;
  const { hostname, pathname } = url;
  if (hostname === "openrouter.ai") {
    if (method === "POST") return /^\/api\/v1\/(chat\/completions|images|videos)$/.test(pathname);
    return method === "GET" && /^\/api\/v1\/(key|images\/models|videos\/models|videos\/[A-Za-z0-9_-]+(?:\/content)?)$/.test(pathname);
  }
  if (hostname === "queue.fal.run") {
    if (method === "POST") return pathname === "/minimax/h3-max-turbo/image-to-video";
    return method === "GET" && /^\/minimax\/h3-max-turbo\/requests\/[A-Za-z0-9-]{1,80}(?:\/status)?$/.test(pathname);
  }
  if (hostname === "api.reactor.inc") return method === "POST" && pathname === "/tokens";
  return method === "GET" && mediaHost(hostname);
}

function targetFor(url, method) {
  const suffix = url.pathname.slice(PREFIX.length);
  const slash = suffix.indexOf("/");
  if (slash < 0) return null;
  const host = suffix.slice(0, slash);
  if (!/^[a-z0-9.-]+$/.test(host)) return null;
  const target = new URL(`https://${host}${suffix.slice(slash)}${url.search}`);
  return allows(target, method) ? target : null;
}

function requestHeaders(req, target, origin) {
  const headers = {};
  for (const name of ["accept", "content-type", "range"]) {
    if (typeof req.headers[name] === "string") headers[name] = req.headers[name];
  }
  if (target.origin === origin && !mediaHost(target.hostname)) {
    const name = target.hostname === "api.reactor.inc" ? "reactor-api-key" : "authorization";
    if (typeof req.headers[name] === "string") headers[name] = req.headers[name];
  }
  return headers;
}

function createProviderRelay(proxyUrl) {
  let agent;
  return async (req, res, url, send) => {
    let target = targetFor(url, req.method);
    if (!target) return send(res, 404, { error: "provider_route_not_found" });
    if (!proxyUrl) return send(res, 503, { error: "provider_proxy_not_configured" });
    const length = Number(req.headers["content-length"] || 0);
    if (length > MAX_BODY) return send(res, 413, { error: "provider_request_too_large" });

    let received = 0;
    const body = req.method === "POST" ? req.pipe(new Transform({
      transform(chunk, encoding, callback) {
        received += chunk.length;
        if (received > MAX_BODY) callback(Object.assign(new Error("provider_request_too_large"), { status: 413 }));
        else callback(null, chunk);
      },
    })) : undefined;
    const signal = AbortSignal.timeout(8 * 60_000);
    const origin = target.origin;
    try {
      agent ??= new ProxyAgent(proxyUrl);
      for (let redirects = 0; ; redirects += 1) {
        const response = await fetch(target, {
          dispatcher: agent,
          method: req.method,
          headers: requestHeaders(req, target, origin),
          body,
          duplex: "half",
          redirect: "manual",
          signal,
        });
        if (REDIRECTS.has(response.status)) {
          await response.body?.cancel();
          const location = response.headers.get("location");
          if (req.method !== "GET" || redirects >= 3 || !location) {
            return send(res, 502, { error: "provider_redirect_denied" });
          }
          target = new URL(location, target);
          if (!allows(target, "GET")) return send(res, 502, { error: "provider_redirect_denied" });
          continue;
        }
        for (const name of ["content-type", "content-range", "accept-ranges", "retry-after"]) {
          const value = response.headers.get(name);
          if (value !== null) res.setHeader(name, value);
        }
        res.setHeader("Cache-Control", "no-store");
        res.setHeader("Content-Security-Policy", "sandbox; default-src 'none'");
        res.setHeader("X-Content-Type-Options", "nosniff");
        res.statusCode = response.status;
        if (response.body) await pipeline(Readable.fromWeb(response.body), res);
        else res.end();
        return;
      }
    } catch (error) {
      if (res.headersSent || res.destroyed) return res.destroy();
      const tooLarge = error.cause?.status === 413;
      return send(res, tooLarge ? 413 : 502,
        { error: tooLarge ? "provider_request_too_large" : "provider_connection_failed" });
    }
  };
}

module.exports = { createProviderRelay, PREFIX, targetFor };
