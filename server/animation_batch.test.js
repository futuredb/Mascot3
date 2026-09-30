const test = require("node:test");
const assert = require("node:assert/strict");
const { chooseAnimationBatch, chooseGreetingBatch } = require("./animation_batch");
const catalog = ["idle", "rest", "sleep", "thinking", "at_glass", "watching", "happy", "sad", "angry", "refusal", "frightened", "curious", "tender", "stretch", "greeting", "signature_move", "playful"];

test("acceptance authorizes only the existing greeting, never four/seventeen or welcome", () => {
  assert.deepEqual(chooseGreetingBatch(catalog, [], null), { ids: ["greeting"], resume: false });
  assert.deepEqual(chooseGreetingBatch(catalog, [], ["greeting"]), { ids: ["greeting"], resume: true });
  assert.deepEqual(chooseGreetingBatch(catalog, ["greeting"], ["greeting"]), { ids: [], resume: false });
  assert.deepEqual(chooseGreetingBatch(catalog, ["idle"], ["idle"]), { ids: ["greeting"], resume: false });
});
test("greeting cannot restart or resume an unrelated unfinished paid batch", () => {
  assert.throws(() => chooseGreetingBatch(catalog, [], ["idle"]), { status: 409 });
  assert.throws(() => chooseGreetingBatch(catalog, [], catalog), { status: 409 });
  assert.throws(() => chooseGreetingBatch(catalog, [], ["welcome"]), { status: 409 });
  assert.throws(() => chooseGreetingBatch(catalog.filter(id => id !== "greeting"), [], null), { status: 409 });
});

test("four basic animations, then only missing animations", () => {
  const first = chooseAnimationBatch(catalog, [], null, 4);
  assert.deepEqual(first, { ids: ["idle", "happy", "sleep", "playful"], resume: false });
  const next = chooseAnimationBatch(catalog, first.ids, first.ids, 4);
  assert.deepEqual(next.ids, ["rest", "thinking", "at_glass", "watching"]);
  assert.deepEqual(chooseAnimationBatch(catalog, first.ids, first.ids, 1).ids, ["rest"]);
  assert.equal(next.resume, false);
});
test("clamps to remaining and supports all seventeen or one", () => {
  assert.equal(chooseAnimationBatch(catalog, [], null, 17).ids.length, 17);
  assert.equal(chooseAnimationBatch(catalog, catalog.slice(0, 16), null, 4).ids.length, 1);
  assert.equal(chooseAnimationBatch(catalog, [], null, 1).ids.length, 1);
  assert.deepEqual(chooseAnimationBatch(catalog, catalog, null, 4).ids, []);
});

test("retired welcome is never newly submitted or silently moved to another paid batch", () => {
  assert.deepEqual(chooseAnimationBatch(catalog, catalog, [...catalog, "welcome"], 4), { ids: [], resume: false });
  assert.throws(() => chooseAnimationBatch(catalog, [], ["idle", "welcome"], 4), { status: 409 });
});
test("unfinished paid batch retains its plan; smaller choice never resumes seventeen", () => {
  assert.deepEqual(chooseAnimationBatch(catalog, ["idle"], ["idle", "happy"], 4), { ids: ["idle", "happy"], resume: true });
  assert.throws(() => chooseAnimationBatch(catalog, [], catalog, 4), { status: 409 });
});
test("invalid count fails before any generation", () => {
  for (const count of [0, 18, 1.5, NaN]) assert.throws(() => chooseAnimationBatch(catalog, [], null, count), { status: 400 });
});
