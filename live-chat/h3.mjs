import fs from 'node:fs';
import path from 'node:path';
import { randomUUID, createHash } from 'node:crypto';
import { createFalClient } from '@fal-ai/client';
import { AppError } from './chat.mjs';

export const H3_ENDPOINT = 'minimax/h3-max/reference-director';
export function createH3Service({ directory, getConfig, getImage, fetchImpl = fetch, now = Date.now, upload }) {
  fs.mkdirSync(directory, { recursive: true });
  const runs = new Map(), pending = new Map();
  for (const file of fs.readdirSync(directory).filter(x => /^run-.*\.json$/.test(x))) {
    const run = JSON.parse(fs.readFileSync(path.join(directory, file), 'utf8')); runs.set(run.id, run);
  }
  const save = run => {
    const file = path.join(directory, `run-${run.id}.json`);
    fs.writeFileSync(`${file}.tmp`, JSON.stringify(run, null, 2), { mode: 0o600 }); fs.renameSync(`${file}.tmp`, file);
  };
  const get = id => { const run = runs.get(id); if (!run) throw new AppError(404, 'H3_RUN', 'Live-сессия не найдена.'); return run; };
  async function prepare({ sessionId, requestId, heroId }) {
    if (typeof requestId !== 'string' || !/^[\w-]{8,80}$/.test(requestId)) throw new AppError(400, 'REQUEST_ID', 'Неверный запрос.');
    if (!getConfig().h3Key) throw new AppError(503, 'H3_KEY', 'Нет ключа H3 Reference.');
    const previous = [...runs.values()].find(r => r.sessionId === sessionId && r.requestId === requestId);
    if (previous) return prepared(previous);
    if ([...runs.values()].some(r => r.status === 'unknown' || (!r.endedAt && r.expiresAt > now()))) throw new AppError(409, 'H3_BUSY', 'Одна live-сессия уже открыта. Сначала останови её.');
    const run = { id: randomUUID(), sessionId, requestId, heroId, createdAt: now(), expiresAt: now() + 60_000, status: 'preparing', events: [] };
    runs.set(run.id, run); save(run);
    try {
      const bytes = getImage(heroId), hash = createHash('sha256').update(bytes).digest('hex');
      const cache = path.join(directory, `image-${hash}.json`);
      if (fs.existsSync(cache)) run.referenceUrl = JSON.parse(fs.readFileSync(cache, 'utf8')).url;
      else {
        const file = new File([bytes], `${heroId}.png`, { type: 'image/png' });
        run.referenceUrl = upload ? await upload(file) : await createFalClient({ credentials: getConfig().h3Key, retry: { maxRetries: 0 } }).storage.upload(file);
        fs.writeFileSync(cache, JSON.stringify({ url: run.referenceUrl }), { mode: 0o600 });
      }
      run.status = 'prepared'; run.expiresAt = now() + 60_000; save(run); return prepared(run);
    } catch { run.status = 'failed'; run.endedAt = now(); save(run); throw new AppError(502, 'H3_UPLOAD', 'Не удалось передать изображение в fal. Live-сессия ещё не запускалась.'); }
  }
  function prepared(run) { return { runId: run.id, endpoint: H3_ENDPOINT, referenceUrl: run.referenceUrl, maxSeconds: 60 }; }
  async function proxy(id, target, data) {
    const run = get(id);
    const allowed = new Set(['https://wma.fal.run/ice', 'https://wma.fal.run/session', 'https://wma.fal.run/session/heartbeat', `https://fal.run/${H3_ENDPOINT}/ice`]);
    if (!allowed.has(target)) throw new AppError(403, 'H3_TARGET', 'Этот адрес не разрешён.');
    const heartbeat = target.endsWith('/session/heartbeat');
    const session = target.endsWith('/session');
    if (heartbeat && (data.session_id !== run.providerSessionId || !run.providerSessionId)) throw new AppError(403, 'H3_SESSION', 'Неверная live-сессия.');
    if (run.endedAt || run.expiresAt <= now()) {
      if (heartbeat) return { status: 200, data: { alive: false } };
      throw new AppError(410, 'H3_EXPIRED', 'Время live-сессии истекло.');
    }
    if (session) {
      if (pending.has(id)) return pending.get(id);
      if (run.sessionAttemptAt) throw new AppError(409, 'H3_DUPLICATE', 'Сессия уже запускалась. Повторный платный запуск заблокирован.');
      if (typeof data.sdp !== 'string' || data.type !== 'offer') throw new AppError(400, 'H3_SDP', 'Неверное подключение.');
      run.sessionAttemptAt = now(); run.expiresAt = now() + 60_000; run.status = 'unknown'; save(run);
    }
    const operation = (async () => {
      try {
        const input = heartbeat ? { session_id: run.providerSessionId } : { ...data, app_id: H3_ENDPOINT };
        const response = await fetchImpl(target, { method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: `Key ${getConfig().h3Key}` }, body: JSON.stringify(input), signal: AbortSignal.timeout(session ? 30_000 : 10_000) });
        const result = await response.json();
        if (heartbeat && result.alive === false) event(id, { type: 'provider_ended', reason: 'bridge_alive_false' });
        if (session) {
          run.providerStatus = response.status;
          if (response.ok && result.session_id) { run.providerSessionId = result.session_id; run.status = 'connected'; }
          else if (response.status >= 400 && response.status < 500) { run.status = 'failed'; run.endedAt = now(); }
          save(run);
        }
        return { status: response.status, data: result };
      } catch { throw new AppError(502, 'H3_BRIDGE', 'H3 не ответил на подключение. Автоповтор отключён.'); }
    })();
    if (session) pending.set(id, operation);
    return operation;
  }
  function event(id, input) {
    const run = get(id);
    if (run.events.length >= 250) return { ok: true };
    const fields = ['type', 'prompt_version', 'chunk_index', 'generation_seconds', 'playback_seconds', 'buffer_depth_seconds', 'elapsedMs', 'code', 'error', 'reason', 'state', 'pricing_status', 'max_session_seconds', 'session_wall_ms', 'final'];
    const event = { at: now() };
    for (const key of fields) if (typeof input[key] === 'number' || typeof input[key] === 'string') event[key] = typeof input[key] === 'string' ? input[key].slice(0, 1200) : input[key];
    run.events.push(event); save(run); return { ok: true };
  }
  function end(id) { const run = get(id); run.endedAt = now(); if (run.status !== 'unknown') run.status = 'closed'; save(run); return { ok: true }; }
  return { prepare, proxy, event, end };
}
