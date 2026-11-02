import { H3Live } from './h3-live.js';
const $ = id => document.getElementById(id), video = $('heroVideo');
let config, hero, sessionId, busy = false, live, armed = true, starting = false, firstMessageAt, firstFrameMs;
let recorder, recordingUrl, frameCallback;
const qa = new URLSearchParams(location.search).get('qa') === '1';
async function api(endpoint, data, keepalive = false) {
  const response = await fetch(`/api/${endpoint}`, data === undefined ? { cache: 'no-store' } : { method: 'POST', keepalive,
    headers: { 'Content-Type': 'application/json', 'X-Mascot-Client': config.clientToken }, body: JSON.stringify(data) });
  const value = await response.json(); if (!response.ok) { const error = new Error(value.error || value.detail || 'Ошибка запроса'); error.code = value.code; throw error; } return value;
}
function badge(text, state = '') { $('videoBadge').className = `status ${state}`; $('videoBadge').lastElementChild.textContent = text; }
function note(text) { $('videoStatus').textContent = text; }
function error(text) { $('chatError').textContent = text; $('chatError').hidden = !text; }
function controls() {
  const active = live && !live.closed;
  $('sendButton').disabled = busy || starting || !config?.chatReady || !$('messageInput').value.trim();
  $('messageInput').disabled = !config?.chatReady;
  $('heroSelect').disabled = busy || active; $('newChat').disabled = busy || active;
  $('liveButtonText').textContent = active ? 'Остановить live' : armed ? 'Live включится после сообщения' : 'Включить следующую live-пробу';
  $('liveButton').setAttribute('aria-pressed', String(armed || active));
  $('liveButton').querySelector('.play-icon').textContent = active ? '■' : '▷';
  $('counter').textContent = `${$('messageInput').value.length} / 1500`;
}
function message(role, text, pending = false) {
  $('welcome')?.remove(); const node = document.createElement('article'); node.className = `message ${role}${pending ? ' pending' : ''}`;
  const label = document.createElement('div'); label.className = 'message-label'; label.textContent = role === 'user' ? 'Ты' : 'ИИ-помощник';
  const body = document.createElement('div'); body.className = 'message-body'; body.textContent = text; node.append(label, body); $('messages').append(node); $('messages').scrollTop = $('messages').scrollHeight; return node;
}
function renderHero(id) {
  hero = config.heroes.find(h => h.id === id) || config.heroes[0];
  $('heroSelect').value = hero.id; $('heroName').textContent = hero.name; $('heroSubtitle').textContent = hero.subtitle;
  $('heroImage').src = hero.image; $('heroImage').alt = hero.name; $('heroImage').hidden = false;
  video.pause(); video.srcObject = null; video.removeAttribute('src'); video.hidden = true;
  $('messageInput').placeholder = 'Спроси ИИ — маскот отреагирует в live…';
  badge('Готова слушать'); note('Первое сообщение запустит H3. Следующие меняют реакцию в том же потоке.');
}
function observeFrame() {
  if (!live || live.closed) return;
  frameCallback = video.requestVideoFrameCallback(() => {
    if (!live || live.closed) return;
    if (firstFrameMs == null) {
      firstFrameMs = Date.now() - firstMessageAt;
      $('heroImage').hidden = true; badge('LIVE · H3', 'live');
      $('h3Metrics').textContent = `Первый кадр: ${(firstFrameMs / 1000).toFixed(1)} с после сообщения`;
      note('Поток идёт. Отправь следующее сообщение — реакция изменится без нового подключения.');
      live.report({ type: 'first_frame', elapsedMs: firstFrameMs });
    }
    $('h3Metrics').dataset.frames = String(video.getVideoPlaybackQuality().totalVideoFrames);
    observeFrame();
  });
}
function attachStream(stream) {
  if (video.srcObject === stream) return;
  video.onloadeddata = null; video.removeAttribute('src'); video.srcObject = stream; video.hidden = false; video.controls = false; video.muted = true; video.play().catch(() => error('Браузер остановил воспроизведение. Нажми на видео.'));
  video.cancelVideoFrameCallback?.(frameCallback); observeFrame();
  if (!recorder && stream.getVideoTracks().length && typeof MediaRecorder !== 'undefined') {
    const chunks = []; const recordedStream = new MediaStream(stream.getVideoTracks());
    try {
      recorder = new MediaRecorder(recordedStream, { mimeType: 'video/webm;codecs=vp8' });
      recorder.ondataavailable = event => { if (event.data.size) chunks.push(event.data); };
      recorder.onstop = () => {
        if (!chunks.length) return;
        if (recordingUrl) URL.revokeObjectURL(recordingUrl);
        recordingUrl = URL.createObjectURL(new Blob(chunks, { type: 'video/webm' }));
        video.srcObject = null; video.src = recordingUrl; video.controls = true; video.loop = false;
        video.hidden = false; $('heroImage').hidden = true; badge('Запись live-пробы');
      }; recorder.start(1000);
    } catch {}
  }
}
function onEvent(event) {
  const seconds = event.elapsedMs != null ? (event.elapsedMs / 1000).toFixed(1) : null;
  if (event.type === 'configured') note('H3 подключён, ждём первый сгенерированный кадр…');
  if (event.type === 'prompt_pending' || event.type === 'prompt_sent') note('Новая реакция отправлена в текущий поток…');
  if (event.type === 'prompt_applied') note(`H3 принял реакцию №${event.prompt_version}. Ждём новый фрагмент.`);
  if (event.type === 'chunk') {
    $('h3Metrics').dataset.chunk = String(event.chunk_index); $('h3Metrics').dataset.version = String(event.prompt_version);
    if (event.prompt_version > 1) $('h3Metrics').textContent = `Первый кадр: ${(firstFrameMs / 1000).toFixed(1)} с · Фрагмент реакции №${event.prompt_version} готов за ${seconds} с`;
    note('H3 продолжает поток. Новые сообщения меняют следующую генерируемую реакцию.');
  }
  if (event.type === 'prompt_rejected') error(`H3 отклонил новую реакцию: ${event.reason}.`);
  if (event.type === 'error') error(event.error?.includes('no longer alive') ? 'H3 завершил сессию на своей стороне. Новая реакция не успела появиться; автоматического повтора нет.' : `H3: ${event.error || event.code}`);
}
function onStop(reason) {
  starting = false; armed = false; video.cancelVideoFrameCallback?.(frameCallback);
  if (recorder?.state === 'recording') recorder.stop();
  else { video.pause(); badge(recordingUrl ? 'Запись live-пробы' : 'Live остановлен'); }
  note(`${reason} Новая сессия сама не запускается.`); $('timer').hidden = true; controls();
}
$('chatForm').onsubmit = async event => {
  event.preventDefault(); const text = $('messageInput').value.trim(); if (!text || busy || starting) return;
  const sentAt = Date.now(); busy = true; error(''); message('user', text); const pending = message('assistant', 'Готовит ответ…', true);
  $('messageInput').value = ''; controls(); $('chatStatus').textContent = 'ИИ отвечает · H3 готовит реакцию';
  try {
    if (qa) { pending.remove(); message('assistant', 'QA: внешние модели отключены.'); return; }
    config = await api('config');
    if (!sessionId) sessionId = (await api('session', { heroId: hero.id })).id;
    const input = { sessionId, message: text, requestId: crypto.randomUUID(), videoProvider: 'h3', animate: false };
    let result;
    try { result = await api('chat', input); }
    catch (err) { if (err.code !== 'SESSION') throw err; sessionId = (await api('session', { heroId: hero.id })).id; result = await api('chat', { ...input, sessionId }); }
    pending.remove(); message('assistant', result.reply);
    const prompt = `Mascot is the character in the named reference image. ${result.videoPrompt} Keep a plain pale lavender background. One small expressive gesture lasting 5 to 8 seconds, then remain calmly attentive. No dialogue, no music, no scene changes.`;
    if (live && !live.closed) live.update(prompt, sentAt);
    else if (armed && config.h3Ready) {
      starting = true; firstMessageAt = sentAt; firstFrameMs = null; recorder = null;
      badge('Подключаем H3', 'connecting'); note('Открываем одну live-сессию с лимитом 60 секунд…');
      live = new H3Live({ api, token: config.clientToken, onMedia: attachStream, onEvent, onStop }); controls();
      try { await live.start(sessionId, prompt, sentAt); } catch (err) { error(err.message); await live.stop('Подключение не удалось.'); }
      starting = false;
    } else note('Текстовый ответ готов. Для новой live-сессии включи её кнопкой слева.');
  } catch (err) { pending.remove(); error(err.message); }
  finally { busy = false; $('chatStatus').textContent = 'ИИ отвечает · маскот дополняет ответ без слов'; controls(); }
};
$('messageInput').oninput = controls;
$('messageInput').onkeydown = event => { if (event.key === 'Enter' && !event.shiftKey && !event.isComposing) { event.preventDefault(); $('chatForm').requestSubmit(); } };
$('liveButton').onclick = () => {
  if (live && !live.closed) live.stop();
  else { armed = !armed; note(armed ? 'Следующее сообщение запустит новую live-сессию до 60 секунд.' : 'Live выключен. Доступен текстовый чат.'); controls(); }
};
video.onclick = () => video.play().catch(() => {});
$('heroSelect').onchange = () => { sessionId = null; renderHero($('heroSelect').value); };
$('newChat').onclick = () => { sessionId = null; $('messages').replaceChildren(); error(''); };
$('settingsButton').onclick = () => { $('chatKeyStatus').textContent = config.chatReady ? 'Подключён' : 'Нужен ключ'; $('videoKeyStatus').textContent = config.h3Ready ? 'H3 Reference подключён' : 'Нужен ключ'; $('settingsDialog').showModal(); };
$('closeSettings').onclick = () => $('settingsDialog').close();
window.addEventListener('pagehide', () => { if (live && !live.closed) { try { live.handle?.send({ type: 'stop' }); live.handle?.close(); } catch {} if (live.prepared) api('h3/end', { runId: live.prepared.runId }, true).catch(() => {}); } });
setInterval(() => { const running = live && !live.closed && live.deadline; $('timer').hidden = !running; if (running) { const remaining = Math.max(0, Math.ceil((live.deadline - Date.now()) / 1000)); $('timer').textContent = `${remaining} с до остановки`; if (!remaining) live.stop('Лимит пробы: 60 секунд.'); } }, 250);
try {
  config = await api('config'); document.title = 'Маскот · H3 Live'; document.querySelector('.brand-live').textContent = 'H3 live'; document.querySelector('.brand').href = '/h3';
  document.querySelector('.prototype').textContent = 'Чат + живой видеопоток';
  $('inputHint').textContent = 'H3: $3 за пробу до 60 с · без аудио';
  document.querySelector('#settingsDialog h2').textContent = 'H3 Live';
  document.querySelector('#settingsDialog > p').textContent = 'Первое сообщение открывает видеопоток с Уной. Следующие обновляют направление анимации внутри той же сессии. H3 применяет изменения на следующем доступном фрагменте.';
  document.querySelector('#settingsDialog .small').textContent = 'Проверенное списание H3 Reference: $3 за запуск (минимум 60 оплачиваемых секунд по $0,05). Проба автоматически останавливается не позднее 60 секунд. Запись после остановки можно пересматривать бесплатно.';
  const metrics = document.createElement('p'); metrics.id = 'h3Metrics'; metrics.className = 'video-note'; metrics.textContent = 'Задержку первого кадра измерим после отправки.'; $('videoStatus').after(metrics);
  for (const item of config.heroes) { const option = document.createElement('option'); option.value = item.id; option.textContent = item.name; $('heroSelect').append(option); }
  renderHero('una'); $('messageInput').value = 'Я новичок в теннисе. С чего начать, чтобы научиться уверенно подавать?';
  document.querySelectorAll('[data-suggestion]').forEach(button => button.onclick = () => { $('messageInput').value = button.dataset.suggestion; controls(); $('messageInput').focus(); });
  if (!qa) {
    const trial = await api('h3/last-trial').catch(() => null);
    if (trial?.video) {
      armed = false; renderHero(trial.heroId);
      for (const turn of trial.turns) { message('user', turn.question); message('assistant', turn.reply); }
      $('messageInput').value = '';
      video.src = trial.video; video.controls = true; video.muted = true; video.hidden = false;
      video.onloadeddata = () => { $('heroImage').hidden = true; };
      badge('Запись H3-пробы');
      note('Live-проба завершена. Это сохранённая запись; просмотр не запускает генерацию.');
      $('h3Metrics').textContent = `Первый кадр: ${trial.firstFrameSeconds.toFixed(1)} с · Стоимость: $${trial.costUsd}. Вторую реакцию H3 не успел показать.`;
      video.play().catch(() => {});
    }
  }
  if (qa) { config.chatReady = true; const banner = document.createElement('div'); banner.className = 'qa-banner'; banner.textContent = 'QA: внешние запросы отключены'; document.body.prepend(banner); }
  controls();
} catch { error('Не удалось загрузить H3-прототип. Проверь локальный сервер.'); }
