import { test } from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import { readFile } from 'node:fs/promises';

// Exercise the actual bridge script with local stand-ins: no provider or paid stream.
async function harness({ cached = false, slowConnect = false } = {}) {
  const events = [], calls = [], timers = [], archives = [], instances = [];
  let resolveConnect;
  const connectGate = slowConnect ? new Promise(resolve => { resolveConnect = resolve; }) : Promise.resolve();
  const video = () => ({ videoWidth: 640, videoHeight: 360, play: async () => {}, pause() {}, load() {},
    removeAttribute() {}, requestVideoFrameCallback(fn) { this.frame = fn; }, set src(value) { this.url = value; } });
  const live = video(), replay = video();
  const ctx = { drawImage() {}, clearRect() {}, fillRect() {}, getImageData() { return { data: new Uint8Array(4) }; } };
  const frame = { style: {}, getContext: () => ctx };
  const document = { querySelector: s => ({ '#video': live, '#replay': replay, '#frame': frame })[s],
    createElement: () => ({ width: 0, height: 0, getContext: () => ctx, toBlob: fn => fn(new Blob(['ref'])) }) };
  class MockModel {
    constructor() { instances.push(this); }
    onMainVideo(fn) { this.onVideo = fn; } onCommandError() {}
    onChunkComplete(fn) { this.chunkComplete = fn; }
    async saveSnapshot() { return { snapshot_index: 1 }; }
    async rewind() { return {}; }
    on(name, fn) { if (name === 'statusChanged') this.statusChanged = fn; }
    async connect() { calls.push('connect'); await connectGate; }
    async uploadFile() { return { image: true }; }
    async setImageStrength() {}
    async pause() { calls.push('pause'); return { type: 'generation_paused' }; }
    async reset() { calls.push('reset'); }
    async start() { this.onVideo({}, {}); }
    async disconnect() { calls.push('disconnect'); }
  }
  class Recorder {
    static isTypeSupported() { return true; }
    constructor(_stream, options) { this.mimeType = options.mimeType; this.state = 'inactive'; }
    start() { this.state = 'recording'; }
    stop() { this.state = 'inactive'; this.ondataavailable?.({ data: new Blob(['x'.repeat(2048)]) }); this.onstop?.({}); }
  }
  class Reader { readAsDataURL() { this.result = 'data:video/webm;base64,Zm9v'; this.onload(); } }
  const bridge = { event: (kind, value) => events.push([kind, value]), reactorToken: () => '{"jwt":"local"}',
    saveIdleClip: () => { calls.push('save-idle'); return true; } };
  const sandbox = { document, window: null, MediaRecorder: Recorder, MediaStream: class {}, FileReader: Reader,
    Image: class { async decode() {} }, Blob, Uint8Array, performance: { now: () => 0 },
    URL: { createObjectURL: () => 'blob:mock', revokeObjectURL() {} },
    fetch: async () => ({ ok: cached, blob: async () => new Blob(['cached']) }),
    requestAnimationFrame() {}, setTimeout: (fn, ms) => { const t = { fn, ms, cancelled: false }; timers.push(t); return t; },
    clearTimeout: timer => { if (timer) timer.cancelled = true; },
    createReplayTransition: () => ({ render() {} }), hasVisibleDetail: () => true, HeliosModel: MockModel,
    createClipArchive: (_bridge, title) => title ? { append() {}, finish() { archives.push(title); } } : null,
    IDENTITY_REFRESH_MS: 15_000,
    conditionMascot: async (_model, _reference, ...args) => { calls.push({ action: args[0], context: args[1], appearance: args[2], reply: args[3] }); },
    updateMascotPrompt: async (_model, ...args) => { calls.push({ action: args[0], context: args[1], appearance: args[2], reply: args[3] }); } };
  sandbox.window = sandbox; sandbox.MascotBridge = bridge; sandbox.innerWidth = 100; sandbox.innerHeight = 100;
  sandbox.addEventListener = () => {};
  const source = (await readFile(new URL('./src/android-helios.js', import.meta.url), 'utf8')).replace(/^import .*;\s*$/gm, '');
  vm.runInNewContext(source, sandbox);
  await new Promise(resolve => setImmediate(resolve));
  return { sandbox, events, calls, timers, archives, instances, live, resolveConnect };
}

test('cached idle does not replace the native portrait before the live stream', async () => {
  const h = await harness({ cached: true });
  assert.ok(h.events.some(([type, detail]) => type === 'ready' && detail === 'missing'));
  assert.ok(!h.events.some(([type]) => type === 'replay'));
  assert.equal(h.instances.length, 0);
});

test('a long session reuses its latest identity and stops refreshing after exit', async () => {
  const h = await harness();
  await h.sandbox.reactMascot('local-image', 'idle', '', 'original cat');
  await h.sandbox.reactMascot('local-image', 'smiles', 'hello', 'original cat', 'answer');
  const timer = h.timers.findLast(t => t.ms === 30000 && !t.cancelled);
  assert.ok(timer);
  timer.fn();
  await new Promise(resolve => setImmediate(resolve));
  const prompts = h.calls.filter(x => typeof x === 'object');
  assert.equal(prompts.at(-1).appearance, 'original cat');
  assert.equal(prompts.at(-1).context, 'hello');
  assert.equal(prompts.at(-1).action, 'smiles');
  const next = h.timers.findLast(t => t.ms === 15000 && !t.cancelled);
  await h.sandbox.closeMascot();
  assert.equal(next.cancelled, true);
});

test('first idle is saved once, then the connection stops', async () => {
  const h = await harness();
  await h.sandbox.reactMascot('local-image', 'idle', '', 'identity', '', true);
  h.live.frame(1000);
  const timer = h.timers.find(t => t.ms === 6000 && !t.cancelled);
  assert.ok(timer, 'idle recording is bounded');
  await timer.fn();
  await new Promise(resolve => setImmediate(resolve));
  assert.ok(h.calls.includes('save-idle'));
  assert.ok(h.calls.includes('disconnect'));
  assert.ok(h.events.some(([type]) => type === 'idle_saved'));
  assert.deepEqual(h.archives, []);
});

test('a reply arriving during connection uses the newest action and keeps the reference', async () => {
  const h = await harness({ slowConnect: true });
  const first = h.sandbox.reactMascot('local-image', 'early', 'message', 'identity');
  await new Promise(resolve => setImmediate(resolve));
  await h.sandbox.reactMascot('local-image', 'refined', 'message', 'identity', 'answer');
  h.resolveConnect(); await first;
  const prompts = h.calls.filter(x => typeof x === 'object');
  assert.equal(prompts.length, 1);
  assert.deepEqual({ ...prompts[0] }, { action: 'refined', context: 'message', appearance: 'identity', reply: 'answer' });
  assert.equal(h.instances.length, 1);
  await h.sandbox.closeMascot();
});

test('provider disconnect after ready closes the old stream and reports the failure', async () => {
  const h = await harness();
  await h.sandbox.reactMascot('local-image', 'idle');
  h.instances[0].statusChanged('ready');
  h.instances[0].statusChanged('disconnected');
  await new Promise(resolve => setImmediate(resolve));
  assert.ok(h.events.some(([type]) => type === 'error'));
  assert.ok(h.calls.includes('disconnect'));
});
