const fs = require("node:fs");
const path = require("node:path");
const { loadEnvFileDefaults } = require("../../../extensions/02_tools_and_apps/_shared/env_file.js");
const { createVeoBridgeFromEnv } = require("../../../extensions/01_bridges/veo/bridge.js");

const ROOT = path.resolve(__dirname, "..", "..");
const PROMPT = [
  "Animate the character working with the three folding picture cards she holds.",
  "She carefully nudges one card sideways with one front foot, watches it slide, catches it, and aligns it beside the other two before continuing to study the sequence.",
  "Keep the camera locked and the framing stable.",
  "Preserve the exact character identity and keep exactly three cards with unchanged drawings.",
].join(" ");

function dataUrl(file) {
  const extension = path.extname(file).toLowerCase() === ".jpg" ? "jpeg" : "png";
  return `data:image/${extension};base64,${fs.readFileSync(file).toString("base64")}`;
}

async function main() {
  const characterDir = path.resolve(process.argv[2]);
  const source = path.join(characterDir, "canonical", "anchor_00.jpg");
  const outputDir = path.join(characterDir, "animation", "work_kling_motion_only_probe");
  fs.mkdirSync(outputDir, { recursive: true });

  const env = loadEnvFileDefaults({ env: process.env, workspaceRoot: path.resolve(ROOT, "..") });
  const model = env.KLING_MODEL || "kwaivgi/kling-v3.0-pro";
  const bridge = createVeoBridgeFromEnv({ env });
  const response = await bridge.submitVideo({
    payload: {
      model,
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
    prompt: PROMPT,
    model,
    duration: 5,
    prompt_timing: false,
  }, null, 2)}\n`);
  fs.writeFileSync(path.join(outputDir, "veo_job.json"), `${JSON.stringify(response.data, null, 2)}\n`);
  console.log(JSON.stringify({ status: "video-submitted", dir: outputDir, job: response.data.id }, null, 2));
}

main().catch((error) => {
  console.error(error.stack || error.message);
  process.exitCode = 1;
});
