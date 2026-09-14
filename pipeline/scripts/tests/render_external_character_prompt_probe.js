const fs = require("node:fs");
const path = require("node:path");
const { loadEnvFileDefaults } = require("../../../extensions/02_tools_and_apps/_shared/env_file.js");
const { createVeoBridgeFromEnv } = require("../../../extensions/01_bridges/veo/bridge.js");

const ROOT = path.resolve(__dirname, "..", "..");
const SOURCE_URL = "https://picsart.com/blog/kling-ai-prompts/";
const PROMPT = "A runner moves across the frame in slow motion. Each step lands clearly with visible weight and dust lifting slightly from the ground.";

function dataUrl(file) {
  const extension = path.extname(file).toLowerCase() === ".jpg" ? "jpeg" : "png";
  return `data:image/${extension};base64,${fs.readFileSync(file).toString("base64")}`;
}

async function main() {
  const characterDir = path.resolve(process.argv[2]);
  const source = path.join(characterDir, "animation", "_assets", "clean_side", "anchor_clean.jpg");
  const outputDir = path.join(characterDir, "animation", "external_copy_prompt_weighted_run_probe");
  fs.mkdirSync(outputDir, { recursive: true });

  const env = loadEnvFileDefaults({ env: process.env, workspaceRoot: path.resolve(ROOT, "..") });
  const bridge = createVeoBridgeFromEnv({ env });
  const response = await bridge.submitVideo({
    payload: {
      model: env.KLING_MODEL || "kwaivgi/kling-v3.0-pro",
      prompt: PROMPT,
      frame_images: [{
        type: "image_url",
        image_url: { url: dataUrl(source) },
        frame_type: "first_frame",
      }],
      aspect_ratio: "1:1",
      duration: 5,
      generate_audio: false,
    },
    timeoutMs: 30000,
  });

  fs.writeFileSync(path.join(outputDir, "request.json"), `${JSON.stringify({
    source,
    source_url: SOURCE_URL,
    prompt: PROMPT,
    prompt_modified: false,
    model: env.KLING_MODEL || "kwaivgi/kling-v3.0-pro",
    duration: 5,
  }, null, 2)}\n`);
  fs.writeFileSync(path.join(outputDir, "veo_job.json"), `${JSON.stringify(response.data, null, 2)}\n`);
  console.log(JSON.stringify({ status: "video-submitted", dir: outputDir, job: response.data.id }, null, 2));
}

main().catch((error) => {
  console.error(error.stack || error.message);
  process.exitCode = 1;
});
