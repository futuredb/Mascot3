const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const path = require('node:path');
const os = require('node:os');
const http = require('node:http');
const { LightingJobs, sha } = require('./jobs');
const { handleLighting } = require('./routes');

const png = Buffer.concat([Buffer.from('89504e470d0a1a0a', 'hex'), Buffer.alloc(24, 1)]);
async function fixture(t, runner) {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), 'lighting-test-'));
  t.after(() => fs.rm(root, { recursive: true, force: true }));
  const jobs = new LightingJobs({ root, runner });
  return jobs;
}
async function untilIdle(jobs) {
  for (let i = 0; i < 400 && (jobs.busy || jobs.pending.size); i++) await new Promise(resolve => setTimeout(resolve, 5));
  assert.equal(jobs.busy, false);
}
async function validOutput(job, output) {
  await fs.writeFile(path.join(output, 'body_depth.png'), png);
  await fs.writeFile(path.join(output, 'manifest.json'), JSON.stringify({ format: 'mascot-depth-2.5d',
    source_sha256: job.source_sha256, qa: { approved: true }, assets: { depth: { file: 'body_depth.png' } } }));
}
test('concurrent duplicate submissions and retries reuse one completed source job', async t => {
  let calls = 0;
  const jobs = await fixture(t, async (...args) => { calls++; await validOutput(...args); });
  const [a, b] = await Promise.all([jobs.put('image', sha(png), png), jobs.put('image', sha(png), png)]);
  assert.equal(a.id, b.id);
  await untilIdle(jobs);
  assert.equal((await jobs.put('image', sha(png), png)).status, 'ready');
  assert.equal(calls, 1);
  const ready = jobs.public(await jobs.get(a.id));
  assert.equal(ready.files.length, 2);
  assert.ok(ready.files.every(f => f.sha256.length === 64 && f.url.startsWith(`/v1/lighting/jobs/${a.id}/assets/`)));
});
test('different source bytes create different packages; malformed inputs never reach inference', async t => {
  let calls = 0;
  const jobs = await fixture(t, async (...args) => { calls++; await validOutput(...args); });
  await assert.rejects(jobs.put('image', 'a'.repeat(64), png), /hash_mismatch/);
  await assert.rejects(jobs.put('../image', sha(png), png), /invalid/);
  const bad = Buffer.from('not an image');
  await assert.rejects(jobs.put('image', sha(bad), bad), /requires_png/);
  assert.equal(calls, 0);
  const second = Buffer.concat([png, Buffer.from('new')]);
  assert.notEqual((await jobs.put('image', sha(png), png)).id, (await jobs.put('image', sha(second), second)).id);
  await untilIdle(jobs);
  assert.equal(calls, 2);
});
test('restart resumes an interrupted task without accepting incomplete assets', async t => {
  const jobs = await fixture(t, validOutput);
  const id = jobs.identity('image', sha(png)); const directory = jobs.directory(id);
  await fs.mkdir(path.join(directory, 'pack.pending'), { recursive: true });
  await fs.writeFile(path.join(directory, 'job.json'), JSON.stringify({ id, kind: 'image', source_sha256: sha(png),
    source_bytes: png.length, source_file: 'source.png', status: 'running', attempts: 1 }));
  assert.equal(jobs.public(await jobs.get(id)).files.length, 0);
  await jobs.resumeInterrupted(); await untilIdle(jobs);
  assert.equal((await jobs.get(id)).status, 'ready');
  assert.equal((await jobs.get(id)).attempts, 2);
});
test('failed or wrong-source inference stays failed and never resubmits generation', async t => {
  let calls = 0;
  const jobs = await fixture(t, async (job, output) => { calls++; await validOutput({ ...job, source_sha256: 'a'.repeat(64) }, output); });
  const job = await jobs.put('image', sha(png), png); await untilIdle(jobs);
  const failed = await jobs.get(job.id);
  assert.equal(failed.status, 'failed'); assert.equal(jobs.public(failed).files.length, 0);
  await jobs.put('image', sha(png), png); await untilIdle(jobs); assert.equal(calls, 1);
});
test('actual HTTP routes authenticate uploads and downloads and recover by source hash', async t => {
  const jobs = await fixture(t, validOutput);
  const send = (res, status, value) => { res.writeHead(status, { 'Content-Type': 'application/json' }); res.end(JSON.stringify(value)); };
  const server = http.createServer(async (req, res) => {
    try { await handleLighting({ req, res, url: new URL(req.url, 'http://localhost'), lightingJobs: jobs, token: 'preview-test', send,
      serveFile: async (res, root, name) => { res.end(await fs.readFile(path.join(root, name))); } }); }
    catch (e) { send(res, e.status || 500, { error: e.message }); }
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  t.after(() => new Promise(resolve => server.close(resolve)));
  const origin = `http://127.0.0.1:${server.address().port}`;
  const url = origin + `/v1/lighting/sources/image/${sha(png)}`;
  assert.equal((await fetch(url)).status, 401);
  const headers = { 'X-Mascot-Client-Token': 'preview-test' };
  assert.equal((await fetch(url, { headers })).status, 404);
  assert.equal((await fetch(url, { headers, method: 'PUT', body: png })).status, 202);
  await untilIdle(jobs);
  const ready = await (await fetch(url, { headers })).json(); assert.equal(ready.status, 'ready');
  const manifest = ready.files.find(f => f.file === 'manifest.json');
  assert.equal((await fetch(origin + manifest.url)).status, 401);
  assert.equal(sha(Buffer.from(await (await fetch(origin + manifest.url, { headers })).arrayBuffer())), ready.manifest_sha256);
  assert.equal((await fetch(origin + manifest.url.replace('manifest.json', 'secret.json'), { headers })).status, 404);
});
