const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const { sha256, requireValue, validatePack } = require('./contracts');

// Only curated, already approved assets are shipped. Never resume a paid operation
// from a deployment seed, or overwrite a job already present on the data volume.
function importPhysicsSeeds(source, destination) {
  if (!fs.existsSync(source)) return [];
  fs.mkdirSync(destination, { recursive: true });
  const imported = [];
  for (const id of fs.readdirSync(source)) {
    requireValue(/^[a-f0-9]{32}$/.test(id), 'invalid_seed_job_id');
    const target = path.join(destination, id);
    if (fs.existsSync(target)) continue;
    const lock = path.join(destination, `seed-${id}.lock`);
    let handle;
    try { handle = fs.openSync(lock, 'wx'); }
    catch (error) { if (error.code === 'EEXIST') continue; throw error; }
    const staging = path.join(destination, `.seed-${id}-${crypto.randomUUID()}`);
    try {
      if (fs.existsSync(target)) continue;
      const input = path.join(source, id);
      const read = name => {
        const file = path.join(input, name);
        requireValue(fs.lstatSync(file).isFile(), 'seed_requires_regular_file');
        return fs.readFileSync(file);
      };
      const job = JSON.parse(read('job.json'));
      requireValue(job.id === id && job.status === 'ready' && job.stage === 'complete' && !job.error, 'seed_job_not_ready');
      const manifest = read('pack/manifest.json');
      requireValue(sha256(manifest) === job.pack_sha256, 'seed_manifest_hash_mismatch');
      const pack = validatePack(JSON.parse(manifest));
      const image = read('source.png'), anchor = read('anchor.png'), contract = read('character.json');
      const sourceHash = sha256(image), anchorHash = sha256(anchor);
      requireValue(sourceHash === job.source_sha256 && sourceHash === pack.source_sha256 && anchorHash === pack.video_anchor_sha256, 'seed_source_hash_mismatch');
      requireValue(sha256(JSON.stringify({ sourceHash, anchorHash, contract: JSON.parse(contract), mascotId: job.mascot_id })) === job.request_hash, 'seed_request_hash_mismatch');
      const files = new Map([['job.json', Buffer.from(JSON.stringify(job, null, 2))], ['source.png', image], ['anchor.png', anchor], ['character.json', contract], ['pack/manifest.json', manifest]]);
      for (const asset of Object.values(pack.assets)) {
        requireValue(asset.url === `/v1/physics/jobs/${id}/assets/${asset.file}`, 'seed_asset_url_mismatch');
        const bytes = read(`pack/${asset.file}`);
        requireValue(bytes.length === asset.size && sha256(bytes) === asset.sha256, 'seed_asset_hash_mismatch');
        files.set(`pack/${asset.file}`, bytes);
      }
      fs.mkdirSync(path.join(staging, 'pack'), { recursive: true });
      for (const [name, bytes] of files) fs.writeFileSync(path.join(staging, name), bytes);
      fs.renameSync(staging, target);
      imported.push(id);
    } finally {
      fs.rmSync(staging, { recursive: true, force: true });
      fs.closeSync(handle);
      fs.unlinkSync(lock);
    }
  }
  return imported;
}

module.exports = { importPhysicsSeeds };
