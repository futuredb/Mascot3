import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { createH3Service, H3_ENDPOINT } from './h3.mjs';
export async function runH3Checks() {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'mascot-h3-qa-')), results = [];
  const assert = (v, text) => { if (!v) throw new Error(text); };
  const check = async (name, fn) => { try { await fn(); results.push({ name, passed: true }); } catch (error) { results.push({ name, passed: false, error: error.message }); } };
  let clock = Date.now(), calls = 0;
  const options = { directory: root, getConfig: () => ({ h3Key: 'fixture-key' }), getImage: () => Buffer.from('fixture'), now: () => clock,
    upload: async () => 'https://fal.media/fixture.png', fetchImpl: async (_url, init) => { calls++; assert(JSON.parse(init.body).app_id === H3_ENDPOINT, 'Endpoint not pinned'); return { ok: true, status: 200, json: async () => ({ session_id: 'fixture-session', sdp: 'fixture-answer', type: 'answer' }) }; } };
  try {
    const service = createH3Service(options);
    const run = await service.prepare({ sessionId: 'fixture', requestId: 'fixture-1', heroId: 'una' });
    await check('H3: один запуск WebRTC при повторной отправке, адрес модели закреплён', async () => {
      const input = { app_id: 'other-model', sdp: 'fixture-offer', type: 'offer' };
      await Promise.all([service.proxy(run.runId, 'https://wma.fal.run/session', input), service.proxy(run.runId, 'https://wma.fal.run/session', input)]);
      assert(calls === 1, 'Duplicate paid allocation');
    });
    await check('H3: чужой адрес и чужая сессия не получают ключ или heartbeat', async () => {
      const bad = await service.proxy(run.runId, 'https://example.com', {}).catch(e => e.code);
      const other = await service.proxy(run.runId, 'https://wma.fal.run/session/heartbeat', { session_id: 'other' }).catch(e => e.code);
      assert(bad === 'H3_TARGET' && other === 'H3_SESSION' && calls === 1, 'Invalid request forwarded');
    });
    await check('H3: лимит 60 с прекращает heartbeat, перезапуск сервера не повторяет подключение', async () => {
      const restored = createH3Service(options);
      const duplicate = await restored.proxy(run.runId, 'https://wma.fal.run/session', { sdp: 'fixture', type: 'offer' }).catch(e => e.code);
      clock += 60_001;
      const heartbeat = await restored.proxy(run.runId, 'https://wma.fal.run/session/heartbeat', { session_id: 'fixture-session' });
      assert(duplicate === 'H3_DUPLICATE' && heartbeat.data.alive === false && calls === 1, 'Unsafe renewal');
    });
  } finally { fs.rmSync(root, { recursive: true, force: true }); }
  return results;
}
