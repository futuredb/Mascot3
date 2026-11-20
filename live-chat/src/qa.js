import { H3Live } from './h3-live.js';
import { LiveSession } from './live-session.js';
import { prepareReference } from './reference.js';
import { conditionMascot, updateMascotPrompt, mascotInstruction } from './helios-conditioning.js';
const defer = () => { let resolve, reject; const promise = new Promise((a, b) => { resolve = a; reject = b; }); return { promise, resolve, reject }; };
const assert = (value, message) => { if (!value) throw new Error(message); };
const tick = () => new Promise(resolve => setTimeout(resolve, 0));
function fixture(overrides = {}, maxMs = 5000, deadlines = {}) {
  const log = { starts: 0, disconnects: 0, releases: 0, prompts: [], states: [], handlers: null, acknowledgements: 0 };
  const client = {
    connectWithCredentials: async (_credentials, handlers) => { log.handlers = handlers; return new MediaStream(); },
    startStream: async () => { log.starts++; return 'qa-stream'; },
    interact: async ({ prompt }) => { log.prompts.push(prompt); return prompt; },
    endStream: async () => {}, disconnect: () => { log.disconnects++; }, ...overrides,
  };
  const live = new LiveSession({ factory: () => client, credentials: async () => ({ credentials: {} }),
    release: async () => { log.releases++; }, onState: (state, message) => log.states.push({ state, message }), onMedia: () => {},
    onAck: () => log.acknowledgements++, maxMs, ...deadlines });
  return { live, log, client };
}
const start = live => live.start({ sessionId: 'qa-session', image: new Blob(), prompt: 'Initial state' });

document.getElementById('run').onclick = async () => {
  const button = document.getElementById('run'); button.disabled = true;
  const results = []; const list = document.getElementById('results'); list.replaceChildren();
  document.getElementById('summary').textContent = 'Выполняем проверки…';
  async function check(name, fn) {
    try { await fn(); results.push({ name, passed: true }); }
    catch (error) { results.push({ name, passed: false, error: error.message }); }
    const result = results.at(-1); const li = document.createElement('li'); li.textContent = `${result.passed ? '✓' : '✕'} ${name}${result.error ? ': ' + result.error : ''}`; li.style.color = result.passed ? '#42705a' : '#a14c50'; list.append(li);
  }
  await check('Helios: каждая реакция сохраняет исходный образ', async () => {
    const image = { id: 'reference' }, calls = [];
    const model = { setConditioning: async value => { calls.push(value); return { has_image: true, has_prompt: true }; } };
    await conditionMascot(model, image, 'blinks', 'не выводить текст беседы', 'a small cat', 'не добавлять ответ');
    await conditionMascot(model, image, 'nods', 'другой текст');
    assert(calls.length === 2 && calls.every(x => x.image === image), 'Reference was lost');
    assert(!calls[0].prompt.includes('не выводить текст беседы') && !calls[0].prompt.includes('не добавлять ответ'), 'Conversation leaked into video instructions');
    const prompt = mascotInstruction('slices a lemon');
    assert(prompt.includes('slices a lemon') && prompt.includes('separate props'), 'Action or props missing');
    assert(prompt.includes('#EAEBEC') && prompt.includes('Fixed camera') && prompt.includes('full character in frame'), 'Background or framing missing');
  });
  await check('Helios: неподтверждённый референс отклоняется', async () => {
    let failed = false;
    try { await conditionMascot({ setConditioning: async () => ({ has_image: false, has_prompt: true }) }, {}, 'blink'); } catch { failed = true; }
    assert(failed, 'Missing reference acknowledgement accepted');
  });
  await check('Helios: реакция обновляет промпт без повторной установки изображения', async () => {
    const prompts = [];
    const model = { setPrompt: async value => { prompts.push(value); return { type: 'prompt_accepted' }; },
      setConditioning: async () => { throw new Error('Image was reset'); } };
    await updateMascotPrompt(model, 'blinks', '', 'a small cat');
    assert(prompts.length === 1 && prompts[0].prompt.includes('a small cat') && !('image' in prompts[0]), 'Incorrect prompt update');
    let failed = false;
    try { await updateMascotPrompt({ setPrompt: async () => undefined }, 'blink'); } catch { failed = true; }
    assert(failed, 'Missing prompt acknowledgement accepted');
  });
  await check('Отмена во время подключения не запускает видео после позднего ответа', async () => {
    const gate = defer(); const { live, log } = fixture({ connectWithCredentials: () => gate.promise });
    const job = start(live); await tick(); live.stop(); gate.resolve(new MediaStream()); await job; await tick();
    assert(log.starts === 0 && log.releases === 1 && !live.run, 'Late connection was not disposed');
  });
  await check('Ошибка старта освобождает соединение и возвращает явный статус', async () => {
    const { live, log } = fixture({ startStream: async () => { throw new Error('qa-error'); } });
    await start(live); await tick();
    assert(log.releases === 1 && log.disconnects > 0 && log.states.at(-1).state === 'error', 'Leaked session');
  });
  await check('Смена эмоции обновляет текущий поток без нового startStream', async () => {
    const { live, log } = fixture(); await start(live);
    await live.react('Quiet joy with a slightly uncertain smile'); await live.react('Concern with warm attentive eyes');
    assert(log.starts === 1 && log.prompts.length === 2 && log.acknowledgements === 2, 'Stream restarted or reaction lost'); live.stop();
  });
  await check('Очередь реакций оставляет самое новое состояние', async () => {
    const gate = defer(); const seen = []; const { live } = fixture({ interact: async ({ prompt }) => { seen.push(prompt); if (seen.length === 1) await gate.promise; } });
    await start(live); const first = live.react('first'); await tick(); await live.react('obsolete'); await live.react('latest'); gate.resolve(); await first;
    assert(seen.join(',') === 'first,latest', 'Stale reactions accumulated'); live.stop();
  });
  await check('Остановка отбрасывает запоздалое подтверждение реакции', async () => {
    const gate = defer(); const { live, log } = fixture({ interact: () => gate.promise }); await start(live);
    const pending = live.react('late'); live.stop(); gate.resolve(); await pending;
    assert(log.acknowledgements === 0 && !live.run, 'Stale acknowledgement changed state');
  });
  await check('Таймер и ошибка потока завершают сессию без автоперезапуска', async () => {
    const a = fixture({}, 15); await start(a.live); await new Promise(resolve => setTimeout(resolve, 35));
    assert(!a.live.run && a.log.releases === 1 && a.log.starts === 1, 'Timer failed');
    const b = fixture(); await start(b.live); b.log.handlers.onStreamError('session_timeout', 'fixture'); await tick();
    assert(!b.live.run && b.log.releases === 1 && b.log.starts === 1 && b.log.states.at(-1).state === 'error', 'Auto restart or leaked session');
  });
  await check('Зависшие подключение и реакция останавливаются по таймауту', async () => {
    const connect = defer();
    const a = fixture({ connectWithCredentials: () => connect.promise }, 5000, { connectMs: 15 });
    const pendingStart = start(a.live); await new Promise(resolve => setTimeout(resolve, 35));
    assert(!a.live.run && a.log.states.at(-1).state === 'error', 'Connect deadline failed');
    connect.resolve(new MediaStream()); await pendingStart;
    assert(a.log.starts === 0 && a.log.releases === 1, 'Late connection started stream');
    const reaction = defer();
    const b = fixture({ interact: () => reaction.promise }, 5000, { reactionMs: 15 }); await start(b.live);
    const pendingReaction = b.live.react('Slow reaction'); await new Promise(resolve => setTimeout(resolve, 35));
    assert(!b.live.run && b.log.releases === 1, 'Reaction deadline failed');
    reaction.resolve(); await pendingReaction;
    assert(b.log.acknowledgements === 0, 'Stale acknowledgement after timeout');
  });
  await check('Исходный герой вписывается в портретный кадр без обрезки', async () => {
    const canvas = document.createElement('canvas'); canvas.width = 100; canvas.height = 50;
    const context = canvas.getContext('2d'); context.fillStyle = '#ff0000'; context.fillRect(0, 0, 100, 50);
    const input = await new Promise(resolve => canvas.toBlob(resolve));
    const bitmap = await createImageBitmap(await prepareReference(input));
    assert(bitmap.width === 704 && bitmap.height === 1280, 'Incorrect portrait dimensions');
    canvas.width = bitmap.width; canvas.height = bitmap.height; context.drawImage(bitmap, 0, 0); bitmap.close();
    const margin = context.getImageData(352, 0, 1, 1).data;
    const edge = context.getImageData(0, 640, 1, 1).data;
    assert(margin[0] === 234 && margin[3] === 255 && edge[0] === 255 && edge[1] === 0, 'Reference was cropped or transparent');
  });
  await check('H3: новые реакции используют одну сессию; stop закрывает её без перезапуска', async () => {
    let opens = 0, closes = 0; const sent = [];
    const live = new H3Live({ api: async () => ({ runId: 'fixture', endpoint: 'fixture', referenceUrl: 'fixture', maxSeconds: 60 }), token: 'fixture', onMedia() {}, onEvent() {}, onStop() {},
      open: () => { opens++; return { ready: Promise.resolve(), send: data => sent.push(data), close: () => { closes++; } }; } });
    await live.start('fixture', 'first', Date.now()); live.update('second', Date.now()); live.update('third', Date.now()); await live.stop();
    assert(opens === 1 && closes === 1 && sent.map(x => x.type).join(',') === 'configure,prompt,prompt,stop' && sent[2].prompt_version === 3, 'Wrong live lifecycle');
  });
  await check('H3: отмена до подготовки не открывает платную сессию', async () => {
    const gate = defer(); let opens = 0, ended = 0;
    const live = new H3Live({ api: async path => { if (path === 'h3/prepare') return gate.promise; ended++; return {}; }, token: 'fixture', onMedia() {}, onEvent() {}, onStop() {}, open: () => { opens++; } });
    const start = live.start('fixture', 'first', Date.now()); await live.stop(); gate.resolve({ runId: 'fixture' }); await start;
    assert(opens === 0 && ended === 1, 'Late preparation opened connection');
  });
  try {
    const config = await fetch('/api/config').then(r => r.json());
    await check('Изменяющие API-запросы требуют локальный токен', async () => {
      const res = await fetch('/api/session', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ heroId: 'akim' }) });
      assert(res.status === 403, 'Missing token accepted');
    });
    const res = await fetch('/api/qa', { method: 'POST', headers: { 'Content-Type': 'application/json', 'X-Mascot-Client': config.clientToken }, body: '{}' });
    if (!res.ok) throw new Error('Server QA failed');
    const server = await res.json();
    for (const item of server.results) await check(item.name, () => { if (!item.passed) throw new Error(item.error); });
  } catch (error) { await check('Проверки сервера', () => { throw error; }); }
  const passed = results.filter(r => r.passed).length;
  document.getElementById('summary').textContent = `${passed} из ${results.length} проверок пройдены. Внешних AI-запросов: 0. Качество live-видео этими проверками не подтверждается.`;
  document.getElementById('report').textContent = JSON.stringify({ passed, total: results.length, externalAIRequests: 0, results }, null, 2);
  button.disabled = false;
};
