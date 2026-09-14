const crypto = require("node:crypto");
const fs = require("node:fs/promises");
const path = require("node:path");
const { loadEnvFileDefaults } = require("../../extensions/02_tools_and_apps/_shared/env_file.js");
const { runProductionAnimation, resolveProductionAnimation } = require("../lib/production_animation.js");

const ROOT = path.resolve(__dirname, "..");
const PRICE_PER_SECOND_USD = 0.15225;

async function main() {
  const options = Object.fromEntries(process.argv.slice(2).filter((value) => value.startsWith("--") && value.includes("=")).map((value) => value.slice(2).split(/=(.*)/s)));
  if (!options.character || options["submit-confirmed"] !== "yes") {
    throw new Error("Usage: --character=<dir> --submit-confirmed=yes [--batch-dir=<folder>]");
  }
  const characterDir = path.resolve(options.character);
  const catalog = JSON.parse(await fs.readFile(path.join(ROOT, "dict", "animation_prompt_catalog.json"), "utf8"));
  const selected = catalog.entries.filter((entry) => entry.availability === "enabled");
  const totalSeconds = selected.reduce((total, entry) => total + entry.duration_seconds, 0);
  const stamp = new Date().toISOString().slice(0, 10).replaceAll("-", "");
  const batchFolder = options["batch-dir"] || `catalog-v${catalog.version}-${stamp}-batch`;
  const animationRoot = path.resolve(characterDir, "animation");
  const batchDir = path.resolve(animationRoot, batchFolder);
  if (!batchDir.startsWith(`${animationRoot}${path.sep}`)) throw new Error("--batch-dir must be inside the character animation directory");
  await fs.mkdir(batchDir, { recursive: true });
  const batch = {
    contract: "pet_generation_v2.catalog_batch.v1",
    character: path.basename(characterDir),
    catalog_version: catalog.version,
    created_at: new Date().toISOString(),
    output_directory: batchDir,
    state_count: selected.length,
    total_seconds: totalSeconds,
    estimated_price_per_second_usd: PRICE_PER_SECOND_USD,
    estimated_total_usd: Number((totalSeconds * PRICE_PER_SECOND_USD).toFixed(4)),
    states: [],
  };
  const env = loadEnvFileDefaults({ env: process.env, workspaceRoot: path.resolve(ROOT, "..") });
  for (const entry of selected) {
    const output = path.join(batchFolder, entry.id);
    const setup = await resolveProductionAnimation({
      root: ROOT, env, characterDir, animationId: entry.id,
      options: { verification: true, allowPendingCanonical: true, "animation-output": output },
    });
    const result = await runProductionAnimation({
      root: ROOT, env, characterDir, animationId: entry.id,
      options: { verification: true, allowPendingCanonical: true, "animation-output": output },
    });
    batch.states.push({
      id: entry.id,
      output,
      duration_seconds: setup.catalogEntry.duration_seconds,
      estimated_cost_usd: Number((setup.catalogEntry.duration_seconds * PRICE_PER_SECOND_USD).toFixed(4)),
      status: result.status,
      job: result.job || null,
    });
    await fs.writeFile(path.join(batchDir, "batch.json"), `${JSON.stringify(batch, null, 2)}\n`);
  }
  console.log(JSON.stringify(batch, null, 2));
}

main().catch((error) => { console.error(error.stack || error.message); process.exitCode = 1; });
