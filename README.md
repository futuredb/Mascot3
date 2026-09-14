# Маскот 3

Отдельное бюджетное Android-приложение с тем же Compose UI, что у `mascot_2`, и строгими словарями, референсами и промптами из `pet_generation_v2.5`.

## Профиль генерации

- отдельный Android package: `app.mascot3`;
- отдельный backend на Node.js: локально `http://127.0.0.1:8788`, production через HTTPS;
- один фронтальный canonical вместо трёх ракурсов;
- строгий canonical-промпт v2.5 остаётся без изменений;
- платный AI-review canonical отключён, но локальная проверка кадрирования и ручное принятие героя сохранены;
- одна попытка canonical без автоматического платного повтора;
- все 17 включённых анимаций строгого каталога v2.5;
- `sleep` переиспользуется клиентом как `sleep_loop`, поэтому восемнадцатое видео не оплачивается;
- video model: `bytedance/seedance-2.0-mini`, 720p, без аудио;
- лимит анимаций: `$9`; общий плановый лимит героя: `$11`.

При текущем профиле видео занимают 112 секунд. Оценка по заложенной ставке `$0.076125/с` — `$8.526`. Ещё `$1.75` зарезервировано под lore, character design и canonical image; плановая верхняя оценка героя — `$10.276`, жёсткий общий лимит — `$11`.

## Безопасность стоимости

Платная генерация героя начинается только после стука в UI. Видео запускаются отдельно после принятия героя и подтверждения «Оживить». Перед отправкой видеозадач весь пакет из 17 роликов проверяется против лимита. Точный набор незавершённых анимаций и batch-каталог сохраняются до запуска; provider job ID сохраняются до polling, поэтому повторное открытие приложения продолжает тот же пакет и не создаёт дубликат оплаченного ролика.

Ключ OpenRouter хранится только в server-side `.env`, исключённом из Git, и не попадает в командный APK. Публичный beta API дополнительно защищён отдельным клиентским токеном, дневным лимитом запусков и idempotency key для платного создания героя.

## Запуск

```bash
npm install
npm start
```

Для production задайте `MASCOT3_DATA_DIR=/data`, `MASCOT3_CLIENT_TOKEN`,
`OPENROUTER_API_KEY` и лимиты из `.env.example`. Каталог `/data` должен быть
persistent volume: в нём атомарно сохраняются библиотека героев, run/job state,
idempotency keys и бюджетный ledger. При первом запуске два проверенных героя
копируются из `deploy/seed`, но существующий volume никогда не перезаписывается.

## Docker / Coolify

Контейнер слушает только внутренний порт `8788`, работает непривилегированным
пользователем и имеет независимый `/health`. На VPS host port публиковать не
нужно: HTTPS терминируется в Coolify/Traefik.

```bash
docker compose config
docker compose build
docker compose up -d
```

Production health: `GET /health`. Более подробный `GET /api/health` также
показывает готовность ключа и клиентской авторизации, но не раскрывает секреты.

## Android

```bash
./android/gradlew -p android assembleDebug \
  -PMASCOT3_SERVER_URL=https://mascot3.45-91-52-137.sslip.io \
  -PMASCOT3_CLIENT_TOKEN=<beta-client-token>
```

Для физического телефона включите туннель:

```bash
adb reverse tcp:8788 tcp:8788
```

`OPENROUTER_API_KEY` в обычную server-backed сборку не встраивается. Старый
автономный режим можно собрать только явным параметром
`-PMASCOT3_EMBED_OPENROUTER_KEY=true`; для распространения такой режим не
используется.

## Проверки без платных запросов

```bash
npm test
npm run verify
node pipeline/run_character_pipeline.js --dry-run
node pipeline/scripts/character_cli.js animate \
  --character=/path/to/approved/character \
  --all-enabled \
  --batch-dir=mascot3-mini-full-v43 \
  --video-model=bytedance/seedance-2.0-mini \
  --resolution=720p \
  --price-per-second-usd=0.076125 \
  --max-cost-usd=9
```

## Rollback и данные

Откат к предыдущему image выполняется только для ресурса Mascot 3 в Coolify.
Persistent volume `/data` не удаляется и не откатывается вместе с кодом. Перед
изменением формата данных требуется отдельная миграция и резервная копия.
