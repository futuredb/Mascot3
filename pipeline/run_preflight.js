const fs = require("node:fs/promises");
const path = require("node:path");
const { loadEnvFileDefaults } = require("../extensions/02_tools_and_apps/_shared/env_file.js");
const { createNanobananaBridgeFromEnv } = require("../extensions/01_bridges/nanobanana/bridge.js");

const ROOT = __dirname;
function parse(argv) { return Object.fromEntries(argv.map((arg) => arg.replace(/^--/, "").split("="))); }
async function main() {
  const options = parse(process.argv.slice(2));
  const kind = options.kind || "cost";
  const output = path.join(ROOT, "preflight", `${new Date().toISOString().replace(/[:.]/g, "-")}_${kind}`);
  await fs.mkdir(output, { recursive: true });
  const costPlan = { kind, text_jobs: kind === "model-ab" ? 1 : 0, image_jobs: kind === "chroma" ? 1 : 0, video_jobs: kind === "chroma" ? 1 : 0, requires_confirmation: true };
  await fs.writeFile(path.join(output, "cost_plan.json"), `${JSON.stringify(costPlan, null, 2)}\n`);
  if (options.confirm !== "paid") return console.log(JSON.stringify({ status: "planned", output, costPlan }, null, 2));
  if (kind !== "chroma") throw new Error("Only chroma preflight is automated here; model A/B needs the archived control request path supplied explicitly.");
  const env = loadEnvFileDefaults({ env: process.env, workspaceRoot: path.resolve(ROOT, "..") });
  const bridge = createNanobananaBridgeFromEnv({ env });
  const prompt = "Synthetic alpha/chroma QA image: one friendly character with a translucent outer layer, a sharp translucent force shield, semi-transparent living afterimages, a broad soft elemental aura, and bright hard-light geometric constructions. Flat chroma background #00ff00; no floor, no shadow. Preserve semi-transparent edges.";
  const response = await bridge.render({ payload: { messages: [{ role: "user", content: [{ type: "text", text: prompt }] }], modalities: ["image", "text"], image_config: { aspect_ratio: "1:1" }, stream: false }, timeoutMs: 300000 });
  await fs.writeFile(path.join(output, "response.json"), `${JSON.stringify(response.data, null, 2)}\n`);
  console.log(JSON.stringify({ status: "screening-rendered", output }, null, 2));
}
main().catch((error) => { console.error(error.stack || error.message); process.exitCode = 1; });
