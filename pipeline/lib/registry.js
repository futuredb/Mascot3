const crypto = require("node:crypto");
const fs = require("node:fs/promises");
const path = require("node:path");

async function readJson(file, fallback) {
  try { return JSON.parse(await fs.readFile(file, "utf8")); } catch (error) {
    if (error.code === "ENOENT") return fallback;
    throw error;
  }
}
async function writeJson(file, value) {
  await fs.mkdir(path.dirname(file), { recursive: true });
  await fs.writeFile(file, `${JSON.stringify(value, null, 2)}\n`);
}
function corpusHash(dictionaries) {
  return crypto.createHash("sha256").update(JSON.stringify(dictionaries)).digest("hex");
}
async function claimFingerprint(file, fingerprint, metadata) {
  const registry = await readJson(file, { fingerprints: {} });
  if (registry.fingerprints[fingerprint]) throw new Error(`Duplicate fingerprint: ${fingerprint}`);
  registry.fingerprints[fingerprint] = metadata;
  await writeJson(file, registry);
}
async function claimName(file, name, fingerprint) {
  const registry = await readJson(file, { names: {} });
  const key = String(name).trim().toLowerCase();
  if (registry.names[key] && registry.names[key] !== fingerprint) return false;
  registry.names[key] = fingerprint;
  await writeJson(file, registry);
  return true;
}
async function drawBag(file, { corpus_version, namespace, entries, eligibleEntries = entries, seed }) {
  const entryIds = entries.map(String);
  const eligibleIds = new Set(eligibleEntries.map(String));
  if (!entryIds.length || !eligibleIds.size) throw new Error(`Bag ${namespace} has no eligible entries`);
  const state = await readJson(file, { bags: {} });
  const key = `${corpus_version}:${namespace}`;
  let bag = state.bags[key];
  if (!bag || bag.remaining.some((id) => !entryIds.includes(String(id)))) {
    const ranked = entryIds.map((id) => ({
      id,
      rank: crypto.createHash("sha256").update(`${corpus_version}:${namespace}:${seed}:${id}`).digest("hex"),
    })).sort((a, b) => a.rank.localeCompare(b.rank));
    bag = { remaining: ranked.map((item) => item.id), cycles: (bag?.cycles || 0) + 1 };
  }
  let index = bag.remaining.findIndex((id) => eligibleIds.has(String(id)));
  if (index < 0) {
    const ranked = entryIds.map((id) => ({
      id,
      rank: crypto.createHash("sha256").update(`${corpus_version}:${namespace}:${bag.cycles + 1}:${id}`).digest("hex"),
    })).sort((a, b) => a.rank.localeCompare(b.rank));
    bag = { remaining: ranked.map((item) => item.id), cycles: bag.cycles + 1 };
    index = bag.remaining.findIndex((id) => eligibleIds.has(String(id)));
  }
  const [id] = bag.remaining.splice(index, 1);
  state.bags[key] = bag;
  await writeJson(file, state);
  return { id, cycle: bag.cycles, remaining: bag.remaining.length };
}
module.exports = { corpusHash, claimFingerprint, claimName, drawBag, readJson, writeJson };
