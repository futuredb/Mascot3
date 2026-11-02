import { createFalClient } from '@fal-ai/client';
import { wma } from '@fal-ai/client/realtime';

export class H3Live {
  constructor({ api, token, onMedia, onEvent, onStop, open }) {
    Object.assign(this, { api, token, onMedia, onEvent, onStop, open });
    this.closed = false; this.version = 0; this.startedAt = Date.now();
  }
  async start(sessionId, prompt, sentAt) {
    this.prepared = await this.api('h3/prepare', { sessionId, requestId: crypto.randomUUID() });
    if (this.closed) { await this.api('h3/end', { runId: this.prepared.runId }); return; }
    this.startedAt = Date.now();
    this.deadline = this.startedAt + this.prepared.maxSeconds * 1000;
    this.timer = setTimeout(() => this.stop('Лимит пробы: 60 секунд.'), this.prepared.maxSeconds * 1000);
    const client = createFalClient({ proxyUrl: '/api/h3/proxy', retry: { maxRetries: 0 }, fetch: (url, init) => {
      const headers = new Headers(init?.headers); headers.set('X-Mascot-Client', this.token); headers.set('X-Mascot-H3-Run', this.prepared.runId);
      return fetch(url, { ...init, headers });
    } });
    this.handle = (this.open || ((options) => client.realtime.open(wma(this.prepared.endpoint), options)))({
      receive: ['video', 'audio'], negotiationTimeoutMs: 30_000,
      onMedia: stream => { if (!this.closed) this.onMedia(stream); },
      onData: raw => { try { const event = JSON.parse(raw); this.report(event); if (event.type === 'error') this.stop(`H3: ${event.error}`); if (event.type === 'stream_exhausted') this.stop('H3 завершил поток.'); } catch {} },
      onState: state => { this.report({ type: 'transport', state }); if ((state === 'closed' || state === 'failed') && !this.closed) this.stop('Поток отключился. Автоперезапуска нет.'); },
      onError: error => { this.report({ type: 'error', error: error.message || String(error) }); this.stop('Не удалось подключить H3. Подробности — под чатом.'); },
    });
    this.version = 1; this.sentAt = new Map([[1, sentAt]]);
    this.handle.send({ type: 'configure', protocol_version: 1, prompt_version: 1, prompt,
      resolution: '480p', aspect_ratio: '9:16', memory: 6, num_inference_steps: 10,
      forced_cut_max_segments: 0, reference_images: [{ url: this.prepared.referenceUrl, name: 'Mascot', fidelity: 'high' }] });
    await this.handle.ready;
    if (this.closed) await this.handle.close();
  }
  update(prompt, sentAt) {
    if (this.closed || !this.handle) throw new Error('Live-сессия уже закончилась.');
    const version = ++this.version; this.sentAt.set(version, sentAt);
    this.handle.send({ type: 'prompt', prompt_version: version, prompt, replan: true });
    this.report({ type: 'prompt_sent', prompt_version: version }); return version;
  }
  report(event) {
    if (event.prompt_version && this.sentAt?.has(event.prompt_version)) event = { ...event, elapsedMs: Date.now() - this.sentAt.get(event.prompt_version) };
    this.onEvent(event);
    if (this.prepared) this.api('h3/event', { runId: this.prepared.runId, event }).catch(() => {});
  }
  async stop(reason = 'Поток остановлен.') {
    if (this.closed) return;
    this.closed = true; clearTimeout(this.timer);
    try { this.handle?.send({ type: 'stop' }); } catch {}
    // Give the control channel a moment to deliver stop before tearing down the peer.
    await new Promise(resolve => setTimeout(resolve, 200));
    try { await this.handle?.close(); } catch {}
    if (this.prepared) await this.api('h3/end', { runId: this.prepared.runId }).catch(() => {});
    this.onStop(reason);
  }
}
