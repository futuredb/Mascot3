const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const os = require('node:os');
const path = require('node:path');
const { Providers, MODELS, atomicJson, dataUrl } = require('./providers');
const { PhysicsJobs } = require('./jobs');
const { PILOT_ID, FORMAT, VERSION, sha256, validateAnalysis, validatePack } = require('./contracts');
const { png, rgba, analysisFrame, partMask, assemble, renderPack, neutralError } = require('./assets');
const { maskCandidates, addCandidates, selectCandidateParts } = require('./segmentation');

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

test('renamed stages reuse identical SAM requests and cannot bypass an unknown submission', async t => {
  const directory=await temporary(t), input={image:'fixture',prompt:'cloth'}; let posts=0;
  const provider=new Providers({env:{REPLICATE_API_TOKEN:'fixture'},directory,fetchImpl:async(_url,options)=>{
    if(options.method==='POST') { posts++; return response({id:'prediction1'}); }
    return response({status:'succeeded',output:'mask',metrics:{predict_time:1}});
  }});
  await provider.replicate('original_stage',MODELS.masks,input);
  assert.equal(await provider.replicate('new_strategy_stage',MODELS.masks,input),'mask');
  assert.equal(posts,1);
  assert.equal(JSON.parse(await fs.readFile(path.join(directory,'new_strategy_stage.json'))).cost_usd,0);
  const unknown=new Providers({env:{REPLICATE_API_TOKEN:'fixture'},directory:await temporary(t),fetchImpl:async()=>{posts++;throw Error('lost');}});
  await assert.rejects(unknown.replicate('first',MODELS.masks,input),/submission_unknown/);
  await assert.rejects(unknown.replicate('renamed',MODELS.masks,input),/submission_unknown/);
  assert.equal(posts,2);
});

test('SAM instances supply bounds and roots; incomplete or duplicate selections fail closed', async()=>{
  const width=128,height=128,alpha=Buffer.alloc(width*height,255),union=Buffer.alloc(alpha.length);
  for(let y=20;y<80;y++) for(let x=20;x<27;x++) {union[y*width+x]=255;union[y*width+x+60]=255;}
  const candidates=await maskCandidates(await png(union,width,height,1),alpha,width,height);
  assert.equal(candidates.length,2);
  addCandidates(candidates,await maskCandidates(await png(union,width,height,1),alpha,width,height));
  assert.equal(candidates.length,2);
  const review={complete:true,rigid_regions_excluded:true,parts:[{...part,candidate_id:'C1',attachment_region:'top'}]};
  const {analysis,masks}=selectCandidateParts(review,candidates,width,height);
  assert.deepEqual(analysis.parts[0].bounds,[20/128,20/128,27/128,80/128]);
  assert.equal(analysis.parts[0].attachment[1],20/128);
  assert.equal(masks[0][20*width+23],255);
  assert.throws(()=>selectCandidateParts({...review,complete:false},candidates,width,height),/review_rejected/);
  assert.throws(()=>selectCandidateParts({...review,parts:[...review.parts,{...review.parts[0],id:'duplicate'}]},candidates,width,height),/candidate_selection/);
  assert.throws(()=>selectCandidateParts({...review,parts:[{...review.parts[0],candidate_id:'C99'}]},candidates,width,height),/candidate_selection/);
});

test('explicit rejected creation is recorded and can resume; server errors stay unknown', async t => {
  const directory = await temporary(t); let posts = 0;
  const providers = new Providers({env:{REPLICATE_API_TOKEN:'fixture'}, directory, fetchImpl:async (url, options)=>{
    if (options.method === 'POST') { posts++; return posts === 1 ? {ok:false,status:402} : response({id:'accepted123'}); }
    return response({status:'succeeded',output:'fixture'});
  }});
  await assert.rejects(providers.replicate('matte',MODELS.matte,{image:'fixture'}),/provider_rejected:matte:402/);
  const rejection=JSON.parse(await fs.readFile(path.join(directory,'matte.json')));
  assert.equal(rejection.state,'rejected'); assert.equal(rejection.cost_usd,0);
  assert.equal(await providers.replicate('matte',MODELS.matte,{image:'fixture'}),'fixture'); assert.equal(posts,2);
  assert.equal(JSON.parse(await fs.readFile(path.join(directory,'matte.json'))).attempts[0].error,'provider_http_402');
  let calls=0;
  const ambiguous=new Providers({env:{OPENROUTER_API_KEY:'fixture'},directory,fetchImpl:async()=>{calls++;return {ok:false,status:503};}});
  await assert.rejects(ambiguous.vision('review','prompt',[]),/submission_unknown/);
  await assert.rejects(ambiguous.vision('review','prompt',[]),/submission_unknown/);
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
    const {frame}=await analysisFrame(f.original);
    const croppedPart={...part,bounds:[(part.bounds[0]*128-frame.left)/frame.width,(part.bounds[1]*128-frame.top)/frame.height,(part.bounds[2]*128-frame.left)/frame.width,(part.bounds[3]*128-frame.top)/frame.height],attachment:[(part.attachment[0]*128-frame.left)/frame.width,(part.attachment[1]*128-frame.top)/frame.height]};
    const croppedMask=await require('sharp')(await png(f.masks[0],128,128,1)).extract({left:frame.left,top:frame.top,width:frame.width,height:frame.height}).png().toBuffer();
    const jobs=new PhysicsJobs({root,env:{PHYSICS_ENABLED:'true',REPLICATE_API_TOKEN:'fixture',OPENROUTER_API_KEY:'fixture'},providerFactory:()=>({
      download:async uri=>Buffer.from(uri.split(',')[1],'base64'),
      replicate:async(stage,model,input)=>{models.push([stage,model,input.model_size]); if(stage==='depth') return {grey_depth:dataUrl(f.depthImage)}; if(input.prompt===part.prompt) return dataUrl(await png(Buffer.alloc(frame.width*frame.height),frame.width,frame.height,1)); return dataUrl(stage==='matte'?f.original:stage.startsWith('restored_matte_')?f.restored:croppedMask);},
      vision:async(stage)=> stage==='analysis_grid_v1'?{parts:[structuredClone(croppedPart)]}:stage.startsWith('binding_v1_')?{complete:true,rigid_regions_excluded:true,parts:[{...part,candidate_id:'C1',attachment_region:'top'}]}:stage.startsWith('reconstruction_')?dataUrl(f.restored):{identity_preserved:approved,neutral_matches:true,no_holes_or_duplicates:true,attachments_correct:true,rigid_parts_preserved:true,depth_plausible:true},
    })});
    const job=await jobs.create({mascot_id:PILOT_ID,image_base64:f.original.toString('base64'),character_contract:{}},`fixture-pipeline-${approved}`);
    await jobs.start(job.id); const result=await jobs.get(job.id);
    assert.equal(result.status,approved?'ready':'failed',JSON.stringify(result));
    assert.equal(!!jobs.public(result).physics_pack,approved);
    assert.ok(models.some(([stage,,size])=>stage==='depth'&&size==='Small'));
    assert.ok(models.some(([stage])=>stage.startsWith('mask_category_')));
    if(approved) {
      const bytes=await fs.readFile(path.join(root,job.id,'pack/manifest.json'));
      assert.equal(sha256(bytes),result.pack_sha256); validatePack(JSON.parse(bytes));
    } else await assert.rejects(fs.access(path.join(root,job.id,'pack/manifest.json')));
  }
});
