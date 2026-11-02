import fs from 'node:fs';
import path from 'node:path';
import { createHash } from 'node:crypto';
import { AppError } from './chat.mjs';

export const WAN_ENDPOINT = 'fal-ai/wan/v2.2-a14b/image-to-video/turbo';
const active = new Set(['submitting', 'queued', 'generating', 'unknown']);
export function createWanService({ directory, getConfig, getImage, fetchImpl = fetch, now = Date.now }) {
  fs.mkdirSync(directory, { recursive: true });
  const jobs = new Map();
  const polling = new Map();
  for (const name of fs.readdirSync(directory).filter(name => /^[a-f0-9]{32}\.json$/.test(name))) {
    const job = JSON.parse(fs.readFileSync(path.join(directory, name), 'utf8'));
    if (job.status === 'submitting') { job.status = 'unknown'; job.message = 'Сервер перезапущен во время отправки. Повторная генерация заблокирована.'; }
    jobs.set(job.id, job);
  }
  const save = job => {
    jobs.set(job.id, job);
    const file = path.join(directory, `${job.id}.json`);
    fs.writeFileSync(`${file}.tmp`, JSON.stringify(job, null, 2));
    fs.renameSync(`${file}.tmp`, file);
  };
  const expose = job => Object.fromEntries(['id', 'heroId', 'status', 'message', 'createdAt', 'readyAt', 'video', 'queuePosition', 'estimatedCostUsd', 'maxPlaybackSeconds'].filter(k => job[k] !== undefined).map(k => [k, job[k]]));
  const get = id => {
    const job = jobs.get(id);
    if (!job) throw new AppError(404, 'ANIMATION', 'Реакция не найдена.');
    return job;
  };
  const queueUrl = value => {
    const url = new URL(value);
    if (url.origin !== 'https://queue.fal.run' || !url.pathname.startsWith('/fal-ai/wan/requests/') || url.username || url.password) throw new Error('Unexpected queue URL');
    return url.href;
  };
  async function submit(job, prompt) {
    try {
      const response = await fetchImpl(`https://queue.fal.run/${WAN_ENDPOINT}`, {
        method: 'POST', signal: AbortSignal.timeout(45_000), redirect: 'error',
        headers: { Authorization: `Key ${getConfig().wanKey}`, 'Content-Type': 'application/json' },
        body: JSON.stringify({ image_url: `data:image/png;base64,${getImage(job.heroId).toString('base64')}`, prompt,
          resolution: '480p', aspect_ratio: 'auto', acceleration: 'regular', enable_safety_checker: true,
          enable_output_safety_checker: true, enable_prompt_expansion: false, video_write_mode: 'fast' }),
      });
      if (!response.ok) {
        job.status = response.status >= 500 ? 'unknown' : 'error';
        job.message = [401, 403].includes(response.status) ? 'Ключ fal не разрешает Wan. Нужен ключ с доступом к этой модели.'
          : response.status === 402 ? 'На fal недостаточно средств.' : `fal отклонил запрос (${response.status}). Автоповтора нет.`;
      } else {
        const data = await response.json();
        job.statusUrl = queueUrl(data.status_url); job.resultUrl = queueUrl(data.response_url);
        job.providerRequestId = data.request_id; job.status = 'queued'; job.message = 'Реакция в очереди Wan…';
      }
    } catch {
      job.status = 'unknown'; job.message = 'Не удалось подтвердить отправку. Запрос мог дойти до fal; повторная отправка заблокирована.';
    }
    save(job);
  }
  function start({ sessionId, requestId, heroId, prompt }) {
    const id = createHash('sha256').update(`${sessionId}:${requestId}`).digest('hex').slice(0, 32);
    if (jobs.has(id)) return expose(jobs.get(id));
    if (!getConfig().wanKey) return { status: 'unavailable', message: 'Автоматическая реакция готова к подключению. Нужен ключ fal с доступом к Wan.' };
    if ([...jobs.values()].some(job => active.has(job.status))) return { status: 'busy', message: 'Предыдущая реакция ещё создаётся. Новую платную генерацию не запускали.' };
    if (jobs.size >= 20) return { status: 'limit', message: 'Лимит этой пробы — 20 анимаций (около $1). Новая генерация не запущена.' };
    const job = { id, heroId, status: 'submitting', message: 'Отправляем реакцию в Wan…', createdAt: now(), estimatedCostUsd: 0.05, maxPlaybackSeconds: 8 };
    save(job); // Durable reservation before any paid request.
    void submit(job, prompt);
    return expose(job);
  }
  async function refresh(id) {
    const job = get(id);
    if (!job.statusUrl || !['queued', 'generating'].includes(job.status)) return expose(job);
    if (polling.has(id)) return polling.get(id);
    const pending = (async () => {
      try {
        const options = { headers: { Authorization: `Key ${getConfig().wanKey}` }, signal: AbortSignal.timeout(15_000), redirect: 'error' };
        const response = await fetchImpl(job.statusUrl, options);
        if (!response.ok) throw new Error('Status unavailable');
        const data = await response.json();
        if (data.status === 'COMPLETED') {
          if (data.error) { job.status = 'error'; job.message = 'Wan завершил запрос с ошибкой. Автоматического повтора нет.'; }
          else {
            const result = await fetchImpl(job.resultUrl, { ...options, signal: AbortSignal.timeout(15_000) });
            if (!result.ok) {
              if (result.status >= 400 && result.status < 500) { job.status = 'error'; job.message = `Wan не вернул видео (${result.status}). Автоповтора нет.`; }
              else throw new Error('Result unavailable');
            } else {
              const value = await result.json();
              const video = new URL(value.video?.url);
              if (video.protocol !== 'https:' || video.username || video.password) throw new Error('Invalid media URL');
              job.video = video.href; job.status = 'ready'; job.readyAt = now(); job.message = 'Реакция готова';
            }
          }
        } else {
          job.status = data.status === 'IN_PROGRESS' ? 'generating' : 'queued';
          job.queuePosition = data.queue_position;
          job.message = job.status === 'generating' ? 'Wan создаёт реакцию…' : 'Реакция в очереди Wan…';
        }
      } catch { job.message = 'Проверяем готовность реакции. Новую генерацию не запускаем…'; }
      save(job);
      return expose(job);
    })().finally(() => polling.delete(id));
    polling.set(id, pending);
    return pending;
  }
  return { start, refresh, get: id => expose(get(id)) };
}
