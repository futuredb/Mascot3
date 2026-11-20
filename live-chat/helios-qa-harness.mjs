import vm from 'node:vm';
import { readFile } from 'node:fs/promises';
import { IDENTITY_REFRESH_MS } from './src/helios-conditioning.js';

export async function createHeliosHarness({ cached = false, slowConnect = false, eagerResetChunk = false, finite = false } = {}) {
  const events = [], calls = [], timers = [], archives = [], instances = [];
  let resolveConnect;
  const connectGate = slowConnect ? new Promise(resolve => { resolveConnect = resolve; }) : Promise.resolve();
  const video = () => ({ videoWidth: 640, videoHeight: 360, play: async () => {}, pause() {}, load() {},
    removeAttribute() {}, requestVideoFrameCallback(fn) { this.frame = fn; }, set src(value) { this.url = value; } });
  const live = video(), replay = video();
  const ctx = { drawImage() {}, clearRect() {}, fillRect() {}, getImageData() { return { data: new Uint8Array(4) }; } };
  const frameCtx = { ...ctx, drawImage() { calls.push('draw-frame'); } };
  const frame = { style: {}, getContext: () => frameCtx };
  const document = { querySelector: s => ({ '#video': live, '#replay': replay, '#frame': frame })[s],
    createElement: () => ({ width: 0, height: 0, getContext: () => ctx, toBlob: fn => fn(new Blob(['ref'])) }) };
  class MockModel {
    constructor() { this.starts = 0; instances.push(this); }
    onMainVideo(fn) { this.onVideo = fn; } onCommandError() {}
    onChunkComplete(fn) { this.chunkComplete = fn; }
    async saveSnapshot() { calls.push('save-snapshot'); return { snapshot_index: 1 }; }
    async rewind({ snapshot_index }) { calls.push(`rewind-${snapshot_index}`); return { snapshot_index }; }
    on(name, fn) { if (name === 'statusChanged') this.statusChanged = fn; }
    async connect() { calls.push('connect'); await connectGate; }
    async uploadFile() { return { image: true }; }
    async setImageStrength() {}
    async pause() { calls.push('pause'); return { type: 'generation_paused' }; }
    async reset() { calls.push('reset'); }
    async start() { this.starts++; this.onVideo({}, {}); if (eagerResetChunk && this.starts > 1) this.chunkComplete?.(); }
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
  const sandbox = { location: { search: finite ? "?finite=1" : "" }, document, window: null, MediaRecorder: Recorder, MediaStream: class {}, FileReader: Reader,
    Image: class { async decode() {} }, Blob, Uint8Array, performance: { now: () => 0 },
    URL: { createObjectURL: () => 'blob:mock', revokeObjectURL() {} },
    fetch: async () => ({ ok: cached, blob: async () => new Blob(['cached']) }),
    requestAnimationFrame() {}, setTimeout: (fn, ms) => { const t = { fn, ms, cancelled: false }; timers.push(t); return t; },
    clearTimeout: timer => { if (timer) timer.cancelled = true; },
    createReplayTransition: () => ({ render() {} }),
    createPortraitTransition: () => ({ begin() { calls.push('glitch-begin'); }, cancel() {}, render(source, _now, draw) { draw(source); } }), hasVisibleDetail: () => true, HeliosModel: MockModel,
    createClipArchive: (_bridge, title) => title ? { append() {}, finish() { archives.push(title); } } : null,
    IDENTITY_REFRESH_MS,
    conditionMascot: async (_model, _reference, ...args) => { calls.push({ action: args[0], context: args[1], appearance: args[2], reply: args[3] }); },
    updateMascotPrompt: async (_model, ...args) => { calls.push({ action: args[0], context: args[1], appearance: args[2], reply: args[3] }); } };
  sandbox.window = sandbox; sandbox.MascotBridge = bridge; sandbox.innerWidth = 100; sandbox.innerHeight = 100;
  sandbox.addEventListener = () => {};
  const source = (await readFile(new URL('./src/android-helios.js', import.meta.url), 'utf8')).replace(/^import .*;\s*$/gm, '');
  vm.runInNewContext(source, sandbox);
  await new Promise(resolve => setImmediate(resolve));
  return { sandbox, events, calls, timers, archives, instances, live, replay, resolveConnect };
}

