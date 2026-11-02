const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const { canResumeAdditiveWelcomeUpdate } = require("./lib/animation_batch_compatibility");

function catalog() { const current = JSON.parse(fs.readFileSync(path.join(__dirname, "dict", "animation_prompt_catalog.json")));
  return { ...current, version: 46, prefix: "The character in the picture moves with the body it actually has.", suffix: "It floats free in open space with nothing beneath it. Movement runs through it continuously. Its body stays one shape. Its complete silhouette, every limb and carried thing stay inside the central seventy percent of the picture, leaving fifteen percent of the unchanged flat background visible on every side. Loose parts follow and settle. Anything this description names drifts in when it is wanted, moves as it is used, and drifts off again by the end; nothing else appears, and the flat empty background behind it stays exactly as it is. The camera does not move." }; }
const legacyBatch = { catalog_version: 44, states: [{ id: "idle" }, { id: "sleep" }] };

test("duplicate removal permits unchanged legacy seventeen-action batches from v44 and v45", () => {
  assert.equal(canResumeAdditiveWelcomeUpdate(legacyBatch, catalog()), true);
  assert.equal(canResumeAdditiveWelcomeUpdate({ ...legacyBatch, catalog_version: 45 }, catalog()), true);
});

test("legacy resume refuses any changed old prompt, timing, source or shared framing", () => {
  for (const change of [
    (c) => { c.entries[0].template += " extra motion"; },
    (c) => { c.entries[0].duration_seconds += 2; },
    (c) => { c.entries[0].source_hash = "different"; },
    (c) => { c.suffix += " different framing"; },
  ]) {
    const c = catalog(); change(c);
    assert.equal(canResumeAdditiveWelcomeUpdate(legacyBatch, c), false);
  }
});

test("new action and arbitrary catalog versions never become legacy authorization", () => {
  assert.equal(canResumeAdditiveWelcomeUpdate({ ...legacyBatch, states: [{ id: "welcome" }] }, catalog()), false);
  assert.equal(canResumeAdditiveWelcomeUpdate({ ...legacyBatch, catalog_version: 43 }, catalog()), false);
  const future = catalog(); future.version = 47;
  assert.equal(canResumeAdditiveWelcomeUpdate(legacyBatch, future), false);
  assert.equal(canResumeAdditiveWelcomeUpdate({ ...legacyBatch, states: [] }, catalog()), false);
});

test("relighting prompt changes do not reinterpret an already submitted legacy batch", () => {
 const current = JSON.parse(fs.readFileSync(path.join(__dirname, "dict", "animation_prompt_catalog.json")));
 assert.equal(canResumeAdditiveWelcomeUpdate(legacyBatch, current), false);
});
