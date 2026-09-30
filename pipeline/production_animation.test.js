const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");
const { validateCatalog, resolveTemplate, PROMPT_LIMIT } = require("./lib/prompt_catalog.js");
const { patchWebpFrameDisposal } = require("./lib/media.js");
const { validateRenderIdentity } = require("./lib/canonical.js");
const { videoFramingQa } = require("./lib/framing.js");
const { spawnSync } = require("node:child_process");
const os = require("node:os");

const ROOT = __dirname;
// The only words the catalog may never contain. Each one was observed being drawn as a
// real object in a rendered clip because the prompt named something the keyframe lacks.
const ABSENT_GEOMETRY = /\b(?:glass|floor|ground|wall|mirror|reflection|shadow|window|pane|ledge|edge|surface|barrier)\b/i;
const ABSENT_GEOMETRY_WORDS = ["glass", "floor", "ground", "wall", "mirror", "reflection", "shadow", "window", "pane", "ledge", "edge", "surface", "barrier"];

test("the absent-geometry gate is not vacuous", () => {
  for (const word of ABSENT_GEOMETRY_WORDS) {
    assert.match(`a prompt that mentions the ${word} somewhere`, ABSENT_GEOMETRY, `the gate must catch "${word}"`);
  }
  assert.doesNotMatch("a prompt naming nothing absent at all", ABSENT_GEOMETRY);
  for (const code of [8, 11, 12, 27]) {
    assert.ok(!ABSENT_GEOMETRY.source.includes(String.fromCharCode(code)), "a mangled escape would leave a control character here and silently disable the gate");
  }
});

test("prompt catalog covers every loop state with character-independent prompts", () => {
  const animations = JSON.parse(fs.readFileSync(path.join(ROOT, "dict", "animations.json")));
  const catalog = JSON.parse(fs.readFileSync(path.join(ROOT, "dict", "animation_prompt_catalog.json")));
  validateCatalog(catalog, animations);
  assert.equal(catalog.entries.length, 18);
  assert.equal(catalog.entries.filter((entry) => entry.availability === "enabled").length, 17);
  assert.match(catalog.suffix, /The camera does not move\.$/);
  for (const entry of catalog.entries) {
    const prompt = resolveTemplate(entry.template);
    const resolvedPrompt = `${resolveTemplate(catalog.prefix)} ${prompt} ${resolveTemplate(catalog.suffix)}`;
    assert.match(entry.availability, /^(enabled|disabled)$/);
    assert.equal(entry.availability === "disabled", entry.verified.verdict === "rejected");
    assert.doesNotMatch(prompt, /\{[^}]+\}/, `${entry.id} must stay character-independent`);
    assert.doesNotMatch(prompt, /\b(?:he|she|his|her|him|hers|they|their|theirs)\b/i, `${entry.id} must not carry a gendered pronoun`);
    assert.doesNotMatch(resolvedPrompt, ABSENT_GEOMETRY, `${entry.id} must not name anything the keyframe does not contain`);
    assert.ok(resolvedPrompt.length <= PROMPT_LIMIT, `${entry.id} resolves to ${resolvedPrompt.length} characters`);
  }
  const templates = catalog.entries.map((entry) => entry.template);
  assert.equal(new Set(templates).size, 18, "every state must read as a different instruction");
  assert.equal(catalog.entries.find((entry) => entry.id === "spin"), undefined, "spin was replaced by stretch");
  assert.equal(catalog.entries.find((entry) => entry.id === "spark"), undefined, "spark was replaced by greeting");
  assert.throws(() => resolveTemplate("{prop}"), /prohibited/);
  assert.throws(() => validateRenderIdentity(
    { subject: "a turtle", silhouette_cue: "round shell", proportion_cue: "compact body", neutral_pose: "neutral pose", persistent_prop: "signal_flag", prop_placement: "orbiting beside Viktor's side" },
    { prop_id: "signal_flag", placement: "orbiting beside Viktor's side" }, "Viktor",
  ), /character name or gendered pronoun/);
  assert.doesNotThrow(() => validateRenderIdentity(
    { subject: "a turtle", silhouette_cue: "round shell", proportion_cue: "compact body", neutral_pose: "neutral pose", persistent_prop: "signal_flag", prop_placement: "Orbiting beside the front-right side" },
    { prop_id: "signal_flag", placement: "Orbiting beside the front-right side" },
  ));
});

test("app entry reuses greeting without a duplicate welcome in the catalog", () => {
  const catalog = JSON.parse(fs.readFileSync(path.join(ROOT, "dict", "animation_prompt_catalog.json")));
  assert.equal(catalog.entries.find((entry) => entry.id === "welcome"), undefined);
  const greeting = catalog.entries.find((entry) => entry.id === "greeting");
  assert.equal(greeting.availability, "enabled");
  assert.equal(greeting.duration_seconds, 6);
});

test("WebP disposal patch changes only ANMF flags", () => {
  const file = path.join(ROOT, "tmp_webp_test.webp");
  const chunk = Buffer.alloc(24);
  chunk.write("ANMF", 0, "ascii"); chunk.writeUInt32LE(16, 4);
  const riff = Buffer.concat([Buffer.from("RIFF"), Buffer.alloc(4), Buffer.from("WEBP"), chunk]);
  riff.writeUInt32LE(riff.length - 8, 4); fs.writeFileSync(file, riff);
  const before = fs.readFileSync(file);
  const result = patchWebpFrameDisposal(file);
  const after = fs.readFileSync(file); fs.unlinkSync(file);
  assert.deepEqual(result, { frame_count: 1, blend: false, dispose_to_background: true });
  assert.equal(before.length, after.length);
  assert.equal(after[35], 3);
  for (let index = 0; index < before.length; index += 1) if (index !== 35) assert.equal(before[index], after[index]);
});

test("video framing QA blocks source-boundary contact without requiring the preferred margin", () => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), "mascot-framing-test-"));
  const contained = path.join(directory, "contained.mp4");
  const tight = path.join(directory, "tight.mp4");
  const clipped = path.join(directory, "clipped.mp4");
  const render = (output, boxX) => spawnSync("ffmpeg", [
    "-loglevel", "error", "-y", "-f", "lavfi", "-i",
    `color=c=0x00ff00:s=240x240:d=1:r=12,drawbox=x=${boxX}:y=70:w=100:h=100:color=white:t=fill`,
    "-pix_fmt", "yuv420p", output,
  ]);
  assert.equal(render(contained, 70).status, 0);
  assert.equal(render(tight, 10).status, 0);
  assert.equal(render(clipped, 0).status, 0);

  const containedQa = videoFramingQa(contained, { minMargin: 0.10, hardMinMargin: 0.015 });
  const tightQa = videoFramingQa(tight, { minMargin: 0.10, hardMinMargin: 0.015 });
  const clippedQa = videoFramingQa(clipped, { minMargin: 0.10, hardMinMargin: 0.015 });
  assert.equal(containedQa.hard_pass, true);
  assert.equal(containedQa.framing_pass, true);
  assert.equal(tightQa.hard_pass, true);
  assert.equal(tightQa.framing_pass, false);
  assert.equal(clippedQa.hard_pass, false);
  assert.equal(clippedQa.framing_pass, false);
  fs.rmSync(directory, { recursive: true, force: true });
});
