const fs = require('node:fs/promises');
const path = require('node:path');
const crypto = require('node:crypto');
const { spawn } = require('node:child_process');

const RECIPE = 'relight-v1-depth518-flow3';
const sha = bytes => crypto.createHash('sha256').update(bytes).digest('hex');
const fail = (message, status = 400) => Object.assign(new Error(message), { status });
const safeFile = name => /^(manifest\.json|provenance\.json|body_(color|depth)\.png|normals-\d{3}\.png)$/.test(name);
async function atomic(file, value) {
  const tmp = file + '.pending';
  await fs.writeFile(tmp, JSON.stringify(value));
  await fs.rename(tmp, file);
}

/** One bounded inference queue, independent of all paid generator jobs. */
class LightingJobs {
  constructor({ root, env = process.env, runner = null }) {
    this.root = root; this.env = env; this.runner = runner;
    this.pending = new Set(); this.busy = false; this.lock = Promise.resolve();
  }
  identity(kind, source) {
    if (!['image', 'video'].includes(kind) || !/^[a-f0-9]{64}$/.test(source)) throw fail('invalid_lighting_source');
    return sha(`${RECIPE}:${kind}:${source}`);
  }
  directory(id) {
    if (!/^[a-f0-9]{64}$/.test(id)) throw fail('invalid_lighting_job');
    return path.join(this.root, id);
  }
  async get(id) {
    try { return JSON.parse(await fs.readFile(path.join(this.directory(id), 'job.json'), 'utf8')); }
    catch (e) { if (e.code === 'ENOENT') return null; throw e; }
  }
  async put(kind, source, bytes) {
    const id = this.identity(kind, source);
    if (!Buffer.isBuffer(bytes) || bytes.length < 8 || bytes.length > (kind === 'image' ? 20 : 80) * 1024 * 1024) throw fail('lighting_source_size', 413);
    if (sha(bytes) !== source) throw fail('lighting_source_hash_mismatch');
    if (kind === 'image' && bytes.subarray(0, 8).toString('hex') !== '89504e470d0a1a0a') throw fail('lighting_requires_png');
    if (kind === 'video' && bytes.subarray(4, 8).toString() !== 'ftyp') throw fail('lighting_requires_mp4');
    // Serialize duplicate uploads, including the create-before-response window.
    const operation = this.lock.then(async () => {
      let job = await this.get(id);
      if (job) return job;
      await fs.mkdir(this.root, { recursive: true });
      const disk = await fs.statfs(this.root);
      if (disk.bavail * disk.bsize < 1024 * 1024 * 1024 + bytes.length) throw fail('lighting_disk_space_low', 503);
      if ((await fs.readdir(this.root)).filter(n => /^[a-f0-9]{64}$/.test(n)).length >= Number(this.env.LIGHTING_MAX_JOBS || 3000)) throw fail('lighting_storage_limit', 503);
      const directory = this.directory(id);
      await fs.mkdir(directory, { recursive: true });
      const sourceFile = `source.${kind === 'image' ? 'png' : 'mp4'}`;
      await fs.writeFile(path.join(directory, sourceFile + '.pending'), bytes);
      await fs.rename(path.join(directory, sourceFile + '.pending'), path.join(directory, sourceFile));
      job = { id, kind, source_sha256: source, source_bytes: bytes.length, recipe: RECIPE,
        source_file: sourceFile, status: 'queued', attempts: 0, created_at: new Date().toISOString() };
      await atomic(path.join(directory, 'job.json'), job);
      return job;
    });
    this.lock = operation.catch(() => {});
    const job = await operation;
    if (job.status === 'queued') { this.pending.add(id); this.drain(); }
    return job;
  }
  public(job) {
    return { id: job.id, kind: job.kind, status: job.status, source_sha256: job.source_sha256,
      recipe: job.recipe, error: job.error || null, manifest_sha256: job.manifest_sha256 || null,
      files: (job.files || []).map(file => ({ ...file, url: `/v1/lighting/jobs/${job.id}/assets/${file.file}` })) };
  }
  async resumeInterrupted() {
    await fs.mkdir(this.root, { recursive: true });
    for (const id of await fs.readdir(this.root)) {
      if (!/^[a-f0-9]{64}$/.test(id)) continue;
      const job = await this.get(id);
      if (!job || !['queued', 'running'].includes(job.status)) continue;
      job.status = job.attempts >= 3 ? 'failed' : 'queued';
      if (job.status === 'failed') job.error = 'lighting_interrupted_repeatedly';
      await atomic(path.join(this.directory(id), 'job.json'), job);
      if (job.status === 'queued') this.pending.add(id);
    }
    this.drain();
  }
  async drain() {
    if (this.busy) return;
    this.busy = true;
    try {
      while (this.pending.size) {
        const id = this.pending.values().next().value; this.pending.delete(id);
        await this.process(id);
      }
    } catch (error) { console.error('lighting_queue_failed:', error.message); }
    finally { this.busy = false; }
  }
  async process(id) {
    const job = await this.get(id);
    if (!job || job.status !== 'queued') return;
    const directory = this.directory(id), output = path.join(directory, 'pack.pending');
    job.status = 'running'; job.attempts += 1;
    await atomic(path.join(directory, 'job.json'), job);
    try {
      await fs.rm(output, { recursive: true, force: true });
      await fs.mkdir(output, { recursive: true });
      if (this.runner) await this.runner(job, output);
      else await this.runPython(job, output);
      const manifestBytes = await fs.readFile(path.join(output, 'manifest.json'));
      if (manifestBytes.length > 8 * 1024 * 1024) throw Error('lighting_manifest_too_large');
      const manifest = JSON.parse(manifestBytes);
      if (manifest.source_sha256 !== job.source_sha256) throw Error('lighting_output_source_mismatch');
      if (job.kind === 'video' && (manifest.format !== 'mascot-video-lighting' || manifest.source_bytes !== job.source_bytes)) throw Error('lighting_output_format');
      if (job.kind === 'image' && (manifest.format !== 'mascot-depth-2.5d' || !manifest.qa?.approved)) throw Error('lighting_output_format');
      const names = job.kind === 'video' ? manifest.pages : Object.values(manifest.assets).map(a => a.file);
      if (!Array.isArray(names) || !names.length || names.length > 4 || names.some(n => !safeFile(n) || !n.endsWith('.png'))) throw Error('lighting_output_files');
      const files = [];
      let total = 0;
      for (const name of ['manifest.json', ...names]) {
        const bytes = await fs.readFile(path.join(output, name)); total += bytes.length;
        if (total > 96 * 1024 * 1024) throw Error('lighting_output_too_large');
        files.push({ file: name, sha256: sha(bytes), size: bytes.length });
      }
      await fs.rm(path.join(directory, 'pack'), { recursive: true, force: true });
      await fs.rename(output, path.join(directory, 'pack'));
      Object.assign(job, { status: 'ready', manifest_sha256: sha(manifestBytes), files, completed_at: new Date().toISOString() });
      await atomic(path.join(directory, 'job.json'), job);
      await fs.rm(path.join(directory, 'work'), { recursive: true, force: true }).catch(() => {});
      // The source still lives on the phone / in the generator's library.
      // Discard only this job's uploaded working copy after durable publication.
      await fs.rm(path.join(directory, job.source_file), { force: true }).catch(() => {});
    } catch (error) {
      job.status = 'failed'; job.error = String(error.message).slice(0, 180);
      await atomic(path.join(directory, 'job.json'), job);
    }
  }
  runPython(job, output) {
    return new Promise((resolve, reject) => {
      const directory = this.directory(job.id);
      // No generator/API credentials cross into the inference process.
      const env = Object.fromEntries(['PATH', 'HOME', 'HF_HOME', 'TMPDIR'].filter(k => this.env[k]).map(k => [k, this.env[k]]));
      Object.assign(env, { HF_HUB_OFFLINE: '1', TRANSFORMERS_OFFLINE: '1', PYTHONUNBUFFERED: '1' });
      const child = spawn(this.env.LIGHTING_PYTHON || 'python3', [path.resolve(__dirname, '../scripts/prepare-lighting.py'),
        '--kind', job.kind, '--source', path.join(directory, job.source_file), '--output', output,
        '--work', path.join(directory, 'work')], { env, stdio: ['ignore', 'pipe', 'pipe'] });
      let tail = '';
      const capture = chunk => { tail = (tail + chunk.toString()).slice(-2000); };
      child.stdout.on('data', capture); child.stderr.on('data', capture);
      const timeout = setTimeout(() => child.kill('SIGKILL'), 90 * 60 * 1000);
      child.on('error', error => { clearTimeout(timeout); reject(error); });
      child.on('close', async code => {
        clearTimeout(timeout);
        await fs.writeFile(path.join(directory, 'processing.log'), tail).catch(() => {});
        if (code === 0) resolve(); else reject(Error(`lighting_inference_failed_${code ?? 'interrupted'}`));
      });
    });
  }
}
module.exports = { LightingJobs, RECIPE, sha };
