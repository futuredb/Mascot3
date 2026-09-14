const path = require("node:path");
const fs = require("node:fs/promises");
const { runProductionAnimation } = require("../lib/production_animation.js");
const { loadEnvFileDefaults } = require("../../extensions/02_tools_and_apps/_shared/env_file.js");
const { videoFramingQa } = require("../lib/framing.js");
const { hash } = require("../lib/prompt_catalog.js");

async function main() {
  const options = Object.fromEntries(process.argv.slice(2).filter((value) => value.startsWith("--") && value.includes("=")).map((value) => value.slice(2).split(/=(.*)/s)));
  if (!options.character || !options.animation) throw new Error("Usage: --character=<dir> --animation=<id> [--submit-confirmed=yes|--accept=yes] [--allow-disabled=yes] [--allow-pending-canonical=yes]");
  const root = path.resolve(__dirname, "..");
  const catalogFile = path.join(root, "dict", "animation_prompt_catalog.json");
  const catalog = JSON.parse(await fs.readFile(catalogFile, "utf8"));
  const entry = catalog.entries.find((item) => item.id === options.animation);
  if (!entry) throw new Error(`Unknown animation: ${options.animation}`);
  if (options.accept === "yes") {
    if (entry.verified?.verdict !== "pending_manual_review" || !entry.verified.framing_qa?.framing_pass) throw new Error("Acceptance requires pending manual review with passing framing QA");
    entry.verified.verdict = "accepted"; entry.verified.notes = options.notes || "Accepted by operator";
    await fs.writeFile(catalogFile, `${JSON.stringify(catalog, null, 2)}\n`);
    console.log(JSON.stringify({ status: "accepted", animation: entry.id }, null, 2)); return;
  }
  if (options["submit-confirmed"] !== "yes") {
    throw new Error("Verification runner is dry-run only until explicit user approval: pass --submit-confirmed=yes after approval");
  }
  const env = loadEnvFileDefaults({ env: process.env, workspaceRoot: path.resolve(root, "..") });
  const result = await runProductionAnimation({
    root, env, characterDir: path.resolve(options.character), animationId: options.animation,
    options: {
      verification: true,
      seed: options.seed,
      "animation-output": options["animation-output"],
      allowDisabledVerification: options["allow-disabled"] === "yes",
      allowPendingCanonical: options["allow-pending-canonical"] === "yes",
    },
  });
  if (result.status === "assembled") {
    const video = path.join(result.animationDir, result.video);
    const framing = videoFramingQa(video);
    entry.verified = { verdict: framing.framing_pass ? "pending_manual_review" : "rejected", job: result.job || null, date: new Date().toISOString(), framing_qa: framing, template_hash: hash(entry.template), source_hash: entry.source_hash };
    entry.availability = framing.framing_pass ? "enabled" : "disabled";
    await fs.writeFile(catalogFile, `${JSON.stringify(catalog, null, 2)}\n`);
  }
  console.log(JSON.stringify(result, null, 2));
}
main().catch((error) => { console.error(error.message); process.exitCode = 1; });
