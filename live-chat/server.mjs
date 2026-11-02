import http from 'node:http';
import path from 'node:path';
import fs from 'node:fs';
import { fileURLToPath } from 'node:url';
import { parseEnv } from 'node:util';
import { randomUUID, createHash } from 'node:crypto';
import { Odyssey, credentialsToDict } from '@odysseyml/odyssey';
import { AppError, createChatService } from './chat.mjs';
import { heroes, initialPrompt } from './heroes.mjs';
import { createWanService } from './wan.mjs';
import { createH3Service } from './h3.mjs';
import { createTurboService } from './turbo.mjs';

const root = path.dirname(fileURLToPath(import.meta.url));
const project = path.dirname(root);
const localEnv = path.join(root, '.env.local');
const readEnv = file => fs.existsSync(file) ? parseEnv(fs.readFileSync(file, 'utf8')) : {};
function getConfig() {
  const env = { ...readEnv(path.join(project, '.env')), ...readEnv(localEnv), ...process.env };
  return { openrouterKey: env.OPENROUTER_API_KEY || '', odysseyKey: env.ODYSSEY_API_KEY || '', wanKey: env.FAL_WAN_KEY || '', h3Key: env.FAL_H3_KEY || '', turboKey: env.FAL_TURBO_KEY || '',
    chatModel: env.MASCOT_LIVE_CHAT_MODEL || 'openai/gpt-4.1-mini', port: Number(env.MASCOT_LIVE_PORT || 8810) };
}
const port = getConfig().port;
if (!Number.isInteger(port) || port < 1024 || port > 65535) throw new Error('Invalid MASCOT_LIVE_PORT');
const clientToken = randomUUID();
const chat = createChatService({ getConfig });
const wan = createWanService({ directory: path.join(root, 'output/wan-jobs'), getConfig,
  getImage: heroId => {
    const hero = heroes.find(item => item.id === heroId);
    return fs.readFileSync(path.join(project, 'android/app/src/main/assets/bundled-heroes', hero.asset, 'base.png'));
  } });
const h3 = createH3Service({ directory: path.join(root, 'output/h3-runs'), getConfig, getImage: heroId => {
  const hero = heroes.find(item => item.id === heroId);
  return fs.readFileSync(path.join(project, 'android/app/src/main/assets/bundled-heroes', hero.asset, 'base.png'));
} });
const turbo = createTurboService({ directory: path.join(root, 'output/turbo-jobs'), getConfig, getImageUrl: heroId => {
  const hero = heroes.find(item => item.id === heroId);
  const bytes = fs.readFileSync(path.join(project, 'android/app/src/main/assets/bundled-heroes', hero.asset, 'base.png'));
  const cached = path.join(root, 'output/h3-runs', `image-${createHash('sha256').update(bytes).digest('hex')}.json`);
  return fs.existsSync(cached) ? JSON.parse(fs.readFileSync(cached, 'utf8')).url : `data:image/png;base64,${bytes.toString('base64')}`;
} });
const videoRequests = new Map();
let videoLease = null;
const allowedHosts = new Set([`127.0.0.1:${port}`, `localhost:${port}`]);
const send = (res, status, value) => {
  res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff' });
  res.end(JSON.stringify(value));
};
async function body(req, limit = 16_384) {
  let size = 0; const chunks = [];
  for await (const chunk of req) {
    size += chunk.length;
    if (size > limit) throw new AppError(413, 'BODY', 'Слишком большой запрос.');
    chunks.push(chunk);
  }
  try {
    const data = JSON.parse(Buffer.concat(chunks).toString('utf8'));
    if (!data || typeof data !== 'object' || Array.isArray(data)) throw new Error();
    return data;
  } catch { throw new AppError(400, 'JSON', 'Не удалось прочитать запрос.'); }
}

async function credentials({ sessionId, requestId }) {
  chat.get(sessionId);
  if (typeof requestId !== 'string' || !/^[\w-]{8,80}$/.test(requestId)) throw new AppError(400, 'REQUEST_ID', 'Неверный запрос подключения.');
  const cacheKey = `${sessionId}:${requestId}`;
  if (videoRequests.has(cacheKey)) return videoRequests.get(cacheKey);
  const key = getConfig().odysseyKey;
  if (!key) throw new AppError(503, 'VIDEO_KEY', 'Для live-видео нужен ключ Odyssey API. Добавь его в настройках.');
  if (videoLease && videoLease.until > Date.now()) throw new AppError(409, 'VIDEO_BUSY', 'Уже открывается или работает один видеопоток. Сначала останови его.');
  if (videoRequests.size >= 40) throw new AppError(429, 'VIDEO_LIMIT', 'Лимит запусков MVP достигнут. Перезапусти сервер, чтобы продолжить.');
  videoLease = { sessionId, requestId, until: Date.now() + 960_000 };
  const pending = (async () => {
    try {
      const client = new Odyssey({ apiKey: key });
      const value = await client.createClientCredentials();
      return { credentials: credentialsToDict(value), maxSeconds: 120 };
    } catch {
      // Unknown provision outcome: keep the lease instead of spawning a second paid session.
      throw new AppError(502, 'VIDEO_PROVIDER', 'Odyssey не открыл сессию. Проверь ключ, доступ и баланс. Повторный запуск заблокирован на 16 минут, чтобы не создать вторую сессию при потерянном ответе.');
    }
  })();
  videoRequests.set(cacheKey, pending);
  return pending;
}

const server = http.createServer(async (req, res) => {
  try {
    if (!allowedHosts.has(req.headers.host)) return send(res, 403, { error: 'Локальный доступ.', code: 'HOST' });
    const url = new URL(req.url, `http://${req.headers.host}`);
    if (req.method === 'POST') {
      if (req.headers.origin !== `http://${req.headers.host}` || req.headers['x-mascot-client'] !== clientToken || !req.headers['content-type']?.startsWith('application/json'))
        return send(res, 403, { error: 'Открой приложение в локальном превью.', code: 'ORIGIN' });
      const data = await body(req, url.pathname === '/api/h3/proxy' ? 131_072 : 16_384);
      if (url.pathname === '/api/h3/prepare') return send(res, 200, await h3.prepare({ ...data, heroId: chat.get(data.sessionId).hero.id }));
      if (url.pathname === '/api/h3/proxy') {
        const result = await h3.proxy(req.headers['x-mascot-h3-run'], req.headers['x-fal-target-url'], data);
        return send(res, result.status, result.data);
      }
      if (url.pathname === '/api/h3/event') return send(res, 200, h3.event(data.runId, data.event || {}));
      if (url.pathname === '/api/h3/end') return send(res, 200, h3.end(data.runId));
      if (url.pathname === '/api/session') return send(res, 200, chat.create(data.heroId));
      if (url.pathname === '/api/turbo/measurement') return send(res, 200, turbo.measure(data.id, data.measurement));
      if (url.pathname === '/api/chat') {
        const chatStartedAt = Date.now();
        const result = await chat.send(data);
        const animation = data.animate === false || data.videoProvider === 'h3' ? null : (data.videoProvider === 'turbo' ? turbo : wan).start({ chatMs: Date.now() - chatStartedAt, sessionId: data.sessionId, requestId: data.requestId,
          heroId: chat.get(data.sessionId).hero.id, prompt: result.videoPrompt });
        return send(res, 200, { ...result, animation });
      }
      if (url.pathname === '/api/video') return send(res, 200, await credentials(data));
      if (url.pathname === '/api/video/release') {
        if (videoLease?.sessionId === data.sessionId && videoLease?.requestId === data.requestId) videoLease = null;
        return send(res, 200, { ok: true });
      }
      if (url.pathname === '/api/settings') {
        const key = data.odysseyKey;
        if (typeof key !== 'string' || key.length < 16 || key.length > 500 || /\s|["'`]/.test(key)) throw new AppError(400, 'KEY', 'Проверь формат ключа. Пробелы и кавычки не нужны.');
        const current = fs.existsSync(localEnv) ? fs.readFileSync(localEnv, 'utf8') : '';
        const lines = current.split('\n').filter(line => !/^\s*(export\s+)?ODYSSEY_API_KEY\s*=/.test(line));
        fs.writeFileSync(`${localEnv}.tmp`, `${lines.join('\n').trim()}\nODYSSEY_API_KEY=${key}\n`, { mode: 0o600 });
        fs.renameSync(`${localEnv}.tmp`, localEnv);
        return send(res, 200, { ok: true });
      }
      if (url.pathname === '/api/qa') {
        const { runServerChecks } = await import('./qa-server.mjs');
        return send(res, 200, await runServerChecks());
      }
      return send(res, 404, { error: 'Не найдено.' });
    }
    if (req.method !== 'GET') return send(res, 405, { error: 'Метод не поддерживается.' });
    if (/^\/api\/animation\/[a-f0-9]{32}$/.test(url.pathname)) return send(res, 200, await wan.refresh(url.pathname.split('/').pop()));
    if (/^\/api\/turbo\/animation\/[a-f0-9]{32}$/.test(url.pathname)) return send(res, 200, await turbo.wait(url.pathname.split('/').pop()));
    if (url.pathname === '/tennis' || url.pathname === '/h3' || url.pathname === '/turbo') {
      const html = fs.readFileSync(path.join(root, 'public/index.html'), 'utf8');
      res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': 'no-store' });
      return res.end(url.pathname === '/h3' ? html.replace('src="/app.js"', 'src="/h3-app.js"') : html);
    }
    if (url.pathname === '/api/h3/last-trial') {
      const file = path.join(root, 'output/h3-trial.json');
      return send(res, 200, fs.existsSync(file) ? JSON.parse(fs.readFileSync(file, 'utf8')) : null);
    }
    if (url.pathname === '/api/config') {
      const config = getConfig();
      return send(res, 200, { clientToken, chatReady: !!config.openrouterKey, videoReady: !!config.wanKey, h3Ready: !!config.h3Key, turboReady: !!config.turboKey,
        maxSeconds: 8, provider: 'Wan 2.2 Turbo', estimatedCostUsd: 0.05,
        heroes: heroes.map(({ id, name, subtitle }) => ({ id, name, subtitle, image: `/heroes/${id}.png`, prompt: initialPrompt(heroes.find(h => h.id === id)) })) });
    }
    const staticFiles = new Map([
      ['/', ['public/index.html', 'text/html; charset=utf-8']],
      ['/style.css', ['public/style.css', 'text/css; charset=utf-8']],
      ['/app.js', ['dist/app.js', 'text/javascript; charset=utf-8']],
      ['/h3-app.js', ['dist/h3-app.js', 'text/javascript; charset=utf-8']],
      ['/qa', ['public/qa.html', 'text/html; charset=utf-8']],
      ['/qa.js', ['dist/qa.js', 'text/javascript; charset=utf-8']],
    ]);
    let file, type;
    if (staticFiles.has(url.pathname)) { const entry = staticFiles.get(url.pathname); file = path.join(root, entry[0]); type = entry[1]; }
    const hero = heroes.find(item => url.pathname === `/heroes/${item.id}.png`);
    if (hero) { file = path.join(project, 'android/app/src/main/assets/bundled-heroes', hero.asset, 'base.png'); type = 'image/png'; }
    if (url.pathname === '/font.ttf') { file = path.join(project, 'android/app/src/main/res/font/sb_sans_display_medium.ttf'); type = 'font/ttf'; }
    if (url.pathname === '/display.ttf') { file = path.join(project, 'android/app/src/main/res/font/rubik_bubbles.ttf'); type = 'font/ttf'; }
    if (!file || !fs.existsSync(file)) return send(res, 404, { error: 'Не найдено.' });
    res.writeHead(200, { 'Content-Type': type, 'Content-Length': fs.statSync(file).size,
      'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff', 'Referrer-Policy': 'no-referrer',
      'Permissions-Policy': 'camera=(), microphone=()' });
    fs.createReadStream(file).pipe(res);
  } catch (error) {
    send(res, error.status || 500, { error: error instanceof AppError ? error.message : 'Ошибка локального сервера.', code: error.code || 'INTERNAL' });
  }
});
server.listen(port, '127.0.0.1', () => console.log(`Mascot Live: http://127.0.0.1:${port}`));
