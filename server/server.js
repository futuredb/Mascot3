const http = require("node:http");
const fs = require("node:fs");
const fsp = require("node:fs/promises");
const path = require("node:path");
const { spawn, spawnSync } = require("node:child_process");
const crypto = require("node:crypto");
const { loadEnvFileDefaults } = require("../extensions/02_tools_and_apps/_shared/env_file.js");

const ROOT = path.resolve(__dirname, "..");
const PIPELINE = path.join(ROOT, "pipeline");
const PUBLIC = path.join(ROOT, "public");
const env = loadEnvFileDefaults({ env: process.env, workspaceRoot: ROOT });
const port = Number(env.MASCOT3_PORT || 8788);
const DATA_ROOT = env.MASCOT3_DATA_DIR ? path.resolve(env.MASCOT3_DATA_DIR) : null;
const CHARACTERS_DIR = DATA_ROOT ? path.join(DATA_ROOT, "characters") : path.join(PIPELINE, "characters");
const RUNS_DIR = DATA_ROOT ? path.join(DATA_ROOT, "runs") : path.join(PIPELINE, "runs");
const STATE_DIR = DATA_ROOT ? path.join(DATA_ROOT, "_state") : path.join(PIPELINE, "_state");
const SERVER_DATA_DIR = DATA_ROOT ? path.join(DATA_ROOT, "server-data") : path.join(ROOT, "server-data");
const JOBS_DIR = path.join(SERVER_DATA_DIR, "jobs");
const MASCOTS_FILE = path.join(SERVER_DATA_DIR, "mascots.json");
const IDEMPOTENCY_FILE = path.join(SERVER_DATA_DIR, "idempotency.json");
const BUDGET_LEDGER_FILE = path.join(SERVER_DATA_DIR, "budget-ledger.json");
const SEED_ROOT = path.join(ROOT, "deploy", "seed");
const ANIMATION_CATALOG = JSON.parse(fs.readFileSync(path.join(PIPELINE, "dict", "animation_prompt_catalog.json"), "utf8"));
const ENABLED_ANIMATION_ENTRIES = ANIMATION_CATALOG.entries.filter((entry) => entry.availability === "enabled");
const UI_TO_PIPELINE_ANIMATION = Object.freeze({
  idle: "idle",
  resting: "rest",
  sleeping: "sleep",
  thinking: "thinking",
  at_glass: "at_glass",
  watching: "watching",
  joyful: "happy",
  sad: "sad",
  angry: "angry",
  refusal: "refusal",
  frightened: "frightened",
  curious: "curious",
  tender: "tender",
  stretching: "stretch",
  greeting: "greeting",
  signature_move: "signature_move",
  dancing: "playful",
  // A local playback derivative of `sleep`; it is never a separate paid job.
  sleep_loop: "sleep",
});
const LIBRARY_UI_STATES = Object.keys(UI_TO_PIPELINE_ANIMATION).filter((state) => state !== "sleep_loop");
const ENABLED_ANIMATION_IDS = ENABLED_ANIMATION_ENTRIES.map((entry) => entry.id);
if (LIBRARY_UI_STATES.length !== 17 || ENABLED_ANIMATION_IDS.join(",") !== LIBRARY_UI_STATES.map((state) => UI_TO_PIPELINE_ANIMATION[state]).join(",")) {
  throw new Error("Mascot 3 UI animation mapping is out of sync with the enabled v2.5 catalog");
}
const runtimeEnv = {
  ...env,
  PET_V2_DATA_DIR: DATA_ROOT || PIPELINE,
};
const PROFILE = Object.freeze({
  id: "pet_generation_v2.5_mini_full17",
  videoModel: "bytedance/seedance-2.0-mini",
  videoResolution: "720p",
  pipelineAnimations: ENABLED_ANIMATION_IDS,
  uiStates: LIBRARY_UI_STATES,
  totalVideoSeconds: ENABLED_ANIMATION_ENTRIES.reduce((total, entry) => total + entry.duration_seconds, 0),
  estimatedVideoCostUsd: Number((ENABLED_ANIMATION_ENTRIES.reduce((total, entry) => total + entry.duration_seconds, 0) * Number(env.MASCOT3_VIDEO_PRICE_PER_SECOND_USD || 0.076125)).toFixed(4)),
  maxHeroCostUsd: Number(env.MASCOT3_MAX_HERO_COST_USD || 11),
  maxAnimationCostUsd: Number(env.MASCOT3_MAX_ANIMATION_COST_USD || 9),
  baseCostReserveUsd: Number(env.MASCOT3_BASE_COST_RESERVE_USD || 1.75),
  videoPricePerSecondUsd: Number(env.MASCOT3_VIDEO_PRICE_PER_SECOND_USD || 0.076125),
});
const jobs = new Map();
const mascots = new Map();
const idempotencyKeys = new Map();
const budgetLedger = [];
const animationWorkers = new Map();
let mascotSaveQueue = Promise.resolve();
let idempotencySaveQueue = Promise.resolve();
let budgetSaveQueue = Promise.resolve();

function seedDataDirectory() {
  if (!DATA_ROOT) return;
  for (const directory of [CHARACTERS_DIR, RUNS_DIR, STATE_DIR, SERVER_DATA_DIR, JOBS_DIR]) {
    fs.mkdirSync(directory, { recursive: true });
  }
  if (env.MASCOT3_SKIP_SEED === "true") return;
  const seedCharacters = path.join(SEED_ROOT, "characters");
  if (fs.existsSync(seedCharacters) && fs.readdirSync(CHARACTERS_DIR).length === 0) {
    fs.cpSync(seedCharacters, CHARACTERS_DIR, { recursive: true });
  }
  const seedMascots = path.join(SEED_ROOT, "mascots.json");
  if (!fs.existsSync(MASCOTS_FILE) && fs.existsSync(seedMascots)) {
    fs.copyFileSync(seedMascots, MASCOTS_FILE);
  }
}

function normalizeMascotPaths(mascot) {
  const characterSlug = mascot.characterSlug || path.basename(mascot.characterDir || "");
  const characterDir = characterSlug ? path.join(CHARACTERS_DIR, characterSlug) : null;
  const previewName = path.basename(mascot.previewPath || "anchor_00.png");
  const previewPath = characterDir ? path.join(characterDir, "canonical", previewName) : null;
  const runSlug = path.basename(mascot.runDir || "");
  return {
    ...mascot,
    characterSlug: characterSlug || null,
    characterDir: characterDir && fs.existsSync(characterDir) ? characterDir : null,
    previewPath: previewPath && fs.existsSync(previewPath) ? previewPath : null,
    runDir: runSlug ? path.join(RUNS_DIR, runSlug) : null,
  };
}

seedDataDirectory();
if (fs.existsSync(MASCOTS_FILE)) {
  for (const mascot of JSON.parse(fs.readFileSync(MASCOTS_FILE, "utf8"))) {
    const normalized = normalizeMascotPaths(mascot);
    mascots.set(normalized.id, normalized);
  }
}
if (fs.existsSync(IDEMPOTENCY_FILE)) {
  for (const [key, mascotId] of Object.entries(JSON.parse(fs.readFileSync(IDEMPOTENCY_FILE, "utf8")))) {
    idempotencyKeys.set(key, mascotId);
  }
}
if (fs.existsSync(BUDGET_LEDGER_FILE)) {
  budgetLedger.push(...JSON.parse(fs.readFileSync(BUDGET_LEDGER_FILE, "utf8")));
}

function send(res, status, body, type = "application/json; charset=utf-8") {
  const data = type.startsWith("application/json") ? JSON.stringify(body) : body;
  res.writeHead(status, { "Content-Type": type, "Content-Length": Buffer.byteLength(data), "Cache-Control": "no-store" });
  res.end(data);
}

async function readBody(req) {
  const chunks = [];
  let size = 0;
  for await (const chunk of req) {
    size += chunk.length;
    if (size > 128 * 1024) throw Object.assign(new Error("request_too_large"), { status: 413 });
    chunks.push(chunk);
  }
  return chunks.length ? JSON.parse(Buffer.concat(chunks).toString("utf8")) : {};
}

function secureEqual(left, right) {
  const a = Buffer.from(String(left || ""));
  const b = Buffer.from(String(right || ""));
  return a.length === b.length && crypto.timingSafeEqual(a, b);
}

function requireClientAuth(req) {
  const expected = String(env.MASCOT3_CLIENT_TOKEN || "").trim();
  if (!expected) return;
  const supplied = req.headers["x-mascot-client-token"];
  if (!secureEqual(supplied, expected)) {
    throw Object.assign(new Error("unauthorized"), { status: 401 });
  }
}

function idempotencyKey(req) {
  return cleanText(req.headers["idempotency-key"], 100, "idempotency_key");
}

function cleanText(value, max, field, required = true) {
  const text = String(value || "").trim();
  if (required && !text) throw Object.assign(new Error(`${field}_required`), { status: 400 });
  if (text.length > max) throw Object.assign(new Error(`${field}_too_long`), { status: 400 });
  return text;
}

function pipelineArgs(input, submit) {
  const subject = cleanText(input.subject, 80, "subject");
  const brief = cleanText(input.brief, 600, "brief", false);
  const args = [path.join(PIPELINE, "scripts", "character_cli.js"), "create", `--subject=${subject}`];
  if (brief) args.push(`--brief=${brief}`);
  if (input.name) args.push(`--name=${cleanText(input.name, 50, "name")}`);
  if (["living", "object"].includes(input.subjectKind)) args.push(`--subject-kind=${input.subjectKind}`);
  if (submit) args.push("--submit=yes", "--direct-openrouter=yes");
  return args;
}

function publicJob(job) {
  return {
    id: job.id,
    status: job.status,
    stage: job.stage,
    createdAt: job.createdAt,
    finishedAt: job.finishedAt || null,
    input: job.input,
    result: job.result || null,
    error: job.error || null,
  };
}

async function saveJob(job) {
  await fsp.mkdir(JOBS_DIR, { recursive: true });
  await writeJsonAtomic(path.join(JOBS_DIR, `${job.id}.json`), publicJob(job));
}

async function saveMascots() {
  const snapshot = [...mascots.values()];
  mascotSaveQueue = mascotSaveQueue.then(() => writeJsonAtomic(MASCOTS_FILE, snapshot));
  await mascotSaveQueue;
}

async function saveIdempotencyKeys() {
  const snapshot = Object.fromEntries(idempotencyKeys);
  idempotencySaveQueue = idempotencySaveQueue.then(() => writeJsonAtomic(IDEMPOTENCY_FILE, snapshot));
  await idempotencySaveQueue;
}

async function recordPaidHeroStart(mascotId) {
  const cutoff = Date.now() - 24 * 60 * 60 * 1000;
  const recent = budgetLedger.filter((entry) => Date.parse(entry.startedAt) >= cutoff);
  const limit = Number(env.MASCOT3_MAX_HERO_STARTS_PER_DAY || 5);
  if (!Number.isInteger(limit) || limit < 1 || recent.length >= limit) {
    throw Object.assign(new Error("daily_generation_limit_reached"), { status: 429 });
  }
  budgetLedger.splice(0, budgetLedger.length, ...recent, { mascotId, startedAt: new Date().toISOString() });
  const snapshot = [...budgetLedger];
  budgetSaveQueue = budgetSaveQueue.then(() => writeJsonAtomic(BUDGET_LEDGER_FILE, snapshot));
  await budgetSaveQueue;
}

async function writeJsonAtomic(file, value) {
  await fsp.mkdir(path.dirname(file), { recursive: true });
  const pending = `${file}.${process.pid}.${crypto.randomUUID()}.pending`;
  await fsp.writeFile(pending, `${JSON.stringify(value, null, 2)}\n`, { mode: 0o600 });
  await fsp.rename(pending, file);
}

function mascotDto(mascot) {
  const fullAnimationPackReady = PROFILE.pipelineAnimations.every((id) => Boolean(animationSource(mascot, id)));
  const stages = { ...(mascot.stages || {}) };
  if (!fullAnimationPackReady && stages.animations === "ready") {
    stages.animations = PROFILE.pipelineAnimations.some((id) => Boolean(animationSource(mascot, id))) ? "partial" : "pending";
  }
  return {
    id: mascot.id,
    name: mascot.name || null,
    status: mascot.status,
    reroll_used: 0,
    reroll_limit: 1,
    prompt_version: PROFILE.id,
    stages,
    preview_url: mascot.previewPath ? `/assets/${mascot.id}/base.png` : null,
    preview_animation_url: null,
  };
}

async function createNativeMascot(requestKey) {
  if (!env.OPENROUTER_API_KEY) throw Object.assign(new Error("openrouter_api_key_missing"), { status: 503 });
  const existingId = idempotencyKeys.get(requestKey);
  if (existingId && mascots.has(existingId)) return mascotDto(mascots.get(existingId));
  const id = crypto.randomUUID();
  const mascot = {
    id,
    name: null,
    status: "BASE_GENERATING",
    createdAt: new Date().toISOString(),
    generationProfile: PROFILE.id,
    maxHeroCostUsd: PROFILE.maxHeroCostUsd,
    stages: { deal: "running", lore: "pending", design: "pending", canonical: "pending" },
  };
  mascots.set(id, mascot);
  try {
    await recordPaidHeroStart(id);
  } catch (error) {
    mascots.delete(id);
    throw error;
  }
  idempotencyKeys.set(requestKey, id);
  await saveIdempotencyKeys();
  await saveMascots();
  const args = [
    path.join(PIPELINE, "run_character_pipeline.js"),
    "--through=canonical",
    "--anchor-limit=1",
    "--canonical-attempts=1",
    "--skip-canonical-ai-review",
    "--direct-openrouter=yes",
  ];
  const child = spawn(process.execPath, args, { cwd: PIPELINE, env: runtimeEnv, stdio: ["ignore", "pipe", "pipe"] });
  let stdout = "";
  let stderr = "";
  child.stdout.on("data", (chunk) => { stdout += chunk; });
  child.stderr.on("data", (chunk) => { stderr += chunk; });
  child.on("close", async (code) => {
    if (code !== 0) {
      mascot.status = "FAILED_FINAL";
      mascot.error = (stderr || stdout || `pipeline_exit_${code}`).trim().slice(-5000);
      mascot.stages = { ...mascot.stages, deal: "done", lore: "failed" };
      return saveMascots();
    }
    try {
      const result = JSON.parse(stdout);
      const characterDir = path.resolve(result.characterDir);
      if (!characterDir.startsWith(`${CHARACTERS_DIR}${path.sep}`)) throw new Error("pipeline_character_path_invalid");
      const lore = JSON.parse(await fsp.readFile(path.join(characterDir, "lore.json"), "utf8"));
      const canonical = JSON.parse(await fsp.readFile(path.join(characterDir, "canonical", "manifest.json"), "utf8"));
      const previewPath = path.resolve(canonical.anchors?.[0] || "");
      if (!previewPath.startsWith(`${characterDir}${path.sep}`) || !fs.existsSync(previewPath)) throw new Error("canonical_preview_missing");
      Object.assign(mascot, {
        name: lore.identity?.name || null,
        status: "AWAITING_ACCEPTANCE",
        characterSlug: path.basename(characterDir),
        characterDir,
        previewPath,
        runDir: result.runDir,
        stages: { deal: "done", lore: "done", design: "done", canonical: "review" },
      });
    } catch (error) {
      mascot.status = "FAILED_FINAL";
      mascot.error = error.message;
    }
    await saveMascots();
  });
  return mascotDto(mascot);
}

function runNode(args, cwd = PIPELINE) {
  return new Promise((resolve, reject) => {
    const child = spawn(process.execPath, args, { cwd, env: runtimeEnv, stdio: ["ignore", "pipe", "pipe"] });
    let stdout = "";
    let stderr = "";
    child.stdout.on("data", (chunk) => { stdout += chunk; });
    child.stderr.on("data", (chunk) => { stderr += chunk; });
    child.on("close", (code) => code === 0 ? resolve(stdout) : reject(new Error((stderr || stdout || `pipeline_exit_${code}`).trim())));
  });
}

async function acceptNativeMascot(mascot) {
  if (!mascot.characterDir || mascot.status !== "AWAITING_ACCEPTANCE") {
    if (mascot.status === "READY") return mascotDto(mascot);
    throw Object.assign(new Error("canonical_not_ready"), { status: 409 });
  }
  await runNode([path.join(PIPELINE, "scripts", "character_cli.js"), "approve", `--character=${mascot.characterDir}`, "--approve=yes"]);
  mascot.status = "READY";
  mascot.acceptedAt = new Date().toISOString();
  mascot.stages.canonical = "approved";
  for (const other of mascots.values()) if (other.id !== mascot.id && other.status === "READY") other.active = false;
  mascot.active = true;
  await saveMascots();
  return mascotDto(mascot);
}

function sha256File(file) {
  return crypto.createHash("sha256").update(fs.readFileSync(file)).digest("hex");
}

function presentationPreviewPath(mascot) {
  const source = mascot.previewPath;
  if (!source || !fs.existsSync(source)) return null;
  const output = path.join(path.dirname(source), "display_preview.png");
  const sourceStat = fs.statSync(source);
  if (fs.existsSync(output) && fs.statSync(output).mtimeMs >= sourceStat.mtimeMs) return output;
  const matte = "chromakey=0x00FF00:0.25:0,format=rgba,despill=green:mix=1:expand=0,split[fg][masksrc];[masksrc]alphaextract,gblur=sigma=0.7[alpha];[fg][alpha]alphamerge,format=rgba";
  const result = spawnSync("ffmpeg", [
    "-loglevel", "error", "-y", "-i", source,
    "-filter_complex", matte,
    "-frames:v", "1", output,
  ], { encoding: "utf8", timeout: 120_000 });
  if (result.status !== 0 || !fs.existsSync(output)) {
    console.error(`transparent_preview_failed: ${(result.stderr || result.error?.message || "unknown").trim()}`);
    return source;
  }
  return output;
}

function animationSource(mascot, pipelineId) {
  if (!mascot.characterDir) return null;
  const batchDirs = [
    mascot.animationBatchDir,
    `mascot3-mini-full-v${ANIMATION_CATALOG.version}`,
    `mascot3-mini-completion-v${ANIMATION_CATALOG.version}`,
    "mascot3-core",
  ].filter((value, index, values) => value && values.indexOf(value) === index);
  for (const batchDir of batchDirs) {
    const loopDir = path.join(mascot.characterDir, "animation", batchDir, pipelineId, "loop");
    if (!fs.existsSync(loopDir)) continue;
    const name = fs.readdirSync(loopDir).find((entry) => /^source_video\.[a-z0-9]+$/i.test(entry));
    if (name) return path.join(loopDir, name);
  }
  return null;
}

function videoDurationSeconds(source) {
  const result = spawnSync("ffprobe", [
    "-v", "error",
    "-show_entries", "format=duration",
    "-of", "default=noprint_wrappers=1:nokey=1",
    source,
  ], { encoding: "utf8", timeout: 30_000 });
  const duration = Number.parseFloat(result.stdout || "");
  return result.status === 0 && Number.isFinite(duration) ? duration : null;
}

/**
 * Keep paid provider output immutable and derive playback-only videos locally.
 * Normal actions use a forward/backward cycle cut from the useful first half,
 * so the final frame is the initial frame exactly.  Sleep gets a separate,
 * short closed-pose breathing loop whose endpoints are the same source frame.
 */
function presentationVideoPath(mascot, state) {
  const source = animationSource(mascot, UI_TO_PIPELINE_ANIMATION[state]);
  if (!source || state === "sleeping") return source;
  const duration = videoDurationSeconds(source);
  if (!duration) return source;
  const outputName = state === "sleep_loop" ? "presentation_sleep_loop.mp4" : "presentation_seamless.mp4";
  const output = path.join(path.dirname(source), outputName);
  const sourceStat = fs.statSync(source);
  if (fs.existsSync(output) && fs.statSync(output).mtimeMs >= sourceStat.mtimeMs) return output;

  const pending = `${output}.pending.mp4`;
  const filter = state === "sleep_loop"
    ? (() => {
      const end = Math.max(1, duration - 0.75);
      const start = Math.max(0, end - 1.25);
      return `[0:v]trim=start=${start.toFixed(3)}:end=${end.toFixed(3)},setpts=PTS-STARTPTS,split=2[forward][reverse_input];` +
        "[reverse_input]reverse,setpts=PTS-STARTPTS[backward];" +
        "[backward][forward]concat=n=2:v=1:a=0,format=yuv420p[out]";
    })()
    : (() => {
      const midpoint = Math.max(1, duration / 2);
      return `[0:v]trim=start=0:end=${midpoint.toFixed(3)},setpts=PTS-STARTPTS,split=2[forward][reverse_input];` +
        "[reverse_input]reverse,setpts=PTS-STARTPTS[backward];" +
        "[forward][backward]concat=n=2:v=1:a=0,format=yuv420p[out]";
    })();
  const result = spawnSync("ffmpeg", [
    "-loglevel", "error", "-y", "-i", source,
    "-filter_complex", filter,
    "-map", "[out]", "-an",
    "-c:v", "libx264", "-preset", "medium", "-crf", "18",
    "-movflags", "+faststart", pending,
  ], { encoding: "utf8", timeout: 300_000 });
  if (result.status !== 0 || !fs.existsSync(pending)) {
    if (fs.existsSync(pending)) fs.unlinkSync(pending);
    console.error(`seamless_video_failed(${state}): ${(result.stderr || result.error?.message || "unknown").trim()}`);
    return source;
  }
  fs.renameSync(pending, output);
  return output;
}

function animationPackDto(mascot) {
  const ready = PROFILE.uiStates.filter((state) => Boolean(animationSource(mascot, UI_TO_PIPELINE_ANIMATION[state])));
  const blocked = mascot.animationStatus === "failed"
    ? PROFILE.uiStates.filter((state) => !ready.includes(state))
    : [];
  const queued = blocked.length ? [] : PROFILE.uiStates.filter((state) => !ready.includes(state));
  return { mascot_id: mascot.id, kind: "video", pack: PROFILE.uiStates.length, requested: PROFILE.uiStates, ready, queued, blocked };
}

function videoAsset(mascot, state) {
  const source = presentationVideoPath(mascot, state);
  if (!source) return null;
  return {
    url: `/assets/${mascot.id}/videos/${state}.mp4`,
    sha256: sha256File(source),
    mime_type: "video/mp4",
    format: "mp4",
  };
}

const wait = (milliseconds) => new Promise((resolve) => setTimeout(resolve, milliseconds));

async function runAnimationBatch(mascot) {
  const batchIds = mascot.animationBatchIds;
  if (!Array.isArray(batchIds) || !batchIds.length || !mascot.animationBatchDir) {
    throw new Error("animation_batch_plan_missing");
  }
  const args = [
    path.join(PIPELINE, "scripts", "character_cli.js"),
    "animate",
    `--character=${mascot.characterDir}`,
    `--animations=${batchIds.join(",")}`,
    `--batch-dir=${mascot.animationBatchDir}`,
    "--submit=yes",
    "--allow-pending-canonical=yes",
    `--video-model=${PROFILE.videoModel}`,
    `--resolution=${PROFILE.videoResolution}`,
    `--price-per-second-usd=${PROFILE.videoPricePerSecondUsd}`,
    `--max-cost-usd=${PROFILE.maxAnimationCostUsd}`,
  ];
  mascot.animationStatus = "running";
  mascot.stages = { ...mascot.stages, animations: "running" };
  await saveMascots();
  try {
    for (let poll = 0; poll < 480; poll += 1) {
      const raw = await runNode(args);
      const result = JSON.parse(raw);
      mascot.animationEstimateUsd = result.estimated_total_usd;
      if (result.estimated_total_usd > PROFILE.maxAnimationCostUsd) {
        throw new Error("animation_budget_exceeded");
      }
      if (PROFILE.pipelineAnimations.every((id) => Boolean(animationSource(mascot, id)))) {
        mascot.animationStatus = "ready";
        mascot.stages = { ...mascot.stages, animations: "ready" };
        await saveMascots();
        return;
      }
      await wait(15_000);
    }
    throw new Error("animation_generation_timeout");
  } catch (error) {
    mascot.animationStatus = "failed";
    mascot.animationError = String(error.message || error).slice(-3000);
    mascot.stages = { ...mascot.stages, animations: "failed" };
    await saveMascots();
  } finally {
    animationWorkers.delete(mascot.id);
  }
}

async function startAnimationBatch(mascot) {
  if (mascot.status !== "READY" || !mascot.characterDir) {
    throw Object.assign(new Error("canonical_not_approved"), { status: 409 });
  }
  if (!Number.isFinite(PROFILE.maxHeroCostUsd) || PROFILE.maxHeroCostUsd <= 0 || PROFILE.maxAnimationCostUsd > PROFILE.maxHeroCostUsd) {
    throw Object.assign(new Error("invalid_cost_budget"), { status: 500 });
  }
  const animationEstimate = PROFILE.estimatedVideoCostUsd;
  if (PROFILE.baseCostReserveUsd + animationEstimate > PROFILE.maxHeroCostUsd) {
    throw Object.assign(new Error("hero_budget_exceeded"), { status: 409 });
  }
  if (!animationWorkers.has(mascot.id) && animationPackDto(mascot).ready.length < PROFILE.uiStates.length) {
    const persistedPlanIsValid = Array.isArray(mascot.animationBatchIds)
      && mascot.animationBatchIds.length > 0
      && mascot.animationBatchIds.every((id) => PROFILE.pipelineAnimations.includes(id))
      && typeof mascot.animationBatchDir === "string"
      && /^mascot3-mini-[a-z0-9-]+$/i.test(mascot.animationBatchDir);
    if (!persistedPlanIsValid) {
      const missing = PROFILE.pipelineAnimations.filter((id) => !animationSource(mascot, id));
      mascot.animationBatchIds = missing;
      mascot.animationBatchDir = missing.length === PROFILE.pipelineAnimations.length
        ? `mascot3-mini-full-v${ANIMATION_CATALOG.version}`
        : `mascot3-mini-completion-v${ANIMATION_CATALOG.version}`;
      // Persist the exact paid set before the worker starts. After a process or
      // network interruption, the same batch.json and provider job IDs resume.
      await saveMascots();
    }
    const worker = runAnimationBatch(mascot);
    animationWorkers.set(mascot.id, worker);
  }
  return animationPackDto(mascot);
}

function nativeManifest(mascot) {
  const previewPath = presentationPreviewPath(mascot);
  const base = previewPath
    ? { url: `/assets/${mascot.id}/base.png`, sha256: sha256File(previewPath), mime_type: "image/png", format: "png" }
    : null;
  const manifestStates = [...PROFILE.uiStates, "sleep_loop"];
  const states = Object.fromEntries(manifestStates.map((state) => {
    const animation = videoAsset(mascot, state);
    return [state, { still: null, animation, sequence: null, status: animation ? "ready" : "pending" }];
  }));
  return { mascot_id: mascot.id, version: 1, base, base_sequence: null, states, expires_at: null };
}

function nativeContext(mascot) {
  return {
    state_key: "idle",
    reason_code: PROFILE.id,
    reason_text: "Строгий герой v2.5, полный профиль из 17 анимаций на Mini",
    city_name: "Москва",
    still_url: mascot?.previewPath ? `/assets/${mascot.id}/base.png` : null,
    animation_url: mascot ? videoAsset(mascot, "idle")?.url || null : null,
    animation_frames: [],
    animation_fps: 6,
    playback_mode: "idle_pulse",
    motion_ms: 7000,
    pause_ms: 50000,
  };
}

async function createJob(input) {
  if (!input.confirmPaid) throw Object.assign(new Error("paid_generation_confirmation_required"), { status: 409 });
  if (!env.OPENROUTER_API_KEY) throw Object.assign(new Error("openrouter_api_key_missing"), { status: 503 });
  const id = crypto.randomUUID();
  const job = { id, status: "queued", stage: "deal", createdAt: new Date().toISOString(), input: {
    subject: cleanText(input.subject, 80, "subject"),
    brief: cleanText(input.brief, 600, "brief", false),
    name: cleanText(input.name, 50, "name", false),
    subjectKind: ["living", "object"].includes(input.subjectKind) ? input.subjectKind : "living",
  } };
  jobs.set(id, job);
  await saveJob(job);
  const child = spawn(process.execPath, pipelineArgs(job.input, true), { cwd: PIPELINE, env: runtimeEnv, stdio: ["ignore", "pipe", "pipe"] });
  job.status = "running";
  job.stage = "lore";
  let stdout = "";
  let stderr = "";
  child.stdout.on("data", (chunk) => { stdout += chunk; if (stdout.length > 2_000_000) stdout = stdout.slice(-2_000_000); });
  child.stderr.on("data", (chunk) => { stderr += chunk; if (stderr.length > 200_000) stderr = stderr.slice(-200_000); });
  child.on("close", async (code) => {
    job.finishedAt = new Date().toISOString();
    if (code === 0) {
      job.status = "review_required";
      job.stage = "canonical";
      try { job.result = JSON.parse(stdout); } catch { job.result = { output: stdout.trim() }; }
    } else {
      job.status = "failed";
      job.error = (stderr || stdout || `pipeline_exit_${code}`).trim().slice(-5000);
    }
    await saveJob(job);
  });
  return publicJob(job);
}

async function plan(input) {
  return new Promise((resolve, reject) => {
    const child = spawn(process.execPath, pipelineArgs(input, false), { cwd: PIPELINE, env: runtimeEnv, stdio: ["ignore", "pipe", "pipe"] });
    let stdout = "";
    let stderr = "";
    child.stdout.on("data", (chunk) => { stdout += chunk; });
    child.stderr.on("data", (chunk) => { stderr += chunk; });
    child.on("close", (code) => {
      if (code !== 0) return reject(new Error(stderr || stdout || `pipeline_exit_${code}`));
      try { resolve(JSON.parse(stdout)); } catch { resolve({ status: "dry-run", output: stdout.trim() }); }
    });
  });
}

async function characters() {
  const root = CHARACTERS_DIR;
  await fsp.mkdir(root, { recursive: true });
  const entries = await fsp.readdir(root, { withFileTypes: true });
  const result = [];
  for (const entry of entries.filter((item) => item.isDirectory())) {
    const dir = path.join(root, entry.name);
    try {
      const lore = JSON.parse(await fsp.readFile(path.join(dir, "lore.json"), "utf8"));
      const manifest = JSON.parse(await fsp.readFile(path.join(dir, "canonical", "manifest.json"), "utf8"));
      const anchor = manifest.anchors?.[0];
      result.push({
        id: entry.name,
        name: lore.identity?.name || entry.name,
        subject: lore.identity?.subject || "",
        status: manifest.status,
        previewUrl: anchor ? `/files/${path.relative(PIPELINE, anchor).split(path.sep).map(encodeURIComponent).join("/")}` : null,
      });
    } catch { /* incomplete output stays outside the library */ }
  }
  return result;
}

function serveFile(res, root, requestPath) {
  const decoded = requestPath.split("/").map(decodeURIComponent).join(path.sep);
  const target = path.resolve(root, decoded);
  if (target !== root && !target.startsWith(`${root}${path.sep}`)) return send(res, 403, { error: "forbidden" });
  if (!fs.existsSync(target) || !fs.statSync(target).isFile()) return send(res, 404, { error: "not_found" });
  const ext = path.extname(target).toLowerCase();
  const types = { ".html": "text/html; charset=utf-8", ".css": "text/css; charset=utf-8", ".js": "text/javascript; charset=utf-8", ".png": "image/png", ".webp": "image/webp", ".jpg": "image/jpeg", ".jpeg": "image/jpeg", ".mp4": "video/mp4", ".json": "application/json; charset=utf-8" };
  res.writeHead(200, { "Content-Type": types[ext] || "application/octet-stream" });
  fs.createReadStream(target).pipe(res);
}

const server = http.createServer(async (req, res) => {
  try {
    const url = new URL(req.url, `http://${req.headers.host || "localhost"}`);
    if (req.method === "GET" && url.pathname === "/api/health") return send(res, 200, {
      status: "ok",
      pipeline: PROFILE.id,
      paidReady: Boolean(env.OPENROUTER_API_KEY),
      clientAuthReady: Boolean(env.MASCOT3_CLIENT_TOKEN),
      profile: PROFILE,
    });
    if (req.method === "GET" && url.pathname === "/health") return send(res, 200, { status: "ok" });
    if (!["GET", "HEAD", "OPTIONS"].includes(req.method)) requireClientAuth(req);
    if (req.method === "PUT" && url.pathname === "/v1/profile/city") {
      const body = await readBody(req);
      return send(res, 200, { id: body.city_id || "524901", ...body });
    }
    if (req.method === "POST" && url.pathname === "/v1/mascots") return send(res, 202, await createNativeMascot(idempotencyKey(req)));
    if (req.method === "GET" && url.pathname === "/v1/mascots/current/active") {
      const active = [...mascots.values()].find((item) => item.active && item.status === "READY");
      return active ? send(res, 200, mascotDto(active)) : send(res, 404, { detail: "no_mascot" });
    }
    if (req.method === "GET" && url.pathname === "/v1/mascots/library") {
      return send(res, 200, [...mascots.values()].filter((item) => item.acceptedAt).map(mascotDto));
    }
    const mascotMatch = url.pathname.match(/^\/v1\/mascots\/([^/]+)(?:\/(accept|activate|replace|manifest))?$/);
    if (mascotMatch) {
      const mascot = mascots.get(mascotMatch[1]);
      if (!mascot) return send(res, 404, { detail: "mascot_not_found" });
      const action = mascotMatch[2];
      if (req.method === "GET" && !action) return send(res, 200, mascotDto(mascot));
      if (req.method === "PATCH" && !action) {
        const body = await readBody(req);
        mascot.name = cleanText(body.name, 50, "name", false) || mascot.name;
        await saveMascots();
        return send(res, 200, mascotDto(mascot));
      }
      if (req.method === "POST" && action === "accept") return send(res, 200, await acceptNativeMascot(mascot));
      if (req.method === "POST" && action === "activate") {
        for (const item of mascots.values()) item.active = item.id === mascot.id;
        mascot.status = "READY";
        await saveMascots();
        return send(res, 200, mascotDto(mascot));
      }
      if (req.method === "POST" && action === "replace") return send(res, 202, await createNativeMascot(idempotencyKey(req)));
      if (req.method === "GET" && action === "manifest") return send(res, 200, nativeManifest(mascot));
    }
    const videosMatch = url.pathname.match(/^\/v1\/mascots\/([^/]+)\/videos\/(\d+)$/);
    if (req.method === "POST" && videosMatch) {
      const mascot = mascots.get(videosMatch[1]);
      if (!mascot) return send(res, 404, { detail: "mascot_not_found" });
      if (Number(videosMatch[2]) !== PROFILE.uiStates.length) {
        return send(res, 400, { detail: `video_pack_must_be_${PROFILE.uiStates.length}` });
      }
      return send(res, 202, await startAnimationBatch(mascot));
    }
    if (req.method === "GET" && url.pathname === "/v1/context/current") {
      const current = [...mascots.values()].find((item) => item.active && item.status === "READY");
      return send(res, 200, nativeContext(current));
    }
    if (req.method === "POST" && url.pathname === "/v1/widgets") return send(res, 200, { status: "ok" });
    if (req.method === "DELETE" && url.pathname === "/v1/account") {
      if (env.MASCOT3_ALLOW_ACCOUNT_DELETE !== "true") {
        throw Object.assign(new Error("account_delete_disabled"), { status: 403 });
      }
      mascots.clear();
      await saveMascots();
      return send(res, 200, { status: "deleted" });
    }
    if (req.method === "GET" && url.pathname === "/api/characters") return send(res, 200, await characters());
    if (req.method === "POST" && url.pathname === "/api/generations/plan") return send(res, 200, await plan(await readBody(req)));
    if (req.method === "POST" && url.pathname === "/api/generations") {
      if (env.MASCOT3_ENABLE_LEGACY_API !== "true") {
        throw Object.assign(new Error("legacy_generation_api_disabled"), { status: 403 });
      }
      return send(res, 202, await createJob(await readBody(req)));
    }
    if (req.method === "GET" && url.pathname.startsWith("/api/jobs/")) {
      const id = url.pathname.slice("/api/jobs/".length);
      const job = jobs.get(id) || (fs.existsSync(path.join(JOBS_DIR, `${id}.json`)) ? JSON.parse(await fsp.readFile(path.join(JOBS_DIR, `${id}.json`), "utf8")) : null);
      return job ? send(res, 200, publicJob(job)) : send(res, 404, { error: "job_not_found" });
    }
    if (req.method === "GET" && url.pathname.startsWith("/files/")) return serveFile(res, PIPELINE, url.pathname.slice(7));
    const assetMatch = url.pathname.match(/^\/assets\/([^/]+)\/base\.png$/);
    if (req.method === "GET" && assetMatch) {
      const mascot = mascots.get(assetMatch[1]);
      const previewPath = mascot && presentationPreviewPath(mascot);
      if (!previewPath) return send(res, 404, { error: "not_found" });
      return serveFile(res, path.dirname(previewPath), path.basename(previewPath));
    }
    const videoAssetMatch = url.pathname.match(/^\/assets\/([^/]+)\/videos\/([a-z_]+)\.mp4$/);
    if (req.method === "GET" && videoAssetMatch) {
      const mascot = mascots.get(videoAssetMatch[1]);
      const state = videoAssetMatch[2];
      if (!Object.prototype.hasOwnProperty.call(UI_TO_PIPELINE_ANIMATION, state)) {
        return send(res, 404, { error: "not_found" });
      }
      const source = mascot && presentationVideoPath(mascot, state);
      if (!source) return send(res, 404, { error: "not_found" });
      return serveFile(res, path.dirname(source), path.basename(source));
    }
    const local = url.pathname === "/" ? "index.html" : url.pathname.slice(1);
    return serveFile(res, PUBLIC, local);
  } catch (error) {
    send(res, error.status || 500, { error: error.message || "internal_error" });
  }
});

server.listen(port, "0.0.0.0", () => {
  console.log(`Mascot 3 is running on port ${port}`);
  if (!env.OPENROUTER_API_KEY) return;
  for (const mascot of mascots.values()) {
    const shouldResume = mascot.status === "READY"
      && mascot.animationStatus === "running"
      && Array.isArray(mascot.animationBatchIds)
      && mascot.animationBatchIds.length > 0
      && mascot.animationBatchDir
      && !PROFILE.pipelineAnimations.every((id) => Boolean(animationSource(mascot, id)));
    if (!shouldResume || animationWorkers.has(mascot.id)) continue;
    const worker = runAnimationBatch(mascot);
    animationWorkers.set(mascot.id, worker);
  }
});

function shutdown(signal) {
  console.log(`Mascot 3 received ${signal}; draining HTTP connections`);
  server.close((error) => process.exit(error ? 1 : 0));
  setTimeout(() => process.exit(1), 10_000).unref();
}
process.once("SIGTERM", () => shutdown("SIGTERM"));
process.once("SIGINT", () => shutdown("SIGINT"));

module.exports = { pipelineArgs, cleanText, secureEqual, normalizeMascotPaths };
