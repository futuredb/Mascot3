const fs = require("node:fs/promises");
const path = require("node:path");
const { CONTRACT, PROMPT_LIMIT, hash, sourceSnapshot, validateCatalog } = require("../lib/prompt_catalog.js");

const ROOT = path.resolve(__dirname, "..");
const MODEL = "handwritten";
const output = path.join(ROOT, "dict", "animation_prompt_catalog.json");
const auditOutput = path.join(ROOT, "dict", "animation_prompt_catalog.audit.json");
const check = process.argv.includes("--check");
const writeAudit = process.argv.includes("--write-audit");

const prefix = "The character in the picture moves with the body it actually has.";
const suffix = "It floats free in open space with nothing beneath it. Movement runs through it the whole time and never settles. Its body holds together as one shape, and it moves and poses as big and as freely as it likes while staying inside the picture. Whatever hangs loose on it swings after it and settles a moment later. Anything this description names drifts in when it is wanted, moves as it is used, and drifts off again by the end; nothing else appears, and the flat empty background behind it stays exactly as it is. The camera does not move.";

// Every beat below is written by hand and read aloud. If a clause does not make a
// picture on its own, it does not belong here.
const templates = {
  idle: "It settles into a habit it knows by heart, works through it, and begins again. Easy and unhurried.",
  rest: "It lets its tension go and hangs loose, drifting gently, still awake. Slow and unwinding.",
  sleep: "It is fast asleep. Only its slow breathing moves it, in and out, the whole time. Very slow.",
  thinking: "It is weighing something up. It leans slowly over to one side as if that were the answer, then just as slowly over to the other, unable to settle on either. Slow and absorbed.",
  at_glass: "It leans into the empty air right in front of it as if it will not give, pushes, works its way sideways, and pushes again. Steady and patient.",
  watching: "It is searching for something out beyond the picture. It looks hard to one side, then brings the front of its body across to look just as hard the other way, checking each direction before moving on. Alert and intent.",
  work: "It settles into whatever position suits typing best, then works a keyboard in front of it, hitting it hard and fast with its whole body, glancing at what comes out and driving straight back in. Quick and tireless.",
  happy: "Delight runs through it in waves and it bounces with each one, unable to keep still. Light and lively.",
  sad: "It is grieving and cannot shake it off. It gathers itself as if to lift, gives up part way, and sinks back down smaller and more closed than before. Slow and quiet.",
  angry: "It is furious. The anger keeps driving out of it, hard, over and over, and never works itself out. Sharp and stubborn.",
  refusal: "It turns hard away and shuts itself tight. Asked again, it turns further off and shuts tighter, and will not be talked round. Hard and final.",
  frightened: "Something beyond the picture frightens it. It shrinks back, stays drawn in and watchful, and cannot settle. A sharp start, then wary.",
  curious: "It notices something already in the picture and studies it closely, trying one way of looking at it and then another. Alert and unhurried.",
  tender: "It draws itself in and wraps its own body up, holding itself carefully, easing open a little and folding back into the same protective curl. Slow and gentle.",
  stretch: "It stretches itself out long and slow, pressing a little further at the far end, then eases back and stretches again. Long and luxurious.",
  greeting: "It notices you and greets you with clear pleasure, in whatever way is natural to it, and keeps the welcome going. Warm and lively.",
  signature_move: "It does the move that is entirely its own, the one it is proud of, runs it through and starts it over. Rhythmic and confident.",
  playful: "It plays with its own momentum, tipping and catching itself, staying near where it began, and keeps the game going. Quick and springy.",
};

// Only rules that encode a failure we actually measured on a real clip, plus the
// contract rules the pipeline depends on. Everything else is a matter of reading
// the prompt, not of matching a pattern.
const ABSENT_GEOMETRY = /\b(?:glass|floor|ground|wall|mirror|reflection|shadow|window|pane|ledge|edge|surface|barrier)\b/i;
const IDENTITY_LEAK = /\b(?:he|she|his|her|him|hers|they|their|theirs)\b/i;
const lowEnergyIds = new Set(["idle", "rest", "sleep", "thinking", "at_glass", "watching", "sad", "refusal", "frightened", "curious", "tender", "greeting"]);

function auditEntry(entry, animation, catalogSuffix = suffix, catalogPrefix = prefix) {
  const failures = [];
  const resolved = `${catalogPrefix} ${entry.template} ${catalogSuffix}`;
  // Measured on catalog-v18 work/sleep: a literal pane of glass, a reflection and a
  // floor to stand on all appeared because the prompt named things absent from the keyframe.
  if (ABSENT_GEOMETRY.test(resolved)) failures.push(`assembled prompt names something absent from the keyframe: ${resolved.match(ABSENT_GEOMETRY)[0]}`);
  // Contract: one catalog serves every character.
  if (/\{[^}]+\}/.test(entry.template)) failures.push("template contains a character-specific placeholder");
  if (IDENTITY_LEAK.test(entry.template)) failures.push("template contains a gendered pronoun");
  if (/\b(first_frame|last_frame|anchor|chroma|green background)\b/i.test(resolved)) failures.push("prompt contains payload-only language");
  if (!catalogSuffix.endsWith("The camera does not move.")) failures.push("suffix must end with the static-camera sentence");
  if (!/the flat empty background behind it stays exactly as it is/i.test(catalogSuffix)) failures.push("suffix must hold the keyframe background still: v38 work repainted it into a scene");
  if (!/nothing else appears/i.test(catalogSuffix)) failures.push("suffix must still forbid anything the beat has not named");
  if (!/drifts in when it is wanted.*drifts off again by the end/i.test(catalogSuffix)) failures.push("suffix must give named objects an arrival, a use and an exit, because the clip ends on a keyframe that holds none of them");
  if (resolved.length > PROMPT_LIMIT) failures.push(`assembled prompt exceeds ${PROMPT_LIMIT} characters: ${resolved.length}`);
  if (entry.duration_seconds < animation.duration.min_seconds || entry.duration_seconds > animation.duration.max_seconds) failures.push("duration outside dictionary bounds");
  return failures;
}

async function main() {
  const animations = JSON.parse(await fs.readFile(path.join(ROOT, "dict", "animations.json"), "utf8"));
  const existing = JSON.parse(await fs.readFile(output, "utf8"));
  if (check) {
    const catalog = JSON.parse(await fs.readFile(output, "utf8"));
    validateCatalog(catalog, animations);
    const audit = catalog.entries.map((entry, index) => ({ id: entry.id, failures: auditEntry(entry, animations.entries[index], catalog.suffix, catalog.prefix) }));
    if (audit.some((item) => item.failures.length)) throw new Error(`Catalog audit failed: ${JSON.stringify(audit.filter((item) => item.failures.length))}`);
    if (writeAudit) await fs.writeFile(auditOutput, `${JSON.stringify({ contract: `${CONTRACT}.lint.v1`, model: catalog.model, entries: audit }, null, 2)}\n`);
    console.log(JSON.stringify({ status: "valid", entries: catalog.entries.length }, null, 2));
    return;
  }
  const entries = animations.entries.map((animation) => {
    const template = templates[animation.id];
    if (!template) throw new Error(`No hand-written template for ${animation.id}`);
    return {
      id: animation.id,
      availability: "enabled",
      template,
      duration_seconds: lowEnergyIds.has(animation.id) ? 6 : 8,
      source_hash: hash(JSON.stringify(sourceSnapshot(animation))),
      verified: { verdict: "pending_verification" },
    };
  });
  const catalog = { contract: CONTRACT, version: existing.version + 1, model: MODEL, prefix, suffix, entries };
  validateCatalog(catalog, animations);
  const audit = entries.map((entry, index) => ({ id: entry.id, failures: auditEntry(entry, animations.entries[index]) }));
  if (audit.some((item) => item.failures.length)) throw new Error(`Catalog audit failed: ${JSON.stringify(audit.filter((item) => item.failures.length))}`);
  await fs.writeFile(output, `${JSON.stringify(catalog, null, 2)}\n`);
  await fs.writeFile(auditOutput, `${JSON.stringify({ contract: `${CONTRACT}.lint.v1`, model: MODEL, entries: audit }, null, 2)}\n`);
  console.log(JSON.stringify({ status: "generated", entries: catalog.entries.length, output }, null, 2));
}
main().catch((error) => { console.error(error.stack || error.message); process.exitCode = 1; });
