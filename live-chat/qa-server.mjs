import { createChatService } from './chat.mjs';
import { runWanChecks } from './qa-wan.mjs';
import { runH3Checks } from './qa-h3.mjs';
import { runTurboChecks } from './qa-turbo.mjs';
import { createRequire } from 'node:module';
import { readFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { createHeliosHarness } from './helios-qa-harness.mjs';
const require = createRequire(import.meta.url);

// Called only by the QA button inside Codex preview. No provider network access.
export async function runServerChecks() {
  const results = [];
  const check = async (name, fn) => {
    try { await fn(); results.push({ name, passed: true }); }
    catch (error) { results.push({ name, passed: false, error: error.message }); }
  };
  const assert = (value, text) => { if (!value) throw new Error(text); };
  await check('Глитч Helios: новый запрос ждёт блок, сброс скрывает реальный стык', async () => {
    const h = await createHeliosHarness();
    await h.sandbox.reactMascot('local-image', 'idle');
    h.live.frame(1000, {presentedFrames:1});
    await h.sandbox.reactMascot('local-image', 'smiles');
    h.live.frame(1100, {presentedFrames:1});
    assert(!h.calls.includes('glitch-begin'), 'Glitch started before a new chunk');
    h.instances[0].chunkComplete(); h.live.frame(1200, {presentedFrames:1});
    assert(h.calls.filter(x => x === 'glitch-begin').length === 1, 'Prompt boundary missing');
    h.live.frame(1300, {presentedFrames:1});
    assert(h.calls.filter(x => x === 'glitch-begin').length === 1, 'Continuous frames retrigger the glitch');
    await h.sandbox.restartMascot('local-image', 'waves');
    const before = h.calls.filter(x => x === 'draw-frame').length;
    h.live.frame(1500, {presentedFrames:1});
    assert(h.calls.filter(x => x === 'draw-frame').length === before, 'Old stream appeared while waiting');
    h.instances[0].chunkComplete(); h.live.frame(2000, {presentedFrames:1});
    assert(h.calls.filter(x => x === 'glitch-begin').length === 2, 'Reset boundary missing');
    await h.sandbox.closeMascot();
  });
  await check('Helios: сброс начинается до ответа, реакция применяется в той же сессии', async () => {
    const h = await createHeliosHarness();
    await h.sandbox.reactMascot('local-image', 'idle', '', 'original character');
    const early = h.sandbox.restartMascot('local-image', 'listening', '', 'original character', '');
    const reply = h.sandbox.replyMascot('local-image', 'smiles', 'message', 'original character', 'answer');
    await early; await reply;
    const actions = h.calls.filter(x => typeof x === 'object').map(x => x.action);
    assert(h.calls.filter(x => x === 'connect').length === 1 && h.calls.filter(x => x === 'reset').length === 1, 'Reaction reconnected or reset twice');
    assert(actions.slice(-2).join(',') === 'listening,smiles', 'Reply was not applied after the reset');
    await h.sandbox.closeMascot();
  });
  await check('Helios: подключение идёт параллельно ответу, повторно не открывает сессию', async () => {
    const h = await createHeliosHarness({ slowConnect: true });
    const preparing = h.sandbox.restartMascot('local-image', 'listening', '', 'original character', '');
    await new Promise(resolve => setImmediate(resolve));
    const reaction = h.sandbox.replyMascot('local-image', 'smiles', 'message', 'original character', 'answer');
    h.resolveConnect();
    await preparing; await reaction;
    assert(h.calls.filter(x => x === 'connect').length === 1, 'Parallel setup opened another session');
    assert(h.calls.filter(x => typeof x === 'object').at(-1).action === 'smiles', 'Final reaction was lost');
    await h.sandbox.closeMascot();
  });
  await check('Чат: готовый клип повторяется после отключения Helios до следующего запроса', async () => {
    const h = await createHeliosHarness({ finite: true });
    await h.sandbox.restartMascot('local-image', 'shows a banana', 'show a banana', 'original character', 'Here it is');
    h.live.frame(1000, { presentedFrames: 1 });
    const capture = h.timers.findLast(t => t.ms === 8000 && !t.cancelled);
    assert(capture, 'Finite capture timer missing');
    capture.fn();
    await new Promise(resolve => setImmediate(resolve));
    assert(h.calls.filter(x => x === 'disconnect').length === 1, 'Capture did not disconnect');
    assert(h.replay.loop && h.events.some(([type]) => type === 'replay'), 'Local video did not start looping');
    await h.sandbox.restartMascot('local-image', 'shows a cup', 'show a cup', 'original character', 'Here it is');
    assert(h.calls.filter(x => x === 'connect').length === 2, 'Next request did not open a fresh connection');
    await h.sandbox.closeMascot();
  });
  await check('Helios: локальный повтор не останавливает поток и сессию', async () => {
    const h = await createHeliosHarness();
    await h.sandbox.reactMascot('local-image', 'idle', '', 'original character');
    h.live.frame(1000);
    const capture = h.timers.findLast(t => t.ms === 30000 && !t.cancelled);
    assert(capture, 'Loop capture timer missing');
    capture.fn(); await new Promise(resolve => setImmediate(resolve));
    assert(h.events.some(([type]) => type === 'replay'), 'Recorded clip was not replayed');
    assert(!h.calls.includes('pause') && !h.calls.includes('disconnect'), 'Live connection was stopped during replay');
    const expiry = h.timers.findLast(t => t.ms === 30000 && !t.cancelled && t !== capture);
    assert(!expiry, 'Replay unexpectedly scheduled session expiry');
    await h.sandbox.closeMascot();
    assert(h.calls.includes('disconnect'), 'Closing did not release session');
  });
  await check('Helios: новый запрос выходит из повтора и использует ту же сессию', async () => {
    const h = await createHeliosHarness();
    await h.sandbox.reactMascot('local-image', 'idle', '', 'original character');
    h.live.frame(1000);
    const capture = h.timers.findLast(t => t.ms === 30000 && !t.cancelled);
    capture.fn(); await new Promise(resolve => setImmediate(resolve));
    await h.sandbox.restartMascot('local-image', 'nods', 'next message', 'original character', 'answer');
    assert(h.calls.filter(x => x === 'connect').length === 1 && h.calls.includes('reset'), 'Active session was not reused');
    h.instances[0].chunkComplete(); h.live.frame(1000);
    assert(h.events.some(([type]) => type === 'restart-frame'), 'Live frames did not replace replay');
    await h.sandbox.closeMascot();
  });
  await check('Helios: чат получает первый кадр из потока, запущенного до открытия', async () => {
    const h = await createHeliosHarness();
    h.sandbox.setMascotVisibility(false);
    await h.sandbox.reactMascot('local-image', 'idle', '', 'original character');
    h.live.frame(1000); h.live.frame(2000);
    assert(h.events.some(([type]) => type === 'frame'), 'The hidden player did not report its first frame');
    assert(!h.calls.includes('draw-frame'), 'Hidden canvas was rendered');
    assert(!h.calls.includes('pause') && !h.calls.includes('disconnect'), 'Hidden stream was stopped');
    h.sandbox.setMascotVisibility(true);
    assert(h.calls.includes('draw-frame'), 'Visible portrait did not restore the latest frame');
    assert(h.instances.length === 1 && h.instances[0].starts === 1, 'Opening chat restarted the warmed stream');
    await h.sandbox.closeMascot();
  });
  await check('Helios: первый блок при быстром старте не теряется', async () => {
    const h = await createHeliosHarness({ eagerResetChunk: true });
    await h.sandbox.reactMascot('local-image', 'idle', '', 'original character');
    h.live.frame(1000);
    await h.sandbox.restartMascot('local-image', 'waves', 'message', 'original character', 'answer');
    h.live.frame(2000);
    assert(h.events.some(([type]) => type === 'restart-frame'), 'First reset chunk was lost');
    await h.sandbox.closeMascot();
  });
  await check('Helios: новая реакция сбрасывает текущую сессию и закрытие её отменяет', async () => {
    const h = await createHeliosHarness();
    await h.sandbox.reactMascot('local-image', 'idle', '', 'original character');
    await h.sandbox.restartMascot('local-image', 'smiles', 'message', 'original character', 'answer');
    assert(h.calls.filter(x => x === 'connect').length === 1 && h.calls.includes('reset'), 'Session was not reset');
    assert(h.calls.filter(x => typeof x === 'object').at(-1).action === 'smiles', 'Reaction was lost');
    h.instances[0].chunkComplete();
    h.live.frame(1000);
    assert(h.events.some(([type]) => type === 'restart-frame'), 'New frame did not unlock the transition');
    await h.sandbox.closeMascot();
    assert(h.calls.filter(x => x === 'disconnect').length === 1, 'Session was not closed');
  });
  await check('Helios: обновление образа не обрывает реакцию и останавливается при выходе', async () => {
    const h = await createHeliosHarness();
    await h.sandbox.reactMascot('local-image', 'idle', '', 'original character');
    const idleTimer = h.timers.find(t => t.ms === 15000 && !t.cancelled);
    await h.sandbox.reactMascot('local-image', 'smiles', 'message', 'original character', 'answer');
    assert(idleTimer?.cancelled, 'Old refresh can interrupt reaction');
    const timer = h.timers.findLast(t => t.ms === 30000 && !t.cancelled);
    assert(timer, 'Reaction refresh delay missing');
    const before = h.calls.filter(x => typeof x === 'object').length;
    timer.fn();
    await new Promise(resolve => setImmediate(resolve));
    assert(!h.calls.some(x => typeof x === 'string' && x.startsWith('rewind-')), 'Refresh rewound the animation');
    const calls = h.calls.filter(x => typeof x === 'object');
    assert(calls.length === before + 1 && calls.at(-1).appearance === 'original character' && calls.at(-1).action === 'smiles', 'Latest reference or action not refreshed');
    const next = h.timers.findLast(t => t.ms === 15000 && !t.cancelled);
    assert(next && next !== timer, 'Next refresh was not scheduled');
    await h.sandbox.closeMascot();
    assert(next.cancelled, 'Refresh continues after leaving chat');
  });
  await check('Описания встроенных героев соответствуют исходным изображениям', async () => {
    const root = new URL('../android/app/src/main/assets/bundled-heroes/', import.meta.url);
    for (const id of ['716cdb43-703b-4d7e-8a79-27f03d92b99c', '9f226b89-df35-4d3a-9b36-96c28ea1bb62', 'a83e9d30-1270-40e6-902c-b44774e84c17']) {
      const image = await readFile(new URL(id + '/base.png', root));
      const record = JSON.parse(await readFile(new URL(id + '/creative/appearance.json', root), 'utf8'));
      assert(record.description && createHash('sha256').update(image).digest('hex') === record.imageSha256, 'Description does not match reference');
    }
  });
  await check('Генерация сохраняет выбранный предмет, признаки и ракурс', async () => {
    const { buildCanonicalRenderPrompt, validateAnchorPlan } = require('../pipeline/lib/canonical.js');
    const anchorProp = { prop_id: 'requested_object', placement: 'beside the character' };
    const renderIdentity = { subject: 'small squirrel', silhouette_cue: 'large tail', proportion_cue: 'compact body', neutral_pose: 'hovering', persistent_prop: anchorProp.prop_id, prop_placement: anchorProp.placement };
    const prompt = buildCanonicalRenderPrompt({ renderIdentity, anchorProp, propLabel: 'thimble', characterBrief: 'A squirrel with a curled tuft.', viewRule: 'Front view.', propViewRule: 'Beside the character.' });
    const plan = views => views.map(view => ({ view }));
    assert(prompt.includes('Persistent prop: thimble.') && prompt.includes('curled tuft'), 'Character detail or prop missing');
    assert(validateAnchorPlan('top', plan(['top','side','back'])) && !validateAnchorPlan('top', plan(['front','side','back'])), 'Wrong first view');
  });
  await check('Каталог анимаций согласован с правилами генерации', async () => {
    const { validateCatalog } = require('../pipeline/lib/prompt_catalog.js');
    const root = new URL('../pipeline/', import.meta.url);
    const catalog = JSON.parse(await readFile(new URL('dict/animation_prompt_catalog.json', root), 'utf8'));
    const animations = JSON.parse(await readFile(new URL('dict/animations.json', root), 'utf8'));
    const source = await readFile(new URL('scripts/generate_animation_prompt_catalog.js', root), 'utf8');
    validateCatalog(catalog, animations);
    assert(source.includes('const suffix = ' + JSON.stringify(catalog.suffix)) && catalog.suffix.includes('add no new objects'), 'Catalog differs from generator');
    assert(catalog.entries.find(x => x.id === 'work')?.availability === 'disabled', 'Rejected animation enabled');
  });
  const good = { reply_ru: 'Здорово! Как ты себя чувствуешь?', reaction_en: 'The character is smiling softly, eyes bright and shoulders relaxed.', mood_ru: 'Тихая радость с любопытством' };
  const config = () => ({ openrouterKey: 'qa-only-not-a-key', chatModel: 'qa' });
  const response = value => ({ ok: true, json: async () => ({ choices: [{ message: { content: JSON.stringify(value) }, finish_reason: 'stop' }] }) });
  await check('Повтор одного сообщения не создаёт второй запрос к модели', async () => {
    let calls = 0;
    const service = createChatService({ getConfig: config, fetchImpl: async () => { calls++; return response(good); } });
    const session = service.create('akim');
    const input = { sessionId: session.id, message: 'Получилось!', requestId: 'same-request-123' };
    const [a, b] = await Promise.all([service.send(input), service.send(input)]);
    assert(calls === 1 && a.reply === b.reply && a.videoPrompt.includes(good.reaction_en), 'Duplicate call or missing free-form reaction');
  });
  await check('История и предыдущее настроение передаются в следующий ход', async () => {
    const payloads = [];
    const service = createChatService({ getConfig: config, fetchImpl: async (_url, init) => { payloads.push(JSON.parse(init.body)); return response(good); } });
    const s = service.create('akim');
    await service.send({ sessionId: s.id, message: 'Я закончил проект', requestId: 'history-first' });
    await service.send({ sessionId: s.id, message: 'Но он сломался', requestId: 'history-next' });
    assert(payloads[1].messages.some(x => x.content === 'Я закончил проект') && payloads[1].messages[0].content.includes(good.mood_ru), 'Lost context');
    const other = service.create('una');
    assert(service.get(other.id).history.length === 0, 'History crossed characters');
  });
  await check('Ошибка схемы не запускает платный повтор и не портит историю', async () => {
    let calls = 0;
    const service = createChatService({ getConfig: config, fetchImpl: async () => { calls++; return response({ reply_ru: 'неполный ответ' }); } });
    const s = service.create('akim'); const input = { sessionId: s.id, message: 'Привет', requestId: 'invalid-output' };
    const outcomes = await Promise.allSettled([service.send(input), service.send(input)]);
    assert(outcomes.every(x => x.status === 'rejected' && x.reason.code === 'INVALID_RESPONSE') && calls === 1 && service.get(s.id).history.length === 0, 'Unsafe retry or history pollution');
  });
  await check('Пустой ввод, отсутствующий ключ и конфликт id не вызывают модель', async () => {
    let calls = 0;
    const service = createChatService({ getConfig: () => ({ openrouterKey: '' }), fetchImpl: async () => { calls++; throw new Error(); } });
    const s = service.create('mars');
    const a = await service.send({ sessionId: s.id, message: ' ', requestId: 'input-empty' }).catch(e => e.code);
    const b = await service.send({ sessionId: s.id, message: 'Привет', requestId: 'input-nokey' }).catch(e => e.code);
    assert(a === 'MESSAGE' && b === 'CHAT_KEY' && calls === 0, 'Unexpected provider request');
    const valid = createChatService({ getConfig: config, fetchImpl: async () => response(good) });
    const v = valid.create('mars');
    await valid.send({ sessionId: v.id, message: 'Первое', requestId: 'same-message-id' });
    const conflict = await valid.send({ sessionId: v.id, message: 'Другое', requestId: 'same-message-id' }).catch(e => e.code);
    assert(conflict === 'ID_REUSED', 'Reused ID accepted');
  });
  results.push(...await runWanChecks(), ...await runH3Checks(), ...await runTurboChecks());
  return { results, externalCalls: 0, checkedAt: new Date().toISOString() };
}
