import { createChatService } from './chat.mjs';
import { runWanChecks } from './qa-wan.mjs';
import { runH3Checks } from './qa-h3.mjs';
import { runTurboChecks } from './qa-turbo.mjs';

// Called only by the QA button inside Codex preview. No provider network access.
export async function runServerChecks() {
  const results = [];
  const check = async (name, fn) => {
    try { await fn(); results.push({ name, passed: true }); }
    catch (error) { results.push({ name, passed: false, error: error.message }); }
  };
  const assert = (value, text) => { if (!value) throw new Error(text); };
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
