# Маскот — автоматические реакции в чате

Локальный прототип: пользователь отправляет сообщение ИИ, OpenRouter возвращает полезный ответ и свободное описание реакции маскота. Сервер автоматически отправляет изображение выбранного героя и это описание в Wan 2.2 Turbo через fal. Браузер проверяет готовность и сам воспроизводит результат рядом с ответом. Микрофон и озвучка не используются.

Wan создаёт отдельный ролик с задержкой, а не непрерывный live-поток. Индивидуальная реакция придумывается по содержанию сообщения и ответа; каталога готовых эмоций нет. Отдельные варианты доступны на `/h3` (live) и `/turbo` (быстрая генерация из картинки).

## Запуск

```bash
npm ci
npm start
```

Открыть http://127.0.0.1:8810 в превью Codex. `/tennis` открывает тот же чат с вопросом про теннис в поле ввода. Генерация начинается только после отправки сообщения, а не при открытии страницы.

Node.js 22+. Сервер доступен только на loopback. Приоритет настроек: окружение процесса → `.env.local` → `../.env`. Ключи не возвращаются браузеру. `.env.local` исключён из Git и имеет права 0600.

- `OPENROUTER_API_KEY`: текстовый ответ и описание реакции. Модель по умолчанию `openai/gpt-4.1-mini`.
- `FAL_WAN_KEY`: ключ с доступом к `fal-ai/wan/v2.2-a14b/image-to-video/turbo`.
- `MASCOT_LIVE_CHAT_MODEL`, `MASCOT_LIVE_PORT`: необязательные настройки.

Ключи выбираются сервером для соответствующего режима; в браузер они не передаются. Главная страница и `/tennis` используют Wan.

## Сценарий и стоимость

Отправить сообщение → получить ответ ИИ → дождаться автоматической анимации. «Авто-анимация» позволяет оставить только текстовый чат. Пока одна реакция создаётся, следующая отправка заблокирована. Готовое видео воспроизводится без звука один раз; повтор через стандартный видеоплеер бесплатный.

Wan фиксирован на 480p, объявленная цена fal — $0,05 за видео. Показ ограничен 8 секундами; endpoint не предоставляет настройки длительности, это ограничение воспроизведения, а не обещание длительности результата или тарифа. Лимит локальной пробы — 20 зарегистрированных генераций (около $1 при текущем тарифе). Ответы OpenRouter оплачиваются отдельно.

Задачи хранятся в `output/wan-jobs/`: идентификатор, статус и URL результата. Повтор одного сообщения не создаёт повторный POST. Запись резервируется до отправки. Если исход отправки неизвестен, повтор и новая генерация блокируются. Перезапуск сервера восстанавливает известную задачу. Перезагрузка страницы восстанавливает последний ответ и проверяет ту же задачу без новой генерации. Активная задача не перезапускается при закрытии вкладки.

История текстового разговора хранится в памяти сервера; после перезапуска новый запрос начинает новый контекст. Внешность, фон и точность жестов зависят от модели. Видео не имеет гарантированного прозрачного фона.

## Проверка

Только внутри превью Codex:

- `/qa`: проверки контрактов чата, идемпотентности и восстановления Wan на подставном транспорте, а также сохранённые проверки предыдущего адаптера Odyssey. Внешних AI-запросов нет.
- `/?qa=1`: явно обозначенный режим интерфейса с тестовым текстом, без внешних AI-вызовов.
- `/` или `/tennis`: реальный запрос пользователя запускает текст и, если включено, одну генерацию Wan.

## Документация провайдера

- [Wan Turbo API](https://fal.ai/models/fal-ai/wan/v2.2-a14b/image-to-video/turbo/api)
- [Queue API](https://fal.ai/docs/documentation/model-apis/inference/queue)
- [Тариф Wan](https://fal.ai/models/fal-ai/wan/v2.2-a14b/image-to-video/turbo)

## H3 live prototype

Open `/h3` in Codex preview. The first chat message starts `minimax/h3-max/reference-director` over WebRTC; later messages update that same session with `prompt` and `replan: true`. The named image reference preserves the selected mascot. The server uses `FAL_H3_KEY`; credentials never reach the browser. Wan remains available at `/tennis`.

The prototype stops after 60 seconds, sends `stop`, closes the peer, and stops renewing the bridge lease. There is no automatic paid reconnect. Enable the live button explicitly before another session. Camera and microphone are not requested; playback and the optional in-tab recording are silent. A replay survives until navigation/reload only.

Latency labels distinguish the first decoded video frame from a new chunk becoming ready. A prompt acknowledgement is not proof the emotion is already visible. H3 Reference's published pricing is experimental; do not infer a free run from the displayed provisional price. Session records and selected protocol events are saved under `output/h3-runs/` without credentials or SDP. Unknown bridge outcomes block another session pending inspection.

The preview QA page covers H3 lifecycle, cancellation, duplicate allocation, fixed model routing, heartbeat ownership and expiry in addition to previous chat/Wan checks.

### Observed run — 28 October 2026

One real session was started by submitting the tennis question in `/h3` inside Codex preview. First decoded frame: 24.398 seconds after sending the message. A second chat message reused the same session; prompt version 2 was accepted after 19.443 seconds, but no version-2 video chunk was received before the bridge reported `alive: false`. Provider request history reports a 38.62-second execution and a 26.88-second saved video. The interruption occurred before the local 60-second deadline; its internal cause is not exposed in the available account logs.

Confirmed billing: `minimax/h3-max/reference-director/start-session`, 60 seconds × $0.05 = **$3.00**. This supersedes the provisional public pricing above. One H3 allocation, no paid retries. The recording and measured outcome are available in `/h3` and `output/h3-trial.json`; playback does not start a session. The test confirms real video reception and command updates, but **does not establish fast visible reactions or stable continuous interaction**. 22/22 mocked checks passed inside preview before the real run.

## Turbo image-to-video latency trial

`/turbo` uses `minimax/h3-max-turbo/image-to-video` automatically after a chat message. Set server-only `FAL_TURBO_KEY` with access to that exact model. Settings are fixed to 5 seconds, 480P, safety checker enabled, prompt expansion disabled. The image is the selected bundled hero; an existing fal upload of the same SHA-256 is reused where available.

The server makes one direct `fal.run` inference POST, journals the reservation before submission, and does not retry a POST. The browser waits for completion without Wan's two-second polling interval. Reload/replay uses the saved video. An ambiguous outcome blocks new paid requests.

The authoritative client timing starts on form submission and ends on the first displayed frame (original trial: `requestVideoFrameCallback`; transparent renderer: after the first canvas frame). It includes config/session setup, the OpenRouter answer/reaction, the fal request, CDN delivery, decoding and display. The first measurement is saved to `output/turbo-jobs/`; replay cannot overwrite it. If preview visibility is lost, the result is labelled invalid. Provider-reported inference time is retained separately; it is not end-to-end latency.

Pricing checked 28 October 2026: $0.01625 per generated second at 480P through October 31 (approximately $0.08125 for five seconds); base rate $0.025/s. Actual output may run slightly longer than requested. The saved outcome, not the advertised inference speed, determines whether the five-second target was met.

25/25 mocked checks passed in Codex preview before the real Turbo trial.

### Observed Turbo run — 28 October 2026

One tennis question was submitted from `/turbo` in visible Codex preview. The app generated the reaction automatically from Una's image, with no separate generation action. First displayed frame: **14.490 seconds** after sending the message — the **3–5 second target was not met**. Breakdown: OpenRouter 3.534 s; reply received by the browser at 3.552 s; fal round trip 6.698 s; video delivery/decoding/display 4.241 s. Provider execution was 4.975 s, while reported denoising alone was 0.500 s. These provider timings do not represent the user-visible response time.

Fal request `01a18271-2d3d-71e2-9705-a99cfacc9143`: status 200, confirmed cost **$0.08125**, output 5.184 s, 2,385,157 bytes. One paid generation, no retries. The server journal is `output/turbo-jobs/5e0b1319ab34270906e29c7e02965e65.json`.

The Turbo player now removes the edge-connected black background in a transparent canvas, preserving enclosed dark details, and loops the saved clip until paused or a new message arrives. This is playback processing; the source MP4 still has its original background. Pause/resume and repeated playback were verified in Codex preview using the existing result, without another paid request. The original 14.490-second measurement remains unchanged. New frames are measured after canvas rendering. Repeated playback does not incur generation fees.
