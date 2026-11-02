import { createTransparentVideo } from './transparent-video.js';
const $ = id => document.getElementById(id);
const qa = new URLSearchParams(location.search).get('qa') === '1';
const turbo = location.pathname === '/turbo';
const tennis = location.pathname === '/tennis' || turbo;
const provider = turbo ? 'H3 Max Turbo' : 'Wan';
let startedAt, readyAt, replyReceivedMs, visibleThroughout = true, frameCallback;
document.addEventListener('visibilitychange', () => { if (startedAt != null) visibleThroughout &&= !document.hidden; });
const video = $('heroVideo');
const transparentVideo = turbo ? createTransparentVideo(video, $('heroImage')) : null;
const active = new Set(['submitting', 'queued', 'generating']);
const storageKey = turbo ? 'mascot-turbo-last-turn' : 'mascot-wan-last-turn';
let config, hero, sessionId, busy = false, auto = true, currentJob, generation = 0, lastTurn, pollTimer;
const welcomeMarkup = $('messages').innerHTML;
async function api(endpoint, data) {
  const response = await fetch(`/api/${endpoint}`, data === undefined ? { cache: 'no-store' } : {
    method: 'POST', headers: { 'Content-Type': 'application/json', 'X-Mascot-Client': config.clientToken }, body: JSON.stringify(data),
  });
  const value = await response.json();
  if (!response.ok) { const error = new Error(value.error || 'Ошибка запроса'); error.code = value.code; throw error; }
  if (turbo && endpoint === 'config') value.videoReady = value.turboReady;
  return value;
}
function badge(text, state = '') { $('videoBadge').className = `status ${state}`; $('videoBadge').lastElementChild.textContent = text; }
function error(text) { $('chatError').textContent = text; $('chatError').hidden = !text; }
function controls() {
  const waiting = active.has(currentJob?.status);
  $('sendButton').disabled = busy || waiting || !config?.chatReady || !$('messageInput').value.trim();
  $('messageInput').disabled = !config?.chatReady;
  $('heroSelect').disabled = busy || waiting; $('newChat').disabled = busy || waiting; $('liveButton').disabled = busy || waiting;
  $('liveButtonText').textContent = auto ? 'Авто-анимация включена' : 'Авто-анимация выключена';
  $('liveButton').setAttribute('aria-pressed', String(auto));
  $('liveButton').querySelector('.play-icon').textContent = auto ? '✓' : '▷';
  $('counter').textContent = `${$('messageInput').value.length} / 1500`;
}
function save() { if (!qa && lastTurn) try { localStorage.setItem(storageKey, JSON.stringify(lastTurn)); } catch {} }
function renderHero(id) {
  transparentVideo?.reset();
  hero = config.heroes.find(item => item.id === id) || config.heroes[0];
  $('heroSelect').value = hero.id; $('heroName').textContent = hero.name; $('heroSubtitle').textContent = hero.subtitle;
  $('heroImage').src = hero.image; $('heroImage').alt = hero.name;
  $('messageInput').placeholder = 'Спроси ИИ — маскот дополнит ответ…';
  video.pause(); video.removeAttribute('src'); video.load(); video.hidden = true; $('heroImage').hidden = false;
  badge('Готова слушать');
  $('videoStatus').textContent = config.videoReady ? 'Отправь сообщение — реакция создастся и включится автоматически.' : `Для автоматических реакций подключаем ключ ${provider}. Текстовый чат уже работает.`;
  if (!qa) try { localStorage.setItem('mascot-live-hero', hero.id); } catch {}
}
function message(role, text, pending = false) {
  $('welcome')?.remove();
  const node = document.createElement('article'); node.className = `message ${role}${pending ? ' pending' : ''}`;
  const label = document.createElement('div'); label.className = 'message-label'; label.textContent = role === 'user' ? 'Ты' : 'ИИ-помощник';
  const body = document.createElement('div'); body.className = 'message-body'; body.textContent = text;
  node.append(label, body); $('messages').append(node); $('messages').scrollTop = $('messages').scrollHeight;
  return node;
}
function bindSuggestions() {
  document.querySelectorAll('[data-suggestion]').forEach(button => button.onclick = () => { $('messageInput').value = button.dataset.suggestion; controls(); $('messageInput').focus(); });
}
function showJob(job, ticket = generation) {
  if (ticket !== generation) return;
  currentJob = job;
  if (lastTurn) { lastTurn.animation = job; save(); }
  controls(); $('timer').hidden = !active.has(job?.status);
  if (!job) { badge('Фото'); $('videoStatus').textContent = 'Авто-анимация выключена.'; return; }
  $('videoStatus').textContent = job.message;
  if (active.has(job.status)) {
    badge(job.status === 'generating' ? 'Создаёт реакцию' : 'Готовит реакцию', 'connecting');
    clearTimeout(pollTimer);
    pollTimer = setTimeout(async () => {
      try { showJob(await api(`${turbo ? 'turbo/' : ''}animation/${job.id}`), ticket); }
      catch { if (ticket === generation) { $('videoStatus').textContent = 'Связь прервалась. Проверяем ту же генерацию…'; pollTimer = setTimeout(() => showJob(job, ticket), 4000); } }
    }, turbo ? 0 : 2000);
  } else if (job.status === 'ready') {
    badge(`Сгенерировано · ${provider}`);
    const seconds = ((job.readyAt - job.createdAt) / 1000).toFixed(1);
    $('videoStatus').textContent = turbo ? `Запрос к fal: ${seconds} с · Ответ ИИ: ${(job.chatMs / 1000).toFixed(2)} с · Повтор бесплатный.` : `Реакция готова за ${seconds} с. Показ — до 8 с. Повтор бесплатный.`;
    if (turbo) {
      $('latencyResult').textContent = job.measurement ? measurementText(job.measurement) : 'Ролик получен — ждём первый показанный кадр…';
      if (startedAt != null && !job.measurement) {
        readyAt = performance.now();
        video.cancelVideoFrameCallback?.(frameCallback);
        const measureFirstFrame = async () => {
          if (ticket !== generation || currentJob?.id !== job.id) return;
          const firstFrameMs = performance.now() - startedAt;
          const measurement = { firstFrameMs, replyReceivedMs, downloadDecodeMs: performance.now() - readyAt, visibleThroughout: visibleThroughout && !document.hidden };
          startedAt = null; job.measurement = measurement;
          $('latencyResult').textContent = measurementText(measurement);
          $('latencyResult').dataset.firstFrameMs = String(firstFrameMs);
          if (lastTurn) { lastTurn.animation = job; save(); }
          if (!qa) try { const measuredJob = await api('turbo/measurement', { id: job.id, measurement }); if (ticket === generation && lastTurn?.animation?.id === job.id) { currentJob = measuredJob; lastTurn.animation = measuredJob; save(); } } catch {}
        };
        if (transparentVideo) transparentVideo.oncePainted(measureFirstFrame);
        else frameCallback = video.requestVideoFrameCallback(measureFirstFrame);
      }
    }
    video.src = job.video; video.hidden = false;
    transparentVideo?.start();
    video.onloadeddata = () => { if (!transparentVideo && ticket === generation && currentJob?.id === job.id) $('heroImage').hidden = true; };
    video.play().catch(() => { if (ticket === generation) $('videoStatus').textContent = 'Реакция готова. Нажми ▶ на видео, чтобы посмотреть.'; });
  } else badge(job.status === 'unavailable' ? `Нужен ключ ${provider}` : 'Без новой анимации');
}
video.controls = !turbo; video.loop = turbo; video.muted = true;
video.setAttribute('aria-label', 'Автоматическая видеореакция маскота');
video.addEventListener('timeupdate', () => { if (!turbo && video.currentTime >= 8) video.pause(); });
video.addEventListener('error', () => { if (currentJob?.status === 'ready') $('videoStatus').textContent = 'Ролик создан, но браузер не смог его воспроизвести. Повторной генерации нет.'; });
$('chatForm').onsubmit = async event => {
  event.preventDefault();
  const text = $('messageInput').value.trim();
  if (!text || busy || active.has(currentJob?.status)) return;
  startedAt = performance.now(); visibleThroughout = !document.hidden; video.cancelVideoFrameCallback?.(frameCallback);
  transparentVideo?.reset();
  if (turbo) $('latencyResult').textContent = 'Замер от нажатия «Отправить»…';
  busy = true; error(''); controls();
  const ticket = ++generation; clearTimeout(pollTimer);
  message('user', text); const pending = message('assistant', 'Готовит ответ…', true);
  $('messageInput').value = ''; $('chatStatus').textContent = 'ИИ отвечает, маскот готовится отреагировать';
  badge('Читает сообщение', 'connecting'); video.pause(); video.hidden = true; $('heroImage').hidden = false;
  $('videoStatus').textContent = auto ? 'ИИ придумывает ответ и индивидуальную реакцию…' : 'ИИ готовит ответ…';
  try {
    let result;
    if (qa) result = { reply: 'Проверка интерфейса: платные модели отключены.', animation: { status: 'unavailable', message: 'QA: генерация отключена.' } };
    else {
      config = await api('config');
      if (!sessionId) sessionId = (await api('session', { heroId: hero.id })).id;
      const input = { sessionId, message: text, requestId: crypto.randomUUID(), animate: auto, videoProvider: turbo ? 'turbo' : 'wan' };
      try { result = await api('chat', input); }
      catch (err) {
        if (err.code !== 'SESSION') throw err;
        sessionId = (await api('session', { heroId: hero.id })).id;
        result = await api('chat', { ...input, sessionId });
      }
    }
    if (ticket !== generation) return;
    replyReceivedMs = performance.now() - startedAt;
    pending.remove(); message('assistant', result.reply);
    lastTurn = { heroId: hero.id, sessionId, question: text, reply: result.reply, animation: result.animation };
    save(); showJob(result.animation, ticket);
  } catch (err) {
    pending.remove(); error(err.code ? err.message : 'Связь прервалась. Автоматического повтора запроса нет.'); badge('Ответ не получен'); $('videoStatus').textContent = 'Автоматического повтора нет.';
  } finally { busy = false; $('chatStatus').textContent = 'ИИ отвечает · маскот дополняет ответ без слов'; controls(); }
};
$('messageInput').oninput = () => { $('messageInput').style.height = 'auto'; $('messageInput').style.height = `${Math.min(120, $('messageInput').scrollHeight)}px`; controls(); };
$('messageInput').onkeydown = event => { if (event.key === 'Enter' && !event.shiftKey && !event.isComposing) { event.preventDefault(); $('chatForm').requestSubmit(); } };
$('liveButton').onclick = () => { auto = !auto; controls(); $('videoStatus').textContent = auto ? `Следующее сообщение автоматически запустит одну реакцию ${provider}.` : 'Следующее сообщение получит только текстовый ответ.'; };
function reset() {
  generation++; startedAt = null; video.cancelVideoFrameCallback?.(frameCallback); clearTimeout(pollTimer); currentJob = null; lastTurn = null; sessionId = null;
  if (!qa) try { localStorage.removeItem(storageKey); } catch {}
  $('messages').innerHTML = welcomeMarkup; bindSuggestions(); renderHero($('heroSelect').value); error(''); controls();
}
$('heroSelect').onchange = reset; $('newChat').onclick = reset;
$('settingsButton').onclick = () => {
  $('chatKeyStatus').textContent = config.chatReady ? 'Подключён' : 'Нужен OpenRouter';
  $('videoKeyStatus').textContent = config.videoReady ? `${provider} подключён` : `Нужен ключ ${provider}`; $('settingsDialog').showModal();
};
$('closeSettings').onclick = () => $('settingsDialog').close();
setInterval(() => { if (active.has(currentJob?.status)) $('timer').textContent = `${Math.floor((Date.now() - currentJob.createdAt) / 1000)} с ожидания`; }, 500);
try {
  config = await api('config');
  if (turbo) {
    document.title = 'Маскот · H3 Max Turbo'; document.querySelector('.brand-live').textContent = 'Turbo'; document.querySelector('.brand').href = '/turbo';
    $('inputHint').textContent = '5 с видео по кругу · 480p · ≈ $0,08 · Enter — отправить';
    document.querySelector('#settingsDialog > p').textContent = 'После сообщения ИИ создаёт ответ и индивидуальную реакцию. H3 Max Turbo анимирует нашу картинку; результат автоматически появляется рядом с чатом.';
    document.querySelector('#settingsDialog .small').textContent = '5 секунд, 480p, без расширения промпта. Ориентир $0,08125 до 31 октября; базовый тариф $0,125. Замер включает путь от отправки сообщения до первого кадра на экране. Повтор видео не вызывает генерацию.';
    const metric = document.createElement('p'); metric.id = 'latencyResult'; metric.className = 'video-note'; metric.textContent = 'Цель: первый кадр не позднее 5 секунд после сообщения.'; $('videoStatus').after(metric);
  }
  if (qa) { config.chatReady = true; document.body.classList.add('qa-mode'); const banner = document.createElement('div'); banner.className = 'qa-banner'; banner.textContent = 'Проверка интерфейса · AI-запросы отключены'; document.body.prepend(banner); }
  for (const item of config.heroes) { const option = document.createElement('option'); option.value = item.id; option.textContent = item.name; $('heroSelect').append(option); }
  let selected = 'una';
  if (!qa) try { selected = localStorage.getItem('mascot-live-hero') || selected; lastTurn = JSON.parse(localStorage.getItem(storageKey) || 'null'); } catch {}
  renderHero(lastTurn?.heroId || selected); bindSuggestions();
  if (lastTurn) { sessionId = lastTurn.sessionId; message('user', lastTurn.question); message('assistant', lastTurn.reply); showJob(lastTurn.animation); }
  else if (tennis) $('messageInput').value = 'Я новичок в теннисе. С чего начать, чтобы научиться уверенно подавать?';
  controls();
} catch { error('Не удалось загрузить приложение. Проверь локальный сервер.'); }

function measurementText(m) { return `Первый кадр после сообщения: ${(m.firstFrameMs / 1000).toFixed(2)} с · ${m.visibleThroughout ? (m.firstFrameMs <= 5000 ? 'В пределах 5 секунд' : 'Дольше 5 секунд') : 'Превью было скрыто — замер невалиден'}`; }
