const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { importPhysicsSeeds } = require('./seed');
const source = path.resolve(__dirname, '../deploy/seed/physics');
const id = fs.readdirSync(source)[0];
function temporary(t) {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'physics-seed-'));
  t.after(() => fs.rmSync(directory, { recursive: true, force: true }));
  return directory;
}
test('approved deployment seed is verified, atomic and never overwrites an existing job', t => {
  const destination = temporary(t);
  assert.deepEqual(importPhysicsSeeds(source, destination), [id]);
  assert.deepEqual(importPhysicsSeeds(source, destination), []);
  const job = path.join(destination, id, 'job.json');
  fs.writeFileSync(job, '{"status":"running","paid_submission":"keep"}');
  assert.deepEqual(importPhysicsSeeds(source, destination), []);
  assert.equal(JSON.parse(fs.readFileSync(job)).paid_submission, 'keep');
  assert.deepEqual(fs.readdirSync(destination), [id]);
});
test('corrupt seed fails before publication and leaves persistent jobs intact', t => {
  const root = temporary(t), input = path.join(root, 'input'), output = path.join(root, 'output');
  fs.cpSync(source, input, { recursive: true });
  fs.appendFileSync(path.join(input, id, 'pack/body_depth.png'), 'broken');
  assert.throws(() => importPhysicsSeeds(input, output), /seed_asset_hash_mismatch/);
  assert.deepEqual(fs.readdirSync(output), []);
});
test('an active import lock or absent seed cannot purchase or replace anything', t => {
  const root = temporary(t);
  assert.deepEqual(importPhysicsSeeds(path.join(root, 'absent'), root), []);
  fs.writeFileSync(path.join(root, `seed-${id}.lock`), 'other process');
  assert.deepEqual(importPhysicsSeeds(source, root), []);
  assert.deepEqual(fs.readdirSync(root), [`seed-${id}.lock`]);
});
