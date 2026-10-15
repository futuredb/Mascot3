const fs = require('node:fs/promises');
const path = require('node:path');
const { PhysicsJobs } = require('../physics/jobs');
const { PILOT_ID } = require('../physics/contracts');
const { loadEnvFileDefaults } = require('../extensions/02_tools_and_apps/_shared/env_file');
const ROOT = path.resolve(__dirname, '..');
async function main() {
  const options = Object.fromEntries(process.argv.slice(2).map(v => { const index = v.indexOf('='); return index < 0 ? [v.replace(/^--/, ''), true] : [v.slice(2, index), v.slice(index + 1)]; }));
  const env = loadEnvFileDefaults({ env: process.env, workspaceRoot: ROOT });
  const hero = options.hero || PILOT_ID;
  const sourceRoot = path.join(ROOT, 'android/app/src/main/assets/bundled-heroes', hero);
  const image = await fs.readFile(options.image || path.join(sourceRoot, 'base.png'));
  const anchor = await fs.readFile(options.anchor || path.join(sourceRoot, 'videos/neutral.png')).catch(() => image);
  const contract = JSON.parse(await fs.readFile(options.contract || path.join(sourceRoot, 'creative/character-contract.json'), 'utf8'));
  if (options.submit !== 'yes') {
    console.log(JSON.stringify({ mode: 'dry-run', mascot_id: hero, image_bytes: image.length, replicate_configured: !!env.REPLICATE_API_TOKEN, openrouter_configured: !!env.OPENROUTER_API_KEY, note: 'No paid requests. --submit=yes starts one resumable preparation, never a new character or video.' }, null, 2)); return;
  }
  if (!env.REPLICATE_API_TOKEN || !env.OPENROUTER_API_KEY) throw new Error('Set REPLICATE_API_TOKEN and OPENROUTER_API_KEY in the local .env before submitting.');
  const root = path.join(env.MASCOT3_DATA_DIR || ROOT, 'server-data/physics');
  const jobs = new PhysicsJobs({ root, env: { ...env, PHYSICS_ENABLED: 'true' } });
  const job = await jobs.create({ mascot_id: hero, image_base64: image.toString('base64'), anchor_base64: anchor.toString('base64'), character_contract: contract }, options.key || `physics-v1-${hero}`);
  await jobs.start(job.id);
  let result = await jobs.get(job.id);
  const deadline=Date.now()+20*60*1000;
  while(result.status==='queued' && Date.now()<deadline) {
    console.log(JSON.stringify({id:result.id,status:result.status,stage:result.stage,error:result.error}));
    await new Promise(resolve=>setTimeout(resolve,30000));
    await jobs.start(job.id); result=await jobs.get(job.id);
  }
  console.log(JSON.stringify({ ...jobs.public(result), directory: jobs.directory(job.id) }, null, 2));
  if (result.status !== 'ready') process.exitCode = 1;
}
main().catch(e => { console.error(e.message); process.exitCode = 1; });
