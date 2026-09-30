const assert = require("node:assert/strict");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const { execFileSync, spawnSync } = require("node:child_process");
const test = require("node:test");
const { createLoreBridge } = require("./lib/composer.js");

const ROOT = __dirname;
const CLI = path.join(ROOT, "scripts", "character_cli.js");
const batches = path.join(ROOT, "runs", "character_batches");

function run(...args) {
  return execFileSync(process.execPath, [CLI, ...args], { cwd: ROOT, encoding: "utf8" });
}

test("character CLI creates a no-cost dry-run by default", (t) => {
  const result = JSON.parse(run("create", "--subject=turtle", "--name=CLI Test"));
  t.after(() => fs.rmSync(result.result.runDir, { recursive: true, force: true }));
  assert.equal(result.command, "create");
  assert.equal(result.submitted, false);
  assert.equal(result.result.status, "dry-run");
});

test("character CLI safely plans and reports a batch", (t) => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), "pet-v2-cli-"));
  const batchId = `test-${Date.now()}-${process.pid}`;
  const spec = path.join(directory, "characters.json");
  fs.writeFileSync(spec, JSON.stringify({
    contract: "pet_generation_v2.character_batch.v1",
    characters: [{ subject: "turtle", brief: "A test character." }],
  }));
  t.after(() => {
    fs.rmSync(directory, { recursive: true, force: true });
    fs.rmSync(path.join(batches, `${batchId}.json`), { force: true });
  });

  const planned = JSON.parse(run("batch", `--spec=${spec}`, `--batch-id=${batchId}`));
  assert.equal(planned.submitted, false);
  assert.equal(planned.state_count, 1);
  const manifest = JSON.parse(run("status", `--batch=${batchId}`));
  assert.equal(manifest.states[0].status, "planned");
  assert.ok(manifest.states[0].pipeline_args.includes("--dry-run"));
});

test("character CLI approves an existing canonical manifest only on explicit confirmation", (t) => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), "pet-v2-cli-character-"));
  fs.mkdirSync(path.join(directory, "canonical"), { recursive: true });
  fs.writeFileSync(path.join(directory, "primary_data.json"), JSON.stringify({ selected: { subject_kind: "living" } }));
  fs.writeFileSync(path.join(directory, "lore.json"), JSON.stringify({ identity: { subject_kind: "living" } }));
  fs.writeFileSync(path.join(directory, "visual_identity.json"), JSON.stringify({}));
  fs.writeFileSync(path.join(directory, "canonical", "manifest.json"), JSON.stringify({ status: "pending_manual_review" }));
  t.after(() => fs.rmSync(directory, { recursive: true, force: true }));

  const missingConfirmation = spawnSync(process.execPath, [CLI, "approve", `--character=${directory}`], { cwd: ROOT, encoding: "utf8" });
  assert.notEqual(missingConfirmation.status, 0);
  assert.match(missingConfirmation.stderr, /--approve=yes/);
  const result = JSON.parse(run("approve", `--character=${directory}`, "--approve=yes"));
  t.after(() => fs.rmSync(result.result.runDir, { recursive: true, force: true }));
  assert.equal(result.command, "approve");
  const manifest = JSON.parse(fs.readFileSync(path.join(directory, "canonical", "manifest.json"), "utf8"));
  assert.equal(manifest.status, "approved");
  assert.equal(manifest.manual_verdict, "approved");
});

test("character CLI plans exactly the requested animation IDs without submitting them", (t) => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), "pet-v2-cli-animation-"));
  const batchFolder = "selected";
  fs.mkdirSync(path.join(directory, "canonical"), { recursive: true });
  fs.writeFileSync(path.join(directory, "canonical", "manifest.json"), JSON.stringify({ status: "approved", anchors: [] }));
  t.after(() => fs.rmSync(directory, { recursive: true, force: true }));

  const result = JSON.parse(run("animate", `--character=${directory}`, "--animations=idle,sleep", `--batch-dir=${batchFolder}`));
  assert.equal(result.contract, "pet_generation_v2.selected_animation_batch.v1");
  assert.equal(result.submitted, false);
  assert.deepEqual(result.states.map((state) => state.id), ["idle", "sleep"]);
  assert.ok(result.states.every((state) => state.status === "planned" && state.estimated_cost_usd > 0));
  assert.ok(!fs.existsSync(path.join(directory, "animation", batchFolder)), "dry-run must not create animation output");
});

test("Mascot 3 Mini profile plans all 17 enabled animations below the unchanged hard cap", (t) => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), "mascot3-mini-budget-"));
  fs.mkdirSync(path.join(directory, "canonical"), { recursive: true });
  fs.writeFileSync(path.join(directory, "canonical", "manifest.json"), JSON.stringify({ status: "approved", anchors: ["anchor.png"] }));
  t.after(() => fs.rmSync(directory, { recursive: true, force: true }));

  const result = JSON.parse(run(
    "animate",
    `--character=${directory}`,
    "--all-enabled",
    "--batch-dir=mascot3-mini-full-v43",
    "--video-model=bytedance/seedance-2.0-mini",
    "--resolution=720p",
    "--price-per-second-usd=0.076125",
    "--max-cost-usd=9",
  ));
  assert.equal(result.state_count, 17);
  assert.equal(result.total_seconds, 112);
  assert.equal(result.estimated_total_usd, 8.526);
  assert.equal(result.max_cost_usd, 9);
  assert.deepEqual(result.states.map((state) => state.id), [
    "idle", "rest", "sleep", "thinking", "at_glass", "watching", "happy", "sad", "angry",
    "refusal", "frightened", "curious", "tender", "stretch", "greeting", "signature_move", "playful",
  ]);
});

test("Mascot 3 rejects the 17-animation pack when the old four-animation cap is used", (t) => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), "mascot3-full-pack-budget-"));
  fs.mkdirSync(path.join(directory, "canonical"), { recursive: true });
  fs.writeFileSync(path.join(directory, "canonical", "manifest.json"), JSON.stringify({ status: "approved", anchors: ["anchor.png"] }));
  t.after(() => fs.rmSync(directory, { recursive: true, force: true }));

  const result = spawnSync(process.execPath, [
    CLI,
    "animate",
    `--character=${directory}`,
    "--all-enabled",
    "--batch-dir=old-cap-full-pack",
    "--video-model=bytedance/seedance-2.0-mini",
    "--price-per-second-usd=0.076125",
    "--max-cost-usd=3",
  ], { cwd: ROOT, encoding: "utf8" });
  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /exceeds hard limit/);
  assert.ok(!fs.existsSync(path.join(directory, "animation", "old-cap-full-pack")));
});

test("character CLI rejects animation generation options", () => {
  const result = spawnSync(process.execPath, [CLI, "create", "--subject=turtle", "--animation=idle"], { cwd: ROOT, encoding: "utf8" });
  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /not available in character_cli/);
});

test("direct OpenRouter mode bypasses the proxy transport", async () => {
  let requestedUrl;
  const bridge = createLoreBridge({
    env: { OPENROUTER_API_KEY: "test-key" },
    directOpenRouter: true,
    fetchImpl: async (url) => {
      requestedUrl = url;
      return new Response(JSON.stringify({ data: [] }), { status: 200, headers: { "content-type": "application/json" } });
    },
  });
  await bridge.listModels({ timeoutMs: 100 });
  assert.equal(requestedUrl, "https://openrouter.ai/api/v1/models");
  assert.throws(() => createLoreBridge({ env: {}, directOpenRouter: true }), /requires OPENROUTER_API_KEY/);
});
