const crypto = require("node:crypto");
const fs = require("node:fs");
const path = require("node:path");
const { spawnSync } = require("node:child_process");
const { loadEnvFileDefaults } = require("../../extensions/02_tools_and_apps/_shared/env_file.js");
const { resolveProductionAnimation, runProductionAnimation } = require("../lib/production_animation.js");

const ROOT = path.resolve(__dirname, "..");
const DATA_ROOT = process.env.PET_V2_DATA_DIR ? path.resolve(process.env.PET_V2_DATA_DIR) : ROOT;
const PIPELINE = path.join(ROOT, "run_character_pipeline.js");
const BATCH_CONTRACT = "pet_generation_v2.character_batch.v1";
const ANIMATION_BATCH_CONTRACT = "pet_generation_v2.selected_animation_batch.v1";
const DEFAULT_PRICE_PER_SECOND_USD = 0.076125;
const ALLOWED_OPTIONS = new Set(["subject", "subject-kind", "brief", "name", "seed", "age", "gender", "origin", "refs-root", "text-model", "art-model", "anchor-limit", "canonical-attempts", "view-mode", "series", "bag-state", "name-bag-state", "direct-openrouter", "skip-canonical-ai-review"]);

function parseOptions(argv) {
  const [command, ...args] = argv;
  const options = {};
  for (const value of args) {
    const match = value.match(/^--([a-z-]+)(?:=(.*))?$/);
    if (!match) throw new Error(`Unsupported argument: ${value}`);
    options[match[1]] = match[2] ?? "yes";
  }
  return { command, options };
}

function assertNoAnimationOptions(options) {
  for (const key of ["animation", "phase", "video-model", "veo-model"]) {
    if (options[key] !== undefined) throw new Error(`--${key} is not available in character_cli; this command creates canonical anchors only`);
  }
}

function pipelineArgs(options, { submit }) {
  assertNoAnimationOptions(options);
  const args = ["--through=canonical"];
  for (const [key, value] of Object.entries(options)) {
    if (ALLOWED_OPTIONS.has(key)) {
      if (key === "series") {
        if (value === "yes" || value === true) args.push("--series");
      } else {
        args.push(`--${key}=${value}`);
      }
    }
  }
  if (!submit) args.push("--dry-run");
  return args;
}

function runPipeline(options, { submit }) {
  const args = [PIPELINE, ...pipelineArgs(options, { submit })];
  const result = spawnSync(process.execPath, args, {
    cwd: ROOT,
    encoding: "utf8",
    timeout: 20 * 60 * 1000,
    killSignal: "SIGKILL",
  });
  if (result.error?.code === "ETIMEDOUT") throw new Error("Character pipeline timed out after 20 minutes");
  if (result.status !== 0) throw new Error(result.stderr || result.stdout || "Character pipeline failed");
  try {
    return JSON.parse(result.stdout);
  } catch {
    return { status: "completed", output: result.stdout.trim() };
  }
}

function approveCharacter(characterDir) {
  const result = spawnSync(process.execPath, [PIPELINE, `--character-dir=${path.resolve(characterDir)}`, "--through=canonical", "--approve-canonical"], {
    cwd: ROOT,
    encoding: "utf8",
    timeout: 20 * 60 * 1000,
    killSignal: "SIGKILL",
  });
  if (result.error?.code === "ETIMEDOUT") throw new Error("Canonical approval timed out after 20 minutes");
  if (result.status !== 0) throw new Error(result.stderr || result.stdout || "Canonical approval failed");
  return JSON.parse(result.stdout);
}

function readBatch(specFile) {
  const spec = JSON.parse(fs.readFileSync(path.resolve(specFile), "utf8"));
  if (spec.contract !== BATCH_CONTRACT || !Array.isArray(spec.characters) || !spec.characters.length) {
    throw new Error(`Batch spec must use contract '${BATCH_CONTRACT}' and contain a non-empty characters array`);
  }
  if (spec.characters.length > 50) throw new Error("Batch spec contains more than 50 characters");
  for (const [index, character] of spec.characters.entries()) {
    if (!character || typeof character !== "object" || !String(character.subject || "").trim()) {
      throw new Error(`characters[${index}] requires a non-empty subject`);
    }
    assertNoAnimationOptions(character);
  }
  return spec;
}

function batchManifestPath(batchId) {
  const directory = path.join(DATA_ROOT, "runs", "character_batches");
  fs.mkdirSync(directory, { recursive: true });
  return path.join(directory, `${batchId}.json`);
}

function animationBatchDir(characterDir, batchFolder) {
  if (!batchFolder?.trim()) throw new Error("animate requires --batch-dir=<folder>");
  const animationRoot = path.resolve(characterDir, "animation");
  const output = path.resolve(animationRoot, batchFolder);
  if (!output.startsWith(`${animationRoot}${path.sep}`)) throw new Error("--batch-dir must be inside the character animation directory");
  return output;
}

function selectedAnimations(catalog, options) {
  const hasList = Boolean(options.animations);
  const hasAll = options["all-enabled"] === "yes";
  if (hasList === hasAll) throw new Error("animate requires exactly one of --animations=id,id or --all-enabled");
  const ids = hasAll
    ? catalog.entries.filter((entry) => entry.availability === "enabled").map((entry) => entry.id)
    : options.animations.split(",").map((id) => id.trim()).filter(Boolean);
  if (!ids.length || new Set(ids).size !== ids.length) throw new Error("--animations must contain one or more unique IDs");
  const entries = ids.map((id) => catalog.entries.find((entry) => entry.id === id));
  const unknown = ids.filter((id, index) => !entries[index]);
  if (unknown.length) throw new Error(`Unknown animation IDs: ${unknown.join(", ")}`);
  return entries;
}

function animationOptions(options, submit) {
  const allowPendingCanonical = options["allow-pending-canonical"] === "yes";
  const allowDisabledVerification = options["allow-disabled-verification"] === "yes";
  return {
    dryRun: !submit,
    verification: allowPendingCanonical || allowDisabledVerification,
    allowPendingCanonical,
    allowDisabledVerification,
    seed: options.seed,
    "video-model": options["video-model"],
    resolution: options.resolution,
  };
}

async function animate(options) {
  if (!options.character) throw new Error("animate requires --character=<dir>");
  const submit = options.submit === "yes";
  const characterDir = path.resolve(options.character);
  const batchDir = animationBatchDir(characterDir, options["batch-dir"]);
  const catalog = JSON.parse(fs.readFileSync(path.join(ROOT, "dict", "animation_prompt_catalog.json"), "utf8"));
  const entries = selectedAnimations(catalog, options);
  const blocked = entries.filter((entry) => entry.availability === "disabled");
  if (blocked.length && options["allow-disabled-verification"] !== "yes") {
    throw new Error(`Disabled animation IDs require --allow-disabled-verification=yes: ${blocked.map((entry) => entry.id).join(", ")}`);
  }
  const env = loadEnvFileDefaults({ env: process.env, workspaceRoot: path.resolve(ROOT, "..") });
  const pricePerSecond = Number(options["price-per-second-usd"] || env.PET_V2_VIDEO_PRICE_PER_SECOND_USD || DEFAULT_PRICE_PER_SECOND_USD);
  const maxCostUsd = Number(options["max-cost-usd"] || env.PET_V2_MAX_ANIMATION_COST_USD || 3);
  if (!Number.isFinite(pricePerSecond) || pricePerSecond <= 0) throw new Error("price per second must be positive");
  if (!Number.isFinite(maxCostUsd) || maxCostUsd <= 0) throw new Error("max animation cost must be positive");
  const perAnimationOptions = animationOptions(options, submit);
  const plans = [];
  for (const entry of entries) {
    const output = path.relative(path.join(characterDir, "animation"), path.join(batchDir, entry.id));
    const setup = await resolveProductionAnimation({
      root: ROOT, env, characterDir, animationId: entry.id,
      options: { ...perAnimationOptions, "animation-output": output },
    });
    plans.push({
      id: entry.id, output, duration_seconds: setup.catalogEntry.duration_seconds,
      estimated_cost_usd: Number((setup.catalogEntry.duration_seconds * pricePerSecond).toFixed(4)),
      catalog_entry_hash: setup.catalogEntry.source_hash,
      status: submit ? "pending" : "planned",
    });
  }
  const existingPath = path.join(batchDir, "batch.json");
  let existing = null;
  if (submit && fs.existsSync(existingPath)) {
    existing = JSON.parse(fs.readFileSync(existingPath, "utf8"));
    if (existing.contract !== ANIMATION_BATCH_CONTRACT || existing.character_dir !== characterDir || existing.catalog_version !== catalog.version) {
      throw new Error("Existing batch.json belongs to a different character or catalog version; choose a new --batch-dir");
    }
    const existingIds = existing.states.map((state) => state.id).join(",");
    const requestedIds = plans.map((state) => state.id).join(",");
    if (existingIds !== requestedIds) throw new Error("Existing batch.json has a different animation set; choose a new --batch-dir");
  }
  const batch = {
    contract: ANIMATION_BATCH_CONTRACT,
    character_dir: characterDir,
    catalog_version: catalog.version,
    created_at: existing?.created_at || new Date().toISOString(),
    submitted: submit,
    output_directory: batchDir,
    state_count: plans.length,
    total_seconds: plans.reduce((total, plan) => total + plan.duration_seconds, 0),
    estimated_price_per_second_usd: pricePerSecond,
    estimated_total_usd: Number((plans.reduce((total, plan) => total + plan.duration_seconds, 0) * pricePerSecond).toFixed(4)),
    max_cost_usd: maxCostUsd,
    states: plans,
  };
  if (batch.estimated_total_usd > maxCostUsd) {
    throw new Error(`Animation estimate $${batch.estimated_total_usd} exceeds hard limit $${maxCostUsd}`);
  }
  if (!submit) return batch;

  fs.mkdirSync(batchDir, { recursive: true });
  for (const state of batch.states) {
    const result = await runProductionAnimation({
      root: ROOT, env, characterDir, animationId: state.id,
      options: { ...perAnimationOptions, "animation-output": state.output },
    });
    state.status = result.status;
    state.job = result.job || null;
    state.animation_dir = result.animationDir || null;
    fs.writeFileSync(existingPath, `${JSON.stringify(batch, null, 2)}\n`);
  }
  return batch;
}

async function main() {
  const { command, options } = parseOptions(process.argv.slice(2));
  if (!command || !["create", "batch", "animate", "status", "approve"].includes(command)) {
    throw new Error("Usage: character_cli.js <create|batch|animate|status|approve> [options]");
  }

  if (command === "create") {
    if (!options.subject) throw new Error("create requires --subject=<subject>");
    const submit = options.submit === "yes";
    const result = runPipeline(options, { submit });
    console.log(JSON.stringify({ command, submitted: submit, result }, null, 2));
    return;
  }

  if (command === "animate") {
    const batch = await animate(options);
    console.log(JSON.stringify(batch, null, 2));
    return;
  }

  if (command === "batch") {
    if (!options.spec) throw new Error("batch requires --spec=<file>");
    const spec = readBatch(options.spec);
    const batchId = options["batch-id"] || `character-batch-${new Date().toISOString().slice(0, 10).replaceAll("-", "")}-${crypto.randomUUID().slice(0, 8)}`;
    const manifest = {
      contract: BATCH_CONTRACT,
      id: batchId,
      created_at: new Date().toISOString(),
      submitted: options.submit === "yes",
      spec: path.resolve(options.spec),
      state_count: spec.characters.length,
      states: [],
    };
    if (options.submit !== "yes") {
      manifest.states = spec.characters.map((character, index) => ({ index, subject: character.subject, status: "planned", pipeline_args: pipelineArgs({ ...character, series: character.series ?? options.series }, { submit: false }) }));
    } else {
      for (const [index, character] of spec.characters.entries()) {
        const result = runPipeline({ ...character, series: character.series ?? options.series }, { submit: true });
        manifest.states.push({ index, subject: character.subject, status: result.status, character_dir: result.characterDir ?? null, run_dir: result.runDir ?? null });
        fs.writeFileSync(batchManifestPath(batchId), `${JSON.stringify(manifest, null, 2)}\n`);
      }
    }
    const destination = batchManifestPath(batchId);
    fs.writeFileSync(destination, `${JSON.stringify(manifest, null, 2)}\n`);
    console.log(JSON.stringify({ command, submitted: manifest.submitted, manifest: destination, state_count: manifest.state_count }, null, 2));
    return;
  }

  if (command === "status") {
    if (!options.batch) throw new Error("status requires --batch=<batch-id or manifest path>");
    const candidate = options.batch.endsWith(".json") ? path.resolve(options.batch) : batchManifestPath(options.batch);
    if (!fs.existsSync(candidate)) throw new Error(`Batch manifest not found: ${candidate}`);
    console.log(fs.readFileSync(candidate, "utf8"));
    return;
  }

  if (!options.character || options.approve !== "yes") {
    throw new Error("approve requires --character=<dir> --approve=yes");
  }
  const result = approveCharacter(options.character);
  console.log(JSON.stringify({ command, result }, null, 2));
}

main().catch((error) => { console.error(error.stack || error.message); process.exitCode = 1; });
