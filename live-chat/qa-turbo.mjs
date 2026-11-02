import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { createTurboService, TURBO_ENDPOINT } from './turbo.mjs';
export async function runTurboChecks() {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'mascot-turbo-qa-')), results = [];
  const assert = (v, text) => { if (!v) throw new Error(text); };
  const check = async (name, fn) => { try { await fn(); results.push({ name, passed: true }); } catch (error) { results.push({ name, passed: false, error: error.message }); } };
  const input = { sessionId: 'fixture', requestId: 'fixture-request', heroId: 'una', prompt: 'Small encouraging nod', chatMs: 123 };
  const setup = (name, fetchImpl) => ({ directory: path.join(root, name), getConfig: () => ({ turboKey: 'fixture-secret' }), getImageUrl: () => 'https://fal.media/our-una.png', fetchImpl });
  try {
    await check('Turbo: одна генерация из нашей картинки, 5 с / 480P, расширение промпта отключено', async () => {
      let calls = 0, resolve;
      const response = new Promise(done => { resolve = done; });
      const service = createTurboService(setup('contract', async (url, request) => {
        calls++; const body = JSON.parse(request.body);
        assert(url === `https://fal.run/${TURBO_ENDPOINT}` && body.image_url === 'https://fal.media/our-una.png' && body.duration === 5 && body.resolution === '480P' && body.prompt_expansion_mode === 'disabled', 'Wrong generation contract');
        assert(body.prompt.includes(input.prompt) && body.prompt.includes('Match the first and last frames'), 'Missing reaction or loop instruction');
        return response;
      }));
      const job = service.start(input); const duplicate = service.start(input);
      const waiting = service.wait(job.id);
      resolve({ ok: true, status: 200, json: async () => ({ video: { url: 'https://fal.media/result.mp4' }, timings: { inference: 0.8 } }) });
      const done = await waiting;
      assert(calls === 1 && duplicate.id === job.id && done.status === 'ready' && done.inferenceSeconds === 0.8, 'Duplicate request or lost notification');
      assert(!JSON.stringify(done).includes('fixture-secret'), 'Key exposed');
      const measured = service.measure(job.id, { firstFrameMs: 4300, replyReceivedMs: 1000, downloadDecodeMs: 200, visibleThroughout: true });
      const replay = service.measure(job.id, { firstFrameMs: 20, replyReceivedMs: 0, downloadDecodeMs: 20, visibleThroughout: true });
      assert(measured.measurement.withinFiveSeconds && replay.measurement.firstFrameMs === 4300, 'Replay overwrote real latency');
    });
    await check('Turbo: неизвестный исход и перезапуск не повторяют платный POST', async () => {
      let calls = 0;
      const options = setup('unknown', async () => { calls++; throw new Error('lost response'); });
      const service = createTurboService(options); const job = service.start(input); await service.wait(job.id);
      const restored = createTurboService(options);
      const old = restored.start(input); const next = restored.start({ ...input, requestId: 'fixture-next' });
      assert(calls === 1 && old.status === 'unknown' && next.status === 'busy', 'Unsafe paid retry');
    });
    await check('Turbo: готовое видео восстанавливается без генерации; скрытая вкладка не проходит порог', async () => {
      const options = setup('resume', async () => ({ ok: true, status: 200, json: async () => ({ video: { url: 'https://fal.media/result.mp4' } }) }));
      const service = createTurboService(options); const job = service.start(input); await service.wait(job.id);
      const restored = createTurboService({ ...options, fetchImpl: () => { throw new Error('Unexpected request'); } });
      assert(restored.start(input).status === 'ready', 'Restore failed');
      const result = restored.measure(job.id, { firstFrameMs: 3000, replyReceivedMs: 1000, downloadDecodeMs: 100, visibleThroughout: false });
      assert(!result.measurement.withinFiveSeconds, 'Hidden preview treated as valid timing');
    });
  } finally { fs.rmSync(root, { recursive: true, force: true }); }
  return results;
}
