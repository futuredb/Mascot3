const fs = require("node:fs");
const path = require("node:path");
const { loadEnvFileDefaults } = require("../../../extensions/02_tools_and_apps/_shared/env_file.js");
const { createVeoBridgeFromEnv } = require("../../../extensions/01_bridges/veo/bridge.js");

const MODEL = "bytedance/seedance-2.0";
const DURATION = 8;
const catalog = JSON.parse(fs.readFileSync(path.join(__dirname, "..", "..", "dict", "animation_prompt_catalog.json"), "utf8"));
const workTemplate = catalog.entries.find((entry) => entry.id === "work")?.template;
if (!workTemplate) throw new Error("Animation prompt catalog has no work template");
const PROMPT = `${workTemplate} ${catalog.suffix}`;

function dataUrl(file) {
  const extension = path.extname(file).toLowerCase();
  const mime = extension === ".png" ? "image/png" : "image/jpeg";
  return `data:${mime};base64,${fs.readFileSync(file).toString("base64")}`;
}

async function main() {
  const [sourceArg, outputArg] = process.argv.slice(2);
  if (!sourceArg || !outputArg) {
    throw new Error("Usage: node render_minimal_seedance_probe.js <source-image> <output-dir>");
  }
  const source = path.resolve(sourceArg);
  const outputDir = path.resolve(outputArg);
  if (!fs.existsSync(source)) throw new Error(`Missing source image: ${source}`);

  const env = loadEnvFileDefaults({ env: process.env, workspaceRoot: path.resolve(__dirname, "..", "..", "..") });
  const bridge = createVeoBridgeFromEnv({ env });
  fs.mkdirSync(outputDir, { recursive: true });

  const request = {
    model: MODEL,
    prompt: PROMPT,
    frame_images: [
      { type: "image_url", image_url: { url: dataUrl(source) }, frame_type: "first_frame" },
      { type: "image_url", image_url: { url: dataUrl(source) }, frame_type: "last_frame" },
    ],
    aspect_ratio: "1:1",
    duration: DURATION,
    generate_audio: false,
  };
  const response = await bridge.submitVideo({ payload: request, timeoutMs: 30000 });
  fs.writeFileSync(path.join(outputDir, "request.json"), `${JSON.stringify({
    ...request,
    frame_images: [
      { source, frame_type: "first_frame" },
      { source, frame_type: "last_frame" },
    ],
  }, null, 2)}\n`);
  fs.writeFileSync(path.join(outputDir, "veo_job.json"), `${JSON.stringify(response.data, null, 2)}\n`);
  console.log(JSON.stringify({ status: response.data.status, job: response.data.id, outputDir, prompt: PROMPT }, null, 2));
}

main().catch((error) => {
  console.error(error.stack || error.message);
  if (error.body !== undefined) console.error(`PROVIDER_ERROR_BODY ${JSON.stringify(error.body)}`);
  process.exitCode = 1;
});
