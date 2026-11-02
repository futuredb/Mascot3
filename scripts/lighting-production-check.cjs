// Invoked only by the local Codex preview button. Processes existing media.
const fs = require('node:fs/promises');
const path = require('node:path');
const crypto = require('node:crypto');
const root = path.resolve(__dirname, '..');
const output = path.join(root, 'output/automatic-lighting-0.1.60/production');
const origin = 'https://mascot3.45-91-52-137.sslip.io';
const sha = bytes => crypto.createHash('sha256').update(bytes).digest('hex');

async function verify(update) {
  await fs.mkdir(output, { recursive: true });
  const build = JSON.parse(await fs.readFile(path.join(root, 'output/phone-interactions/build-environment.json'), 'utf8'));
  const token = build.ORG_GRADLE_PROJECT_MASCOT3_CLIENT_TOKEN;
  if (!token) throw Error('Client token is not configured');
  async function request(url, options = {}) {
    if (!url.startsWith(origin + '/')) throw Error('Unexpected asset origin');
    return fetch(url, { ...options, redirect: 'error', headers: { 'X-Mascot-Client-Token': token }, signal: AbortSignal.timeout(120000) });
  }
  const health = await request(origin + '/health');
  if (!health.ok || (await health.json()).status !== 'ok') throw Error('Server health failed');
  let available = false;
  for (let poll = 0; poll < 360; poll++) {
    const probe = await request(origin + '/v1/lighting/sources/image/' + '0'.repeat(64));
    const body = await probe.json();
    if (probe.status === 404 && body.error === 'lighting_not_found') { available = true; break; }
    if (probe.status === 401) throw Error('Client authorization failed');
    update({ status: 'running', stage: 'Ожидаем новую версию сервера после сборки Coolify' });
    await new Promise(resolve => setTimeout(resolve, 10000));
  }
  if (!available) throw Error('Deployment readiness timeout');
  const results = [];
  for (const [kind, name, relative] of [
    ['image', 'Уна · изображение', 'base.png'],
    ['video', 'Уна · готовая анимация', 'videos/idle.mp4'],
  ]) {
    update({ status: 'running', stage: name + ': отправляем существующий файл', results });
    const source = await fs.readFile(path.join(root, 'android/app/src/main/assets/bundled-heroes/716cdb43-703b-4d7e-8a79-27f03d92b99c', relative));
    const hash = sha(source), url = origin + '/v1/lighting/sources/' + kind + '/' + hash;
    let response = await request(url);
    if (response.status === 404) response = await request(url, { method: 'PUT', body: source });
    if (!response.ok) throw Error('Lighting endpoint HTTP ' + response.status);
    let job = await response.json();
    for (let poll = 0; !['ready', 'failed'].includes(job.status) && poll < 360; poll++) {
      update({ status: 'running', stage: name + ': ' + job.status, results });
      await new Promise(resolve => setTimeout(resolve, 5000));
      response = await request(origin + '/v1/lighting/jobs/' + job.id);
      if (!response.ok) throw Error('Lighting polling HTTP ' + response.status);
      job = await response.json();
    }
    if (job.status !== 'ready' || job.kind !== kind || job.source_sha256 !== hash) throw Error('Lighting job ' + job.status + ': ' + (job.error || 'timeout or mismatch'));
    const directory = path.join(output, job.id); await fs.mkdir(directory, { recursive: true });
    for (const asset of job.files) {
      if (!/^(manifest\.json|body_(color|depth)\.png|normals-\d{3}\.png)$/.test(asset.file) || asset.url !== '/v1/lighting/jobs/' + job.id + '/assets/' + asset.file) throw Error('Unexpected asset path');
      const response = await request(origin + asset.url);
      if (!response.ok) throw Error('Asset HTTP ' + response.status);
      const bytes = Buffer.from(await response.arrayBuffer());
      if (bytes.length !== asset.size || sha(bytes) !== asset.sha256) throw Error('Corrupt asset');
      await fs.writeFile(path.join(directory, asset.file), bytes);
    }
    const bytes = await fs.readFile(path.join(directory, 'manifest.json'));
    if (sha(bytes) !== job.manifest_sha256) throw Error('Corrupt manifest');
    const manifest = JSON.parse(bytes);
    if (manifest.source_sha256 !== hash) throw Error('Wrong source in manifest');
    if (kind === 'image' && sha(await fs.readFile(path.join(directory, 'body_color.png'))) !== hash) throw Error('Original image changed');
    if (kind === 'video' && (manifest.timestamps_us.length !== 145 || manifest.generated_rgb_frames !== 0)) throw Error('Video frame mismatch');
    results.push({ name, kind, job: job.id, source_sha256: hash, files: job.files.length, frames: manifest.timestamps_us?.length, status: 'ready' });
  }
  const report = { status: 'passed', generated_media: 0, results, completed_at: new Date().toISOString() };
  await fs.writeFile(path.join(output, 'validation.json'), JSON.stringify(report, null, 2));
  update(report);
}
module.exports = { verify };
