#!/usr/bin/env node
// Disposable, loopback-only acceptance proxy. Never loads provider credentials.
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const http = require('node:http');
const crypto = require('node:crypto');
const { spawn } = require('node:child_process');

const root = path.resolve(__dirname, '..');
const sourceId = '7c2caaca1b682e57ad76e0eb53085b46';
const source = path.join(root, 'server-data/physics', sourceId);
const temporary = fs.mkdtempSync(path.join(os.tmpdir(), 'mascot3-acceptance-'));
const jobs = path.join(temporary, 'server-data/physics');
const token = 'physics-local-acceptance'; // Only protects a disposable local fixture.
const requests = [];
let faults = 0;
let backend;
let stopping = false;
const digest = bytes => crypto.createHash('sha256').update(bytes).digest('hex');

function fixture(id, change) {
  const directory = path.join(jobs, id);
  fs.mkdirSync(directory, { recursive: true });
  for (const name of ['source.png', 'anchor.png', 'character.json']) fs.copyFileSync(path.join(source, name), path.join(directory, name));
  fs.cpSync(path.join(source, 'pack'), path.join(directory, 'pack'), { recursive: true });
  const manifestFile = path.join(directory, 'pack/manifest.json');
  const pack = JSON.parse(fs.readFileSync(manifestFile));
  for (const asset of Object.values(pack.assets)) asset.url = `/v1/physics/jobs/${id}/assets/${asset.file}`;
  change?.(pack);
  const bytes = Buffer.from(JSON.stringify(pack, null, 2));
  fs.writeFileSync(manifestFile, bytes);
  const job = JSON.parse(fs.readFileSync(path.join(source, 'job.json')));
  job.id = id; job.pack_sha256 = digest(bytes); job.status = 'ready';
  fs.writeFileSync(path.join(directory, 'job.json'), JSON.stringify(job));
  return { url: `/v1/physics/jobs/${id}/assets/manifest.json`, sha256: job.pack_sha256 };
}

fs.mkdirSync(jobs, { recursive: true });
// Preserve original manifest bytes so the real saved job remains idempotent.
fs.cpSync(source, path.join(jobs, sourceId), { recursive: true });
const original = JSON.parse(fs.readFileSync(path.join(source, 'job.json')));
const refs = {
  original: { url: `/v1/physics/jobs/${sourceId}/assets/manifest.json`, sha256: original.pack_sha256 },
  update: fixture('c'.repeat(32), pack => { pack.acceptance_revision = 2; }),
  rejected: fixture('b'.repeat(32), pack => { pack.qa.approved = false; }),
};

async function startBackend() {
  backend = spawn(process.execPath, ['server/server.js'], {
    cwd: root,
    env: { PATH: process.env.PATH, NODE_ENV: 'test', MASCOT3_PORT: '18788', MASCOT3_DATA_DIR: temporary,
      MASCOT3_SKIP_SEED: 'true', MASCOT3_CLIENT_TOKEN: token, PHYSICS_ENABLED: 'true' },
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  let logs = '';
  backend.stdout.on('data', bytes => { logs += bytes; });
  backend.stderr.on('data', bytes => { logs += bytes; });
  for (let n = 0; n < 50; n++) {
    if (backend.exitCode !== null) throw new Error(`Acceptance backend stopped: ${logs.slice(-1000)}`);
    try { if ((await fetch('http://127.0.0.1:18788/health')).ok) return; } catch {}
    await new Promise(resolve => setTimeout(resolve, 100));
  }
  throw new Error('acceptance_backend_start_timeout');
}
async function stopBackend() {
  const child = backend;
  if (child && child.exitCode === null) { child.kill('SIGTERM'); await new Promise(resolve => child.once('exit', resolve)); }
}
const proxy = http.createServer(async (req, res) => {
  try {
    if (req.headers['x-mascot-client-token'] !== token) { res.writeHead(401); res.end(); return; }
    if (req.url.startsWith('/acceptance/')) {
      if (req.url === '/acceptance/restart' && req.method === 'POST') { await stopBackend(); await startBackend(); }
      if (req.url === '/acceptance/interrupt-next-update' && req.method === 'POST') faults = 1;
      res.setHeader('Content-Type', 'application/json');
      res.end(JSON.stringify({ refs, requests, faults_remaining: faults, provider_keys_present: false })); return;
    }
    requests.push({ method: req.method, path: req.url, time: Date.now() });
    if (faults && req.url === `/v1/physics/jobs/${'c'.repeat(32)}/assets/body_depth.png`) {
      faults--;
      const bytes = fs.readFileSync(path.join(jobs, 'c'.repeat(32), 'pack/body_depth.png'));
      res.writeHead(200, { 'Content-Type': 'image/png', 'Content-Length': bytes.length });
      res.write(bytes.subarray(0, 64));
      setTimeout(() => res.destroy(), 30); return;
    }
    const upstream = http.request({ hostname: '127.0.0.1', port: 18788, path: req.url, method: req.method, headers: req.headers }, answer => {
      res.writeHead(answer.statusCode, answer.headers); answer.pipe(res);
    });
    upstream.on('error', () => { res.writeHead(502); res.end(); });
    req.pipe(upstream);
  } catch (error) { res.writeHead(500); res.end(JSON.stringify({ error: error.message })); }
});

async function finish() {
  if (stopping) return; stopping = true;
  proxy.close(); await stopBackend();
  const output = path.join(root, 'output/physics-pilot/network-acceptance-server.json');
  fs.mkdirSync(path.dirname(output), { recursive: true });
  fs.writeFileSync(output, JSON.stringify({ refs, requests, provider_keys_present: false }, null, 2));
  fs.rmSync(temporary, { recursive: true, force: true });
  process.exit(0);
}
process.once('SIGINT', finish); process.once('SIGTERM', finish);
startBackend().then(() => proxy.listen(8788, '127.0.0.1', () => console.log('Acceptance API listening on 127.0.0.1:8788; provider credentials absent.')))
  .catch(async error => { console.error(error.message); await stopBackend(); fs.rmSync(temporary, { recursive: true, force: true }); process.exit(1); });
