const fs = require("node:fs/promises");
const fssync = require("node:fs");
const path = require("node:path");
const { loadEnvFileDefaults } = require("../../extensions/02_tools_and_apps/_shared/env_file.js");
const { createVeoBridgeFromEnv } = require("../../extensions/01_bridges/veo/bridge.js");

const ROOT = path.resolve(__dirname, "..");

async function main() {
  const options = Object.fromEntries(process.argv.slice(2).filter((value) => value.startsWith("--") && value.includes("=")).map((value) => value.slice(2).split(/=(.*)/s)));
  if (!options.character) throw new Error("Usage: --character=<dir> [--batch-dir=<folder>]");
  const animationDir = path.join(path.resolve(options.character), "animation");
  const batchPath = options["batch-dir"]
    ? path.join(animationDir, options["batch-dir"], "batch.json")
    : path.join(animationDir, fssync.readdirSync(animationDir).filter((name) => name.endsWith("-batch.json")).sort().at(-1) || "");
  if (!fssync.existsSync(batchPath)) throw new Error("No catalog batch manifest found");
  const batch = JSON.parse(await fs.readFile(batchPath, "utf8"));
  const env = loadEnvFileDefaults({ env: process.env, workspaceRoot: path.resolve(ROOT, "..") });
  const veo = createVeoBridgeFromEnv({ env });
  const states = await Promise.all(batch.states.map(async (state) => {
    const status = await veo.getVideoJob(state.job, { timeoutMs: 30000 });
    return { id: state.id, job: state.job, status: status.data?.status || "unknown", cost_usd: status.data?.usage?.cost ?? null };
  }));
  const counts = states.reduce((total, state) => ({ ...total, [state.status]: (total[state.status] || 0) + 1 }), {});
  console.log(JSON.stringify({ batch: path.relative(animationDir, batchPath), counts, states }, null, 2));
}

main().catch((error) => { console.error(error.stack || error.message); process.exitCode = 1; });
