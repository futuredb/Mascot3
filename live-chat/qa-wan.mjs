import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { createWanService } from './wan.mjs';

export async function runWanChecks() {
  const results = [];
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'mascot-wan-qa-'));
  const assert = (v, text) => { if (!v) throw new Error(text); };
  const tick = () => new Promise(resolve => setImmediate(resolve));
  const input = { sessionId: 'qa-session', requestId: 'qa-request', heroId: 'una', prompt: 'A supportive nod' };
  const json = (body, status = 200) => ({ ok: status < 400, status, json: async () => body });
  const submitted = { request_id: 'qa-remote', status_url: 'https://queue.fal.run/fal-ai/wan/requests/qa-remote/status', response_url: 'https://queue.fal.run/fal-ai/wan/requests/qa-remote' };
  async function check(name, fn) {
    try { await fn(); results.push({ name, passed: true }); }
    catch (error) { results.push({ name, passed: false, error: error.message }); }
  }
  const setup = (name, fetchImpl, key = 'fake-qa-key') => ({ directory: path.join(root, name), getConfig: () => ({ wanKey: key }), getImage: () => Buffer.from('fixture'), fetchImpl });
  try {
    await check('Wan: повтор сообщения создаёт ровно одну генерацию, ключ остаётся на сервере', async () => {
      let count = 0, sent;
      const service = createWanService(setup('dedupe', async (_url, options) => { count++; sent = JSON.parse(options.body); return json(submitted); }));
      const a = service.start(input), b = service.start(input); await tick();
      assert(count === 1 && a.id === b.id, 'Duplicate submission');
      assert(sent.resolution === '480p' && sent.prompt === input.prompt && sent.image_url.startsWith('data:image/png;base64,'), 'Wrong model input');
      assert(!JSON.stringify(service.get(a.id)).includes('fake-qa-key'), 'Credential leaked');
    });
    await check('Wan: перезапуск восстанавливает задачу и получает видео без нового POST', async () => {
      let posts = 0;
      const options = setup('resume', async (url, request) => {
        if (request.method === 'POST') { posts++; return json(submitted); }
        return json(url.endsWith('/status') ? { status: 'COMPLETED' } : { video: { url: 'https://v3.fal.media/qa.mp4' } });
      });
      const first = createWanService(options); const job = first.start(input); await tick();
      const restored = createWanService(options); restored.start(input);
      const result = await restored.refresh(job.id);
      assert(posts === 1 && result.status === 'ready' && result.video.endsWith('/qa.mp4'), 'Resume generated again or lost result');
    });
    await check('Wan: неизвестный результат отправки блокирует повтор и параллельную оплату', async () => {
      let calls = 0;
      const service = createWanService(setup('unknown', async () => { calls++; throw new Error('network'); }));
      const job = service.start(input); await tick();
      service.start(input); const next = service.start({ ...input, requestId: 'another' });
      assert(calls === 1 && service.get(job.id).status === 'unknown' && next.status === 'busy', 'Unsafe retry');
    });
    await check('Wan: отсутствие ключа и ошибка доступа не запускают автоматических повторов', async () => {
      let calls = 0;
      const transport = async () => { calls++; return json({}, 403); };
      const missing = createWanService(setup('missing', transport, ''));
      assert(missing.start(input).status === 'unavailable' && calls === 0, 'Called without key');
      const denied = createWanService(setup('denied', transport)); const job = denied.start(input); await tick();
      denied.start(input); await denied.refresh(job.id);
      assert(calls === 1 && denied.get(job.id).status === 'error', 'Retried denied call');
    });
  } finally { fs.rmSync(root, { recursive: true, force: true }); }
  return results;
}
