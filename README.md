# Маскот 3

Отдельное бюджетное Android-приложение с тем же Compose UI, что у `mascot_2`, и строгими словарями, референсами и промптами из `pet_generation_v2.5`.

## Профиль генерации

- отдельный Android package: `app.mascot3`;
- отдельный backend на Node.js: локально `http://127.0.0.1:8788`, production через HTTPS;
- один фронтальный canonical вместо трёх ракурсов;
- строгий canonical-промпт v2.5 остаётся без изменений;
- платный AI-review canonical отключён, но локальная проверка кадрирования и ручное принятие героя сохранены;
- одна попытка canonical без автоматического платного повтора;
- четыре видео: `idle`, `happy`, `sleep`, `playful`;
- `sleep` переиспользуется клиентом как `sleep_loop`, поэтому пятое видео не оплачивается;
- video model: `bytedance/seedance-2.0-mini`, 720p, без аудио;
- лимит анимаций: `$3`; общий плановый лимит героя: `$5`.

При текущем профиле видео занимают 28 секунд. Оценка по заложенной ставке `$0.076125/с` — `$2.1315`. Остаток общего лимита зарезервирован под lore, character design и canonical image.

## Безопасность стоимости

Платная генерация героя начинается только после стука в UI. Видео запускаются отдельно после принятия героя и подтверждения «Оживить». Перед отправкой видеозадач весь четырёхроликовый пакет проверяется против лимита. Job ID сохраняются до polling, поэтому повторное открытие приложения не создаёт дубликат оплаченного ролика.

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
  --animations=idle,happy,sleep,playful \
  --batch-dir=mascot3-core
```

## Rollback и данные

Откат к предыдущему image выполняется только для ресурса Mascot 3 в Coolify.
Persistent volume `/data` не удаляется и не откатывается вместе с кодом. Перед
изменением формата данных требуется отдельная миграция и резервная копия.
