const crypto = require("node:crypto");
const Ajv = require("ajv");
const fs = require("node:fs");
const path = require("node:path");

const CONTRACT = "pet_generation_v2.animation_prompt_catalog.v1";
const PROMPT_LIMIT = 900;
const PLACEHOLDERS = new Set();
const hash = (value) => crypto.createHash("sha256").update(String(value)).digest("hex");
const SHARED_CONSTANT_LEAKS = [/natural to this character/i, /opening pose/i, /the camera/i];
const sourceSnapshot = (entry) => ({ id: entry.id, function: entry.function, expected: entry.expected, group: entry.group, physics: entry.physics });

function resolveTemplate(template) {
  const value = String(template);
  if (/\{[^}]+\}/.test(value)) throw new Error("Character-specific prompt placeholders are prohibited");
  return value;
}

function validateCatalog(catalog, animationDictionary) {
  const schema = JSON.parse(fs.readFileSync(path.join(__dirname, "..", "schema", "animation_prompt_catalog.schema.json"), "utf8"));
  const validate = new Ajv({ strict: false }).compile(schema);
  if (!validate(catalog)) throw new Error(`Prompt catalog schema invalid: ${validate.errors.map((error) => error.message).join("; ")}`);
  if (catalog?.contract !== CONTRACT || !Array.isArray(catalog.entries)) throw new Error("Invalid animation prompt catalog contract");
  for (const part of ["prefix", "suffix"]) {
    if (typeof catalog[part] !== "string" || !catalog[part].trim() || /[Ѐ-ӿ]/u.test(catalog[part])) throw new Error(`Invalid catalog ${part}`);
    resolveTemplate(catalog[part]);
  }
  const animations = animationDictionary.entries || [];
  if (catalog.entries.map((entry) => entry.id).join("|") !== animations.map((entry) => entry.id).join("|")) throw new Error("Prompt catalog IDs must exactly match animation dictionary order");
  for (let index = 0; index < catalog.entries.length; index += 1) {
    const entry = catalog.entries[index];
    const animation = animations[index];
    if (typeof entry.template !== "string" || !entry.template.trim() || /[\u0400-\u04FF]/u.test(entry.template)) throw new Error(`Invalid template for ${entry.id}`);
    if (!Number.isInteger(entry.duration_seconds) || entry.duration_seconds < animation.duration.min_seconds || entry.duration_seconds > animation.duration.max_seconds) throw new Error(`Invalid duration for ${entry.id}`);
    if (/\{[^}]+\}/.test(entry.template)) throw new Error(`Character-specific prompt placeholder in ${entry.id}`);
    if (SHARED_CONSTANT_LEAKS.some((pattern) => pattern.test(entry.template))) throw new Error(`Shared prefix/suffix wording leaked into the ${entry.id} beat`);
    if ((entry.availability === "disabled") !== (entry.verified?.verdict === "rejected")) throw new Error(`Availability and verification verdict disagree for ${entry.id}`);
    const resolved = `${catalog.prefix} ${entry.template} ${catalog.suffix}`;
    if (resolved.length > PROMPT_LIMIT) throw new Error(`Resolved prompt for ${entry.id} exceeds ${PROMPT_LIMIT} characters: ${resolved.length}`);
    if (entry.source_hash !== hash(JSON.stringify(sourceSnapshot(animation)))) throw new Error(`Stale source hash for ${entry.id}`);
  }
  return catalog;
}

module.exports = { CONTRACT, PLACEHOLDERS, PROMPT_LIMIT, SHARED_CONSTANT_LEAKS, hash, sourceSnapshot, resolveTemplate, validateCatalog };
