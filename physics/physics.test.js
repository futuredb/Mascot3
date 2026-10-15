const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const os = require('node:os');
const path = require('node:path');
const { Providers, MODELS, atomicJson, dataUrl } = require('./providers');
const { PhysicsJobs } = require('./jobs');
const { PILOT_ID, FORMAT, VERSION, sha256, validateAnalysis, validatePack } = require('./contracts');
const { png, rgba, partMask, assemble, renderPack, neutralError } = require('./assets');

async function temporary(t) {
  const directory = await fs.mkdtemp(path.join(os.tmpdir(), 'mascot-physics-'));
  t.after(() => fs.rm(directory, { recursive: true, force: true }));
  return directory;
}
const part = { id: 'ribbon', material: 'cloth', prompt: 'single hanging ribbon', parent_id: 'body', bounds: [.35,.3,.7,.9], attachment: [.5,.375], order: 1 };
async function fixture() {
  const w = 128, h = 128, body = Buffer.alloc(w*h*4), original = Buffer.alloc(w*h*4), mask = Buffer.alloc(w*h), depth = Buffer.alloc(w*h, 128);
  for (let y = 24; y < 110; y++) for (let x = 32; x < 100; x++) {
    const i = (y*w+x)*4;
    body[i] = 70; body[i+1] = 100; body[i+2] = 180; body[i+3] = 255;
  }
  body.copy(original);
  for (let y = 48; y < 104; y++) for (let x = 60; x < 72; x++) {
    const i = y*w+x; mask[i] = 255;
    original[i*4] = 220; original[i*4+1] = 70; original[i*4+2] = 20;
  }
  return { original: await png(original,w,h), restored: await png(body,w,h), depthImage: await png(depth,w,h,1), masks: [mask], parts: [structuredClone(part)] };
}
const response = value => ({ ok: true, json: async () => value });

test('lost paid submissions are blocked, including after process restart', async t => {
  const directory = await temporary(t); let posts = 0;
  const opts = { env: { REPLICATE_API_TOKEN: 'fixture' }, directory, fetchImpl: async () => { posts++; throw new Error('connection lost'); } };
  await assert.rejects(new Providers(opts).replicate('matte', MODELS.matte, {image:'fixture'}), /submission_unknown/);
  await assert.rejects(new Providers(opts).replicate('matte', MODELS.matte, {image:'fixture'}), /submission_unknown/);
  assert.equal(posts, 1);
});

test('known prediction resumes by GET and caches success without another paid POST', async t => {
  const directory = await temporary(t); const calls = [];
  const input = {image:'fixture'};
  await atomicJson(path.join(directory,'depth.json'), {state:'submitted',model:MODELS.depth, request_hash:sha256(JSON.stringify({model:MODELS.depth,input})), provider_id:'abc123'});
  const providers = new Providers({env:{REPLICATE_API_TOKEN:'fixture'},directory,fetchImpl: async (url, options) => {
    calls.push(options.method || 'GET'); assert.ok(url.endsWith('/abc123'));
    return response({status:'succeeded',output:{grey_depth:'fixture'},metrics:{predict_time:1.2}});
  }});
  assert.deepEqual(await providers.replicate('depth',MODELS.depth,input),{grey_depth:'fixture'});
  await providers.replicate('depth',MODELS.depth,input);
  assert.deepEqual(calls,['GET']);
  await assert.rejects(providers.replicate('depth',MODELS.depth,{image:'changed'}), /provider_request_changed/);
});

test('OpenRouter unknown outcomes also never purchase automatically twice', async t => {
  let calls = 0;
  const providers = new Providers({env:{OPENROUTER_API_KEY:'fixture'},directory:await temporary(t),fetchImpl:async()=>{calls++; throw new Error('lost');}});
  await assert.rejects(providers.vision('review','prompt',[]),/submission_unknown/);
  await assert.rejects(providers.vision('review','prompt',[]),/submission_unknown/);
  assert.equal(calls,1);
});

test('analysis rejects invented materials and invalid attachment graph', () => {
  validateAnalysis({parts:[part]});
  assert.throws(()=>validateAnalysis({parts:[{...part,parent_id:'missing'}]}),/parent/);
  assert.throws(()=>validateAnalysis({parts:[{...part,attachment:[2,0]}]}),/attachment/);
  assert.throws(()=>validateAnalysis({parts:[{...part,material:'rubber'}]}),/material/);
});

test('assembly preserves neutral appearance, pins roots, and rejects bad geometry', async t => {
  const directory=await temporary(t), f=await fixture();
  const assembled=await assemble({...f,directory});
  const pack={format:FORMAT,version:VERSION,source_sha256:sha256(f.original),anchor_sha256:sha256(f.original),max_tilt_degrees:12,...assembled,qa:{approved:true,neutral_error:0}};
  validatePack(pack);
  assert.ok(pack.layers[1].vertices.some(v=>v[5]===0));
  assert.ok(pack.layers[1].vertices.some(v=>v[5]>.5));
  assert.ok(pack.layers[1].bends.length > 0);
  assert.ok(await neutralError(f.original,await renderPack(pack,directory)) < .001);
  const broken=structuredClone(pack); broken.layers[1].pins=[];
  assert.throws(()=>validatePack(broken),/unattached/);
  const inverted=structuredClone(pack); inverted.layers[1].triangles[0]=inverted.layers[1].triangles[1];
  assert.throws(()=>validatePack(inverted),/degenerate/);
  const unapproved=structuredClone(pack); unapproved.qa.approved=false;
  assert.throws(()=>validatePack(unapproved),/qa/);
  for(const x of [-1,1]) for(const y of [-1,1]) assert.ok((await renderPack(pack,directory,{tiltX:x*Math.PI/15,tiltY:y*Math.PI/15,secondary:x})).length>0);
});

test('segmentation rejects disconnected instances and attachment outside the mask', async t=>{
  const mask=Buffer.alloc(128*128), alpha=Buffer.alloc(mask.length,255);
  for(let y=20;y<40;y++) for(let x=20;x<40;x++) {mask[y*128+x]=255;mask[(y+60)*128+x+60]=255;}
  await assert.rejects(partMask(await png(mask,128,128,1),alpha,128,128,[0,0,1,1]),/disconnected/);
  const f=await fixture(); f.parts[0].attachment=[.01,.01];
  await assert.rejects(assemble({...f,directory:await temporary(t)}),/attachment_not_on_part/);
});

test('pilot gating and request idempotency preserve the known job',async t=>{
  const root=await temporary(t), f=await fixture();
  const jobs=new PhysicsJobs({root,env:{PHYSICS_ENABLED:'true'}});
  const input={mascot_id:PILOT_ID,image_base64:f.original.toString('base64'),character_contract:{name:'fixture'}};
  const first=await jobs.create(input,'fixture-idempotency'); await jobs.start(first.id);
  assert.equal((await jobs.get(first.id)).error,'replicate_token_missing');
  const again=await jobs.create(input,'fixture-another-key'); await jobs.start(again.id);
  assert.equal(first.id,again.id);
  await assert.rejects(jobs.create({...input,character_contract:{changed:true}},'fixture-idempotency'),/conflict/);
  await assert.rejects(jobs.create({...input,mascot_id:'other'},'fixture-other-id'),/pilot_only/);
  assert.equal(jobs.readyForMascot(PILOT_ID),null);
});

test('CLI and server sharing a directory cannot create duplicate input jobs',async t=>{
  const root=await temporary(t), f=await fixture();
  const env={PHYSICS_ENABLED:'true'};
  const a=new PhysicsJobs({root,env}), b=new PhysicsJobs({root,env});
  const input={mascot_id:PILOT_ID,image_base64:f.original.toString('base64'),character_contract:{}};
  const [x,y]=await Promise.all([a.create(input,'fixture-process-a'),b.create(input,'fixture-process-b')]);
  await Promise.all([a.start(x.id),b.start(y.id)]);
  assert.equal(x.id,y.id);
  assert.equal((await fs.readdir(root)).filter(n=>/^[a-f0-9]{32}$/.test(n)).length,1);
});

test('entire local pipeline publishes only when independent review approves',async t=>{
  for(const approved of [false,true]) {
    const root=await temporary(t), f=await fixture(), models=[];
    const jobs=new PhysicsJobs({root,env:{PHYSICS_ENABLED:'true',REPLICATE_API_TOKEN:'fixture',OPENROUTER_API_KEY:'fixture'},providerFactory:()=>({
      download:async uri=>Buffer.from(uri.split(',')[1],'base64'),
      replicate:async(stage,model,input)=>{models.push([stage,model,input.model_size]); if(stage==='depth') return {grey_depth:dataUrl(f.depthImage)}; return dataUrl(stage==='matte'?f.original:stage==='restored_matte'?f.restored:await png(f.masks[0],128,128,1));},
      vision:async(stage)=> stage==='analysis'?{parts:[part]}:stage==='reconstruction'?dataUrl(f.restored):{identity_preserved:approved,neutral_matches:true,no_holes_or_duplicates:true,attachments_correct:true,rigid_parts_preserved:true,depth_plausible:true},
    })});
    const job=await jobs.create({mascot_id:PILOT_ID,image_base64:f.original.toString('base64'),character_contract:{}},`fixture-pipeline-${approved}`);
    await jobs.start(job.id); const result=await jobs.get(job.id);
    assert.equal(result.status,approved?'ready':'failed',JSON.stringify(result));
    assert.equal(!!jobs.public(result).physics_pack,approved);
    assert.ok(models.some(([stage,,size])=>stage==='depth'&&size==='Small'));
    if(approved) {
      const bytes=await fs.readFile(path.join(root,job.id,'pack/manifest.json'));
      assert.equal(sha256(bytes),result.pack_sha256); validatePack(JSON.parse(bytes));
    } else await assert.rejects(fs.access(path.join(root,job.id,'pack/manifest.json')));
  }
});
