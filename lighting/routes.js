const crypto = require('node:crypto');
const path = require('node:path');

async function handleLighting({ req, res, url, lightingJobs, token, send, serveFile }) {
    if (!url.pathname.startsWith('/v1/lighting/')) return false;
    {
      if (!token) return send(res, 503, { error: 'lighting_client_auth_not_configured' });
      const given = Buffer.from(String(req.headers['x-mascot-client-token'] || ''));
      const expected = Buffer.from(token);
      if (given.length !== expected.length || !crypto.timingSafeEqual(given, expected)) return send(res, 401, { error: 'unauthorized' });
      const upload = url.pathname.match(/^\/v1\/lighting\/sources\/(image|video)\/([a-f0-9]{64})$/);
      if (upload && req.method === 'PUT') {
        const id = lightingJobs.identity(upload[1], upload[2]);
        const known = await lightingJobs.get(id);
        if (known) { req.resume(); return send(res, 200, lightingJobs.public(known)); }
        const limit = (upload[1] === 'image' ? 20 : 80) * 1024 * 1024;
        let count = 0; const chunks = [];
        for await (const chunk of req) {
          count += chunk.length;
          if (count > limit) throw Object.assign(new Error('lighting_source_too_large'), { status: 413 });
          chunks.push(chunk);
        }
        return send(res, 202, lightingJobs.public(await lightingJobs.put(upload[1], upload[2], Buffer.concat(chunks))));
      }
      const source = url.pathname.match(/^\/v1\/lighting\/sources\/(image|video)\/([a-f0-9]{64})$/);
      if (source && req.method === 'GET') {
        const job = await lightingJobs.get(lightingJobs.identity(source[1], source[2]));
        return job ? send(res, 200, lightingJobs.public(job)) : send(res, 404, { error: 'lighting_not_found' });
      }
      const match = url.pathname.match(/^\/v1\/lighting\/jobs\/([a-f0-9]{64})(?:\/assets\/([a-z][a-z0-9_-]*\.(?:json|png)))?$/);
      if (match && req.method === 'GET') {
        const job = await lightingJobs.get(match[1]);
        if (!job) return send(res, 404, { error: 'lighting_not_found' });
        if (!match[2]) return send(res, 200, lightingJobs.public(job));
        if (job.status !== 'ready' || !job.files?.some(file => file.file === match[2])) return send(res, 404, { error: 'lighting_asset_not_ready' });
        return serveFile(res, path.join(lightingJobs.directory(job.id), 'pack'), match[2]);
      }
      return send(res, 404, { error: 'lighting_route_not_found' });
    }
}
module.exports = { handleLighting };
