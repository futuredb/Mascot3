const test = require("node:test");
const assert = require("node:assert/strict");
const path = require("node:path");
const fs = require("node:fs/promises");
const Ajv = require("ajv");
const { loadDictionaries, buildPrimaryData, buildPrimaryDataWithBags } = require("./lib/deal.js");
const { corpusHash, drawBag } = require("./lib/registry.js");
const { resolveVideoModel } = require("./lib/animation.js");
const { validateCatalog, resolveTemplate } = require("./lib/prompt_catalog.js");
const { STYLE_TRANSFER_CONTRACT, TECHNICAL_FRAME_SYSTEM_PROMPT, TURNTABLE_SYSTEM_PROMPT, buildCanonicalRenderPrompt, buildTurntablePrompt, validateRenderIdentity } = require("./lib/canonical.js");

const ROOT = __dirname;
test("same seed produces the same primary data", async () => {
  const dictionaries = await loadDictionaries(ROOT);
  const left = buildPrimaryData(dictionaries, { seed: "test-seed" });
  const right = buildPrimaryData(dictionaries, { seed: "test-seed" });
  assert.deepEqual(left, right);
  assert.equal(left.selected.subject_kind, "living");
  assert.ok(["male", "female"].includes(left.selected.gender.id));
  assert.equal(left.input.name_source, "generated");
  assert.ok(left.input.name);
  assert.deepEqual({ ...left.input, name: null, name_source: null }, { name: null, name_source: null, subject: null, subject_kind: null, gender: null, age: null, origin: null, brief: null });
  assert.ok(/^inner_modifiers\.(motivation|goal|fear|interest)\.|^external_modifiers\.superpower\./.test(left.selected.scene_lead.path));
  assert.equal(left.selected.character.emotional_tone.length, 2);
  assert.ok([1, 2].includes(left.selected.character.humor_style.length));
  if (left.selected.character.humor_style.length === 1) assert.equal(left.selected.character.humor_style[0].id, "not_comedic");
  else assert.ok(left.selected.character.humor_style.every((item) => item.fun));
  assert.equal(left.selected.inner_modifiers.interest.length, 2);
  assert.equal(left.selected.inner_modifiers.habit.length, 3);
  assert.equal(left.selected.external_modifiers.appearance_trait.length, 2);
  assert.equal(left.selected.external_modifiers.prop.length, 2);
  assert.ok(left.selected.external_modifiers.appearance_trait.some((item) => item.group === "silhouette"));
  assert.ok(left.selected.external_modifiers.appearance_trait.some((item) => item.group !== "silhouette"));
  assert.equal(left.selected.external_modifiers.superpower.length, 2);
  assert.deepEqual(left.selected.external_modifiers.superpower.map((item) => item.group).sort(), ["quiet", "spectacular"]);
});
test("made requests become a living carrier with a literal requested object", async () => {
  const dictionaries = await loadDictionaries(ROOT);
  assert.throws(() => buildPrimaryData(dictionaries, { seed: "made", subjectKind: "made" }), /subject-kind=made requires/);
  const deal = buildPrimaryData(dictionaries, { seed: "made", subjectKind: "made", subject: "напёрсток" });
  assert.equal(deal.selected.subject_kind, "living");
  assert.ok(["male", "female"].includes(deal.selected.gender.id));
  assert.equal(deal.selected.subject_request, null);
  assert.deepEqual(deal.selected.requested_object, { id: "requested_object", label: "напёрсток", user_requested: true });
});
test("automatic subject kinds use the configured weighted split", async () => {
  const dictionaries = await loadDictionaries(ROOT);
  let made = 0;
  for (let index = 0; index < 1_000; index += 1) {
    if (buildPrimaryData(dictionaries, { seed: `subject-kind-${index}` }).selected.subject_kind === "made") made += 1;
  }
  assert.equal(made, 0, `expected no made subjects, got ${made}/1000`);
});
test("primary input records every explicit override", async () => {
  const dictionaries = await loadDictionaries(ROOT);
  const deal = buildPrimaryData(dictionaries, {
    seed: "pinned", name: "Мира", subject: "выдра", subjectKind: "living",
    gender: "female", age: 28, origin: "medieval_kingdom", brief: "Тихий тестовый заказ.",
  });
  assert.deepEqual(deal.input, {
    name: "Мира", name_source: "user", subject: "выдра", subject_kind: "living",
    gender: "female", age: 28, origin: "medieval_kingdom", brief: "Тихий тестовый заказ.",
  });
});
test("animation catalog contains all migrated state families", async () => {
  const animations = JSON.parse(await fs.readFile(path.join(ROOT, "dict", "animations.json"), "utf8"));
  assert.equal(animations.entries.length, 18);
  assert.ok(animations.entries.find((entry) => entry.id === "idle"));
  assert.ok(animations.entries.find((entry) => entry.id === "rest"));
  assert.ok(animations.entries.find((entry) => entry.id === "signature_move"));
  assert.equal(animations.contract, 4);
  assert.ok(animations.entries.every((entry) => entry.duration.max_seconds === 10));
  assert.equal(animations.entries.find((entry) => entry.id === "greeting").group, "active_motion");
  assert.equal(animations.entries.find((entry) => entry.id === "stretch").group, "active_motion");
  assert.ok(!animations.entries.some((entry) => entry.id === "spin" || entry.id === "spark"));
});
test("canonical rendering uses minimal identity and shared style transfer", () => {
  const anchorProp = { prop_id: "signal_flag", placement: "a small flag beside the paw" };
  const renderIdentity = {
    subject: "common squirrel",
    silhouette_cue: "one large rounded tail",
    proportion_cue: "compressed compact torso with short limbs and an enlarged tail, never ordinary full-size squirrel proportions",
    persistent_prop: "signal_flag",
    prop_placement: "a small flag beside the paw",
    neutral_pose: "neutral upright hover",
  };
  assert.deepEqual(validateRenderIdentity(renderIdentity, anchorProp), renderIdentity);
  const prompt = buildCanonicalRenderPrompt({
    renderIdentity, anchorProp, viewRule: "DIRECT FRONT VIEW.",
    propViewRule: "Show it beside the paw. Keep the prop separated.",
  });
  assert.ok(prompt.includes(STYLE_TRANSFER_CONTRACT));
  assert.match(prompt, /Mandatory body proportions: compressed compact torso/);
  assert.match(STYLE_TRANSFER_CONTRACT, /Do not infer conventional real-world species colours/);
  assert.doesNotMatch(prompt, /character_brief/i);
  assert.match(TECHNICAL_FRAME_SYSTEM_PROMPT, /compressed dwarf-like proportions/);
  assert.match(buildTurntablePrompt({ renderIdentity, anchorProp }), /may be naturally occluded/);
  assert.match(TURNTABLE_SYSTEM_PROMPT, /top-left.*top-right.*bottom-left.*bottom-right/);
  assert.throws(() => validateRenderIdentity({ ...renderIdentity, proportion_cue: "" }, anchorProp), /proportion_cue/);
  assert.throws(() => validateRenderIdentity({ ...renderIdentity, silhouette_cue: "glossy blue tail" }, anchorProp), /forbidden style/);
});
test("rebalanced corpus meets fixed counts and metadata contracts", async () => {
  const dictionaries = await loadDictionaries(ROOT);
  const { inner_modifiers: inner, external_modifiers: external } = dictionaries;
  assert.equal(inner.sections.motivation.entries.length, 22);
  assert.equal(inner.sections.interest.entries.length, 14);
  assert.equal(inner.sections.goal.entries.length, 16);
  assert.ok(inner.sections.fear.entries.length >= 10 && inner.sections.fear.entries.length <= 12);
  assert.equal(external.sections.prop.entries.length, 20);
  assert.deepEqual(external.sections.prop.entries.reduce((counts, item) => {
    counts[item.role] = (counts[item.role] || 0) + 1;
    return counts;
  }, {}), { utility: 4, play: 5, attachment: 7, expressive: 4 });
  assert.deepEqual(external.sections.superpower.entries.reduce((counts, item) => {
    counts[item.group] = (counts[item.group] || 0) + 1;
    assert.ok(["body", "effects"].includes(item.visible_on));
    return counts;
  }, {}), { spectacular: 12, quiet: 12 });
  assert.ok(external.sections.appearance_trait.entries.filter((item) => item.group === "motion_detail").length >= 4);
});
test("lore schema validates a complete character response", async () => {
  const schema = JSON.parse(await fs.readFile(path.join(ROOT, "schema", "lore.schema.json"), "utf8"));
  const validate = new Ajv({ strict: false }).compile(schema);
  assert.equal(validate({
    identity: { name: "Тест", subject: "кот", subject_kind: "living" },
    body_profile: { body_concept: "кот", silhouette: "круглый силуэт с хвостом", scale: "вещи меньше кота", front: "мордочка", visible_parts: ["лапа"], movement_contact: ["лапа"], view_predisposition: { mode: "front", reason: "видна мордочка" } },
    visual_signature: { primary_silhouette_cue: "широкий ворот дугой вокруг головы", secondary_readable_detail: "талисман закреплён у груди", body_language: "чуть наклонённая внимательная поза" },
    passport: { occupation: "исследует", appearance: "полосатый", character: "живой", motivation: "узнать", fear: "ошибиться", interests: ["формы"], goal: "собрать", habits: ["кивает"], props: ["талисман"], superpowers: ["сила", "меткость"], power_manifestation: "свет вокруг лапы" },
    portrait: "Тестовый персонаж живёт в Межслое и занимается своим делом без звука, опоры, биографической тоски и лишних событий.",
    realization: { "inner_modifiers.goal": "собирает" },
  }), true);
});
test("persisted bag draws without replacement", async () => {
  const file = path.join(ROOT, ".test-bag.json");
  await fs.rm(file, { force: true });
  const values = [];
  for (let index = 0; index < 3; index += 1) {
    values.push((await drawBag(file, { corpus_version: "v1", namespace: "test", entries: ["a", "b", "c"], seed: "seed" })).id);
  }
  assert.equal(new Set(values).size, 3);
  await fs.rm(file, { force: true });
});
test("series deals use persisted bags for every axis", async () => {
  const dictionaries = await loadDictionaries(ROOT);
  const file = path.join(ROOT, ".test-series-bags.json");
  await fs.rm(file, { force: true });
  const deal = (seed) => buildPrimaryDataWithBags(dictionaries, {
    seed,
    draw: (request) => drawBag(file, { corpus_version: corpusHash(dictionaries), ...request }),
  });
  const left = await deal("series-1");
  const right = await deal("series-2");
  assert.equal(left.selection_provenance.mode, "persisted_bags");
  assert.notEqual(left.input.name, right.input.name);
  assert.notEqual(left.selected.inner_modifiers.motivation.id, right.selected.inner_modifiers.motivation.id);
  assert.notEqual(left.selected.external_modifiers.prop[0].id, right.selected.external_modifiers.prop[0].id);
  const names = new Set([left.input.name, right.input.name]);
  const silhouette = new Set();
  const extraAppearance = new Set();
  const collectAppearance = (deal) => deal.selected.external_modifiers.appearance_trait.forEach((item) => {
    (item.group === "silhouette" ? silhouette : extraAppearance).add(item.id);
  });
  collectAppearance(left);
  collectAppearance(right);
  for (let index = 2; index < 15; index += 1) {
    const next = await deal(`series-${index + 1}`);
    names.add(next.input.name);
    collectAppearance(next);
  }
  assert.equal(silhouette.size, 8);
  assert.equal(extraAppearance.size, 15);
  assert.equal(names.size, 15);
  await fs.rm(file, { force: true });
});
test("humor gate never mixes non-comedic with comic styles", async () => {
  const dictionaries = await loadDictionaries(ROOT);
  let plain = 0;
  for (let index = 0; index < 1_000; index += 1) {
    const humor = buildPrimaryData(dictionaries, { seed: `humor-${index}` }).selected.character.humor_style;
    if (humor.length === 1) {
      plain += 1;
      assert.equal(humor[0].id, "not_comedic");
    } else {
      assert.equal(humor.length, 2);
      assert.ok(humor.every((item) => item.fun));
    }
  }
  assert.ok(plain > 60 && plain < 140);
});
