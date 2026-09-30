const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const { canResumeAdditiveWelcomeUpdate } = require("./lib/animation_batch_compatibility");

function catalog() { return JSON.parse(fs.readFileSync(path.join(__dirname, "dict", "animation_prompt_catalog.json"))); }
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
