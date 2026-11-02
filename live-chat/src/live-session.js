// Only a real provider MediaStream is displayed. No clip or CSS-emotion fallback.
export class LiveSession {
  constructor({ factory, credentials, release, onState, onMedia, onAck = () => {}, maxMs = 120_000, connectMs = 60_000, reactionMs = 15_000 }) {
    Object.assign(this, { factory, credentials, release, onState, onMedia, onAck, maxMs, connectMs, reactionMs });
    this.run = null;
  }
  async start({ sessionId, image, prompt }) {
    if (this.run) throw new Error('Видеосессия уже запущена.');
    const run = { sessionId, requestId: crypto.randomUUID(), client: null, connected: false, started: false, closed: false, pending: null, working: false };
    this.run = run;
    this.onState('connecting', 'Подключаем видео…');
    run.phaseTimer = setTimeout(() => this.fail(run, 'Модель слишком долго открывает видео. Подключение остановлено.'), this.connectMs);
    try {
      const result = await this.credentials({ sessionId, requestId: run.requestId });
      if (run.closed) return false; // Unconsumed credentials remain reserved server-side until expiry.
      run.client = this.factory();
      const stream = await run.client.connectWithCredentials(result.credentials, {
        onDisconnected: () => { if (!run.closed) this.fail(run, 'Связь с видеомоделью прервалась.'); },
        onError: (_error, fatal) => { if (fatal && !run.closed) this.fail(run, 'Видеомодель завершила соединение.'); },
        onStreamError: (reason) => {
          if (!run.closed) this.fail(run, reason === 'session_timeout' ? 'Время видеосессии истекло. Можно запустить новую.' : 'Видеомодель не смогла продолжить поток.');
        },
        onStreamEnded: () => { if (!run.closed) this.fail(run, 'Видеосессия завершилась. Можно запустить новую.'); },
      });
      run.connected = true;
      if (run.closed) { this.clean(run); return false; }
      this.onMedia(stream);
      // Server image-to-video capability is validated by the SDK on startStream.
      await run.client.startStream({ image, prompt, portrait: true });
      run.started = true;
      if (run.closed) { this.clean(run); return false; }
      clearTimeout(run.phaseTimer);
      run.timer = setTimeout(() => this.stop('Две минуты закончились. Переписка сохранена, видео можно запустить снова.'), this.maxMs);
      this.onState('streaming', 'Ожидаем первый кадр…');
      return true;
    } catch (error) {
      if (run.closed) { this.clean(run); return false; }
      this.fail(run, error.code ? error.message : 'Не удалось запустить видео. Проверь доступ Odyssey и соединение.');
      return false;
    }
  }
  async react(prompt) {
    const run = this.run;
    if (!run || !run.started || run.closed) return false;
    run.pending = prompt;
    if (run.working) return true;
    run.working = true;
    try {
      while (run.pending && !run.closed) {
        const next = run.pending;
        run.pending = null;
        run.reactionTimer = setTimeout(() => this.fail(run, 'Видеомодель не подтвердила реакцию. Поток остановлен.'), this.reactionMs);
        await run.client.interact({ prompt: next });
        clearTimeout(run.reactionTimer);
        if (!run.closed) this.onAck(next);
      }
      return !run.closed;
    } catch {
      if (!run.closed) this.fail(run, 'Не удалось передать реакцию. Текстовый чат продолжает работать.');
      return false;
    } finally { run.working = false; }
  }
  fail(run, message) {
    if (run.closed) return;
    run.closed = true;
    if (this.run === run) this.run = null;
    this.clean(run);
    this.onMedia(null);
    this.onState('error', message);
  }
  stop(message = 'Видео остановлено. Переписка сохранена.') {
    const run = this.run;
    if (run) {
      run.closed = true;
      run.pending = null;
      this.run = null;
      this.clean(run);
    }
    this.onMedia(null);
    this.onState('idle', message);
  }
  clean(run) {
    clearTimeout(run.timer);
    clearTimeout(run.phaseTimer);
    clearTimeout(run.reactionTimer);
    if (run.client) {
      if (run.started) try { Promise.resolve(run.client.endStream()).catch(() => {}); } catch { /* Disconnect still runs. */ }
      try { run.client.disconnect(); } catch { /* Continue local cleanup. */ }
    }
    if (run.connected && !run.released) {
      run.released = true;
      Promise.resolve(this.release({ sessionId: run.sessionId, requestId: run.requestId })).catch(() => {});
    }
  }
}
