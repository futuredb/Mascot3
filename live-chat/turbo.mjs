import fs from 'node:fs';
import path from 'node:path';
import { createHash } from 'node:crypto';
import { AppError } from './chat.mjs';

export const TURBO_ENDPOINT = 'minimax/h3-max-turbo/image-to-video';
export function createTurboService({ directory, getConfig, getImageUrl, fetchImpl = fetch, now = Date.now }) {
  fs.mkdirSync(directory, { recursive: true });
  const jobs = new Map(), waiting = new Map();
  for (const file of fs.readdirSync(directory).filter(x => /^[a-f0-9]{32}\.json$/.test(x))) {
    const job = JSON.parse(fs.readFileSync(path.join(directory, file), 'utf8'));
    if (job.status === 'generating') { job.status = 'unknown'; job.message = 'Сервер перезапущен во время генерации. Повтор заблокирован.'; }
    jobs.set(job.id, job);
  }
  const expose = job => Object.fromEntries(['id', 'heroId', 'status', 'message', 'createdAt', 'readyAt', 'video', 'estimatedCostUsd', 'maxPlaybackSeconds', 'falMs', 'inferenceSeconds', 'chatMs', 'measurement'].filter(k => job[k] !== undefined).map(k => [k, job[k]]));
  const get = id => { const job = jobs.get(id); if (!job) throw new AppError(404, 'ANIMATION', 'Реакция не найдена.'); return job; };
  const save = job => {
    const file = path.join(directory, `${job.id}.json`);
    fs.writeFileSync(`${file}.tmp`, JSON.stringify(job, null, 2), { mode: 0o600 }); fs.renameSync(`${file}.tmp`, file);
    for (const resolve of waiting.get(job.id) || []) resolve(expose(job));
    waiting.delete(job.id);
  };
  async function submit(job, prompt) {
    try {
      const loopPrompt = `${prompt} Create a seamless looping animation: begin in the reference image's neutral pose, perform the reaction gently, then return to the exact starting pose, facial expression, position and framing by the final frame. Match the first and last frames so the motion repeats continuously without a jump. No cuts.`;
      const input = { prompt: loopPrompt, image_url: getImageUrl(job.heroId), duration: 5, resolution: '480P', prompt_expansion_mode: 'disabled', enable_safety_checker: true };
      job.providerStartedAt = now(); save(job);
      // One synchronous inference request: no polling delay and no SDK POST retries.
      const response = await fetchImpl(`https://fal.run/${TURBO_ENDPOINT}`, { method: 'POST', redirect: 'error', signal: AbortSignal.timeout(90_000),
        headers: { Authorization: `Key ${getConfig().turboKey}`, 'Content-Type': 'application/json' }, body: JSON.stringify(input) });
      job.providerRequestId = response.headers?.get('x-fal-request-id') || undefined;
      job.providerStatus = response.status;
      if (!response.ok) {
        job.status = response.status >= 500 ? 'unknown' : 'error';
        job.message = [401, 403].includes(response.status) ? 'Ключ fal не разрешает H3 Max Turbo. Нужен доступ к этой модели.' : response.status === 402 ? 'На fal недостаточно средств.' : `Turbo ответил ошибкой ${response.status}. Автоповтора нет.`;
      } else {
        const data = await response.json(); const video = new URL(data.video?.url);
        if (video.protocol !== 'https:' || video.username || video.password) throw new Error('Invalid video');
        job.video = video.href; job.status = 'ready'; job.readyAt = now(); job.falMs = job.readyAt - job.providerStartedAt;
        if (Number.isFinite(data.timings?.inference)) job.inferenceSeconds = data.timings.inference;
        job.message = 'Видео готово. Загружаем первый кадр…';
      }
    } catch { job.status = 'unknown'; job.message = 'Исход запроса к Turbo неизвестен. Повторная генерация заблокирована.'; }
    save(job);
  }
  function start({ sessionId, requestId, heroId, prompt, chatMs }) {
    const id = createHash('sha256').update(`${sessionId}:${requestId}`).digest('hex').slice(0, 32);
    if (jobs.has(id)) return expose(get(id));
    if (!getConfig().turboKey) return { status: 'unavailable', message: 'Интеграция готова. Нужен ключ с доступом к H3 Max Turbo.' };
    if ([...jobs.values()].some(j => ['generating', 'unknown'].includes(j.status))) return { status: 'busy', message: 'Предыдущий запрос ещё не завершён. Повторного списания нет.' };
    const job = { id, heroId, status: 'generating', message: 'Turbo создаёт реакцию…', createdAt: now(), chatMs, estimatedCostUsd: 0.08125, maxPlaybackSeconds: 8 };
    jobs.set(id, job); save(job); void submit(job, prompt); return expose(job);
  }
  async function wait(id) {
    const job = get(id); if (job.status !== 'generating') return expose(job);
    return new Promise(resolve => {
      const finish = value => { clearTimeout(timer); waiting.get(id)?.delete(finish); resolve(value); };
      const timer = setTimeout(() => finish(expose(get(id))), 8000);
      if (!waiting.has(id)) waiting.set(id, new Set()); waiting.get(id).add(finish);
    });
  }
  function measure(id, value) {
    const job = get(id);
    if (job.status !== 'ready') throw new AppError(409, 'NOT_READY', 'Видео ещё не готово.');
    if (!job.measurement) {
      const measured = {};
      for (const key of ['firstFrameMs', 'replyReceivedMs', 'downloadDecodeMs']) {
        if (!Number.isFinite(value[key]) || value[key] < 0 || value[key] > 600_000) throw new AppError(400, 'MEASUREMENT', 'Неверный замер.');
        measured[key] = Math.round(value[key]);
      }
      measured.visibleThroughout = value.visibleThroughout === true;
      measured.measuredAt = now(); measured.withinFiveSeconds = measured.firstFrameMs <= 5000 && measured.visibleThroughout;
      job.measurement = measured; save(job);
    }
    return expose(job);
  }
  return { start, wait, measure, get: id => expose(get(id)) };
}
