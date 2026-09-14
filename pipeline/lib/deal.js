const crypto = require("node:crypto");
const fs = require("node:fs/promises");
const path = require("node:path");

const DICT_NAMES = ["base_traits", "inner_modifiers", "external_modifiers", "names"];

function seededUint(seed, namespace) {
  return crypto.createHash("sha256").update(`${seed}:${namespace}`).digest().readUInt32BE(0);
}

function choose(entries, seed, namespace) {
  if (!Array.isArray(entries) || entries.length === 0) throw new Error(`${namespace}: empty entries`);
  return entries[seededUint(seed, namespace) % entries.length];
}

function chooseMany(entries, seed, namespace, min, max) {
  if (!Array.isArray(entries) || entries.length < min) throw new Error(`${namespace}: needs ${min} entries`);
  const count = min + (seededUint(seed, `${namespace}.count`) % (Math.min(max, entries.length) - min + 1));
  return entries.map((entry, index) => ({
    entry,
    rank: seededUint(seed, `${namespace}.${entry.id || index}`),
  })).sort((a, b) => a.rank - b.rank).slice(0, count).map(({ entry }) => entry);
}

function selectByRule(entries, rule, seed, namespace) {
  if (rule === "one") return choose(entries, seed, namespace);
  if (rule?.mode === "exactly") return chooseMany(entries, seed, namespace, rule.count, rule.count);
  if (rule?.mode === "one_or_two" || rule?.mode === "one_to_three") {
    return chooseMany(entries, seed, namespace, rule.min, rule.max);
  }
  if (rule?.mode === "fun_gate_then_one_or_two") {
    const gate = seededUint(seed, `${namespace}.gate`) / 0x100000000;
    if (gate >= rule.fun_probability) return [entries.find((entry) => entry.id === rule.not_comedic_id)];
    return chooseMany(entries.filter((entry) => entry.fun), seed, namespace, rule.min, rule.max);
  }
  if (rule?.mode === "fun_gate_then_exactly") {
    const gate = seededUint(seed, `${namespace}.gate`) / 0x100000000;
    if (gate >= rule.fun_probability) return [entries.find((entry) => entry.id === rule.not_comedic_id)];
    return chooseMany(entries.filter((entry) => entry.fun), seed, namespace, rule.count, rule.count);
  }
  if (rule?.mode === "required_group_plus_any") {
    const first = choose(entries.filter((entry) => entry.group === rule.required_group), seed, `${namespace}.required`);
    const excludedGroups = new Set(rule.excluded_groups_from_extra || []);
    return [first, ...chooseMany(entries.filter((entry) => entry.id !== first.id && !excludedGroups.has(entry.group)), seed, `${namespace}.extra`, rule.count - 1, rule.count - 1)];
  }
  if (rule?.mode === "one_per_group") {
    return rule.groups.map((group) => choose(entries.filter((entry) => entry.group === group), seed, `${namespace}.${group}`));
  }
  throw new Error(`${namespace}: unsupported selection rule ${JSON.stringify(rule)}`);
}

/**
 * Load the three active dictionaries from `<root>/dict`.
 * @param {string} root
 * @returns {Promise<Record<string, object>>}
 */
async function loadDictionaries(root) {
  const dictDir = path.join(root, "dict");
  const entries = await Promise.all(DICT_NAMES.map(async (name) => [
    name,
    JSON.parse(await fs.readFile(path.join(dictDir, `${name}.json`), "utf8")),
  ]));
  return Object.fromEntries(entries);
}

function ids(value) {
  return Array.isArray(value) ? value.map((entry) => entry.id) : value?.id ?? null;
}

function canonicalize(value) {
  if (Array.isArray(value)) return value.map(canonicalize);
  if (!value || typeof value !== "object") return value;
  return Object.fromEntries(Object.keys(value).sort().map((key) => [key, canonicalize(value[key])]));
}

function fingerprint(selected) {
  const pickedIds = {
    requested_object: selected.requested_object?.label ?? null,
    gender: ids(selected.gender),
    age: selected.age,
    origin_world: ids(selected.origin_world),
    scene_lead: selected.scene_lead?.path ?? null,
    character: Object.fromEntries(Object.entries(selected.character).map(([key, value]) => [key, ids(value)])),
    inner_modifiers: Object.fromEntries(Object.entries(selected.inner_modifiers).map(([key, value]) => [key, ids(value)])),
    external_modifiers: Object.fromEntries(Object.entries(selected.external_modifiers).map(([key, value]) => [key, ids(value)])),
  };
  return crypto.createHash("sha256").update(JSON.stringify(canonicalize(pickedIds))).digest("hex");
}

function chooseSubjectKind(seed, requestedSubjectKind) {
  if (requestedSubjectKind && !["living", "made"].includes(requestedSubjectKind)) {
    throw new Error("subject-kind must be living or made");
  }
  return "living";
}
function namePool(dictionaries, genderId) {
  const entries = dictionaries.names?.sections?.[genderId]?.entries;
  if (!Array.isArray(entries) || !entries.length) throw new Error(`names dictionary has no ${genderId} pool`);
  return entries;
}
function rankNames(entries, seed) {
  return [...entries].sort((left, right) =>
    seededUint(seed, `identity.name.${left}`) - seededUint(seed, `identity.name.${right}`)
  );
}

function chooseSceneLead(selected, seed) {
  const candidates = [
    { path: `inner_modifiers.motivation.${selected.inner_modifiers.motivation.id}` },
    { path: `inner_modifiers.goal.${selected.inner_modifiers.goal.id}` },
    { path: `inner_modifiers.fear.${selected.inner_modifiers.fear.id}` },
    ...selected.inner_modifiers.interest.map((entry) => ({ path: `inner_modifiers.interest.${entry.id}` })),
    ...selected.external_modifiers.superpower.map((entry) => ({ path: `external_modifiers.superpower.${entry.id}` })),
  ];
  return choose(candidates, seed, "scene_lead");
}

/**
 * Build a deterministic, non-mutating deal.
 * @param {Record<string, object>} dictionaries result of loadDictionaries(root)
 * @param {{seed?: string, name?: string, subject?: string, subjectKind?: "living"|"made", gender?: string, age?: number, origin?: string}} options
 */
function buildPrimaryData(dictionaries, options = {}) {
  const seed = options.seed || crypto.randomUUID();
  const requestedName = String(options.name || "").trim() || null;
  const requestedSubject = String(options.subject || "").trim() || null;
  const requestedBrief = String(options.brief || "").trim() || null;
  const requestedSubjectKind = options.subjectKind || options["subject-kind"] || null;
  if (requestedSubjectKind === "made" && !requestedSubject) {
    throw new Error("subject-kind=made requires --subject=<requested object>");
  }
  const subjectKind = chooseSubjectKind(seed, requestedSubjectKind);

  const base = dictionaries.base_traits;
  const genderEntries = base.sections.gender.entries.filter((entry) =>
    entry.id !== "not_applicable");
  const gender = options.gender
    ? base.sections.gender.entries.find((entry) => entry.id === options.gender)
    : choose(genderEntries, seed, "base_traits.gender");
  if (!gender || !genderEntries.some((entry) => entry.id === gender.id)) throw new Error("gender incompatible with subject_kind");
  const name = requestedName || rankNames(namePool(dictionaries, gender.id), seed)[0];

  const selected = {
    subject_request: requestedSubjectKind === "made" ? null : requestedSubject,
    requested_object: requestedSubjectKind === "made" ? { id: "requested_object", label: requestedSubject, user_requested: true } : null,
    subject_kind: subjectKind,
    gender,
    age: options.age ? Number(options.age) : choose(base.sections.age.entries, seed, "base_traits.age"),
    origin_world: options.origin
      ? base.sections.origin_world.entries.find((entry) => entry.id === options.origin)
      : choose(base.sections.origin_world.entries, seed, "base_traits.origin_world"),
    character: Object.fromEntries(Object.entries(base.sections.character.axes).map(([name, section]) => [
      name, selectByRule(section.entries, base.selection.character[name], seed, `base_traits.character.${name}`),
    ])),
    inner_modifiers: Object.fromEntries(Object.entries(dictionaries.inner_modifiers.sections).map(([name, section]) => [
      name, selectByRule(section.entries, dictionaries.inner_modifiers.selection[name], seed, `inner_modifiers.${name}`),
    ])),
    external_modifiers: Object.fromEntries(Object.entries(dictionaries.external_modifiers.sections).map(([name, section]) => [
      name, selectByRule(section.entries, dictionaries.external_modifiers.selection[name], seed, `external_modifiers.${name}`),
    ])),
  };
  selected.scene_lead = chooseSceneLead(selected, seed);
  if (!base.sections.age.entries.includes(selected.age)) throw new Error("age is not in dictionary");
  if (!selected.origin_world) throw new Error("unknown origin");

  return {
    contract: "pet_generation_v2.primary_data",
    version: 1,
    seed,
    input: {
      name,
      name_source: requestedName ? "user" : "generated",
      subject: requestedSubject,
      subject_kind: requestedSubjectKind,
      gender: options.gender || null,
      age: options.age === undefined ? null : Number(options.age),
      origin: options.origin || null,
      brief: requestedBrief,
    },
    selected,
    fingerprint: fingerprint(selected),
    selection_provenance: {
      dictionaries: Object.fromEntries(Object.entries(dictionaries).map(([name, dictionary]) => [name, dictionary.contract])),
      rules: {
        base_traits: base.selection,
        inner_modifiers: dictionaries.inner_modifiers.selection,
        external_modifiers: dictionaries.external_modifiers.selection,
      },
    },
  };
}

/**
 * Build a series deal using a persisted, caller-supplied bag draw function.
 * Signatures match buildPrimaryData(dictionaries, options).
 */
async function buildPrimaryDataWithBags(dictionaries, { draw, drawName = draw, ...options } = {}) {
  if (typeof draw !== "function") throw new Error("A bag draw function is required");
  const seed = options.seed || crypto.randomUUID();
  const requestedName = String(options.name || "").trim() || null;
  const requestedSubject = String(options.subject || "").trim() || null;
  const requestedBrief = String(options.brief || "").trim() || null;
  const requestedSubjectKind = options.subjectKind || options["subject-kind"] || null;
  if (requestedSubjectKind === "made" && !requestedSubject) {
    throw new Error("subject-kind=made requires --subject=<requested object>");
  }
  const subjectKind = chooseSubjectKind(seed, requestedSubjectKind);
  const id = (entry) => String(entry.id ?? entry);
  async function pick(entries, namespace, excluded = []) {
    const selected = await draw({ entries: entries.map(id), eligibleEntries: entries.filter((item) => !excluded.includes(id(item))).map(id), namespace, seed });
    const result = entries.find((item) => id(item) === String(selected.id));
    if (!result) throw new Error(`${namespace}: bag returned unknown ID ${selected.id}`);
    return result;
  }
  async function pickByRule(entries, rule, namespace) {
    if (rule === "one") return pick(entries, namespace);
    if (rule?.mode === "exactly") {
      const output = [];
      for (let index = 0; index < rule.count; index += 1) output.push(await pick(entries, namespace, output.map(id)));
      return output;
    }
    if (rule?.mode === "fun_gate_then_exactly") {
      const gate = seededUint(seed, `${namespace}.gate`) / 0x100000000;
      if (gate >= rule.fun_probability) return [await pick(entries, namespace, entries.filter((item) => item.id !== rule.not_comedic_id).map(id))];
      const output = [];
      for (let index = 0; index < rule.count; index += 1) output.push(await pick(entries, namespace, entries.filter((item) => !item.fun).map(id).concat(output.map(id))));
      return output;
    }
    if (rule?.mode === "required_group_plus_any") {
      const required = await pick(entries.filter((item) => item.group === rule.required_group), `${namespace}.required`);
      const output = [required];
      const excludedGroups = new Set(rule.excluded_groups_from_extra || []);
      const extraEntries = entries.filter((item) => !excludedGroups.has(item.group));
      for (let index = 1; index < rule.count; index += 1) output.push(await pick(extraEntries, `${namespace}.extra`, output.map(id)));
      return output;
    }
    if (rule?.mode === "one_per_group") {
      const output = [];
      for (const group of rule.groups) output.push(await pick(entries, namespace, entries.filter((item) => item.group !== group).map(id)));
      return output;
    }
    throw new Error(`${namespace}: bag selection does not support ${JSON.stringify(rule)}`);
  }
  const base = dictionaries.base_traits;
  const genderEntries = base.sections.gender.entries.filter((entry) => entry.id !== "not_applicable");
  const gender = options.gender ? genderEntries.find((entry) => entry.id === options.gender) : await pick(genderEntries, "base_traits.gender");
  if (!gender) throw new Error("gender incompatible with subject_kind");
  const name = requestedName || (await drawName({
    entries: namePool(dictionaries, gender.id),
    namespace: `names.${gender.id}`,
    seed,
  })).id;
  const character = {};
  for (const [name, section] of Object.entries(base.sections.character.axes)) character[name] = await pickByRule(section.entries, base.selection.character[name], `base_traits.character.${name}`);
  const innerModifiers = {};
  for (const [name, section] of Object.entries(dictionaries.inner_modifiers.sections)) innerModifiers[name] = await pickByRule(section.entries, dictionaries.inner_modifiers.selection[name], `inner_modifiers.${name}`);
  const externalModifiers = {};
  for (const [name, section] of Object.entries(dictionaries.external_modifiers.sections)) externalModifiers[name] = await pickByRule(section.entries, dictionaries.external_modifiers.selection[name], `external_modifiers.${name}`);
  const selected = {
    subject_request: requestedSubjectKind === "made" ? null : requestedSubject,
    requested_object: requestedSubjectKind === "made" ? { id: "requested_object", label: requestedSubject, user_requested: true } : null,
    subject_kind: subjectKind, gender,
    age: options.age ? Number(options.age) : await pick(base.sections.age.entries, "base_traits.age"),
    origin_world: options.origin ? base.sections.origin_world.entries.find((entry) => entry.id === options.origin) : await pick(base.sections.origin_world.entries, "base_traits.origin_world"),
    character,
    inner_modifiers: innerModifiers,
    external_modifiers: externalModifiers,
  };
  selected.scene_lead = chooseSceneLead(selected, seed);
  if (!selected.origin_world) throw new Error("unknown origin");
  return {
    contract: "pet_generation_v2.primary_data", version: 1, seed,
    input: {
      name,
      name_source: requestedName ? "user" : "generated",
      subject: requestedSubject,
      subject_kind: requestedSubjectKind,
      gender: options.gender || null,
      age: options.age === undefined ? null : Number(options.age),
      origin: options.origin || null,
      brief: requestedBrief,
    }, selected, fingerprint: fingerprint(selected),
    selection_provenance: { mode: "persisted_bags", dictionaries: Object.fromEntries(Object.entries(dictionaries).map(([name, dictionary]) => [name, dictionary.contract])) },
  };
}

module.exports = { loadDictionaries, buildPrimaryData, buildPrimaryDataWithBags, fingerprint, seededUint, choose, chooseMany, selectByRule, ids, namePool, rankNames };
