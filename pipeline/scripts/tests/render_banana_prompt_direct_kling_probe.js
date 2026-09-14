const fs = require("node:fs");
const path = require("node:path");
const { loadEnvFileDefaults } = require("../../../extensions/02_tools_and_apps/_shared/env_file.js");
const { createVeoBridgeFromEnv } = require("../../../extensions/01_bridges/veo/bridge.js");

const ROOT = path.resolve(__dirname, "..", "..");
const WORK_APEX = "Klara carefully nudges one of the three folding picture cards sideways with one front foot, catches it, and aligns it beside the other two while studying the sequence.";
const WORK_DRAWING_APEX = "Klara continues drawing on the separate square card. Her right front foot carefully guides the same glowing blue drawing tool across that card to complete one simple leaf symbol while her eyes follow the tip. Her left front foot keeps the existing connected three-panel picture card still. End with the drawing tool lifted slightly above the completed mark.";
const WORK_RESTART_APEX = "Klara continues drawing on the separate square sheet with the same glowing blue drawing tool. She studies the result, gives one small dissatisfied head shake, crumples only that loose square sheet into one paper ball, and moves it out of frame. She places one fresh identical square sheet in the same position and begins the same first blue stroke again, returning to the exact starting pose. The connected three-panel picture card remains unchanged and stationary throughout.";
const SLEEP_APEX = "Klara grows sleepy, folds the three picture cards together and places them directly beside her shell, then slowly retracts her head, neck, and all four legs completely into the shell and remains asleep.";
const IDLE_APEX = "Klara waits calmly, glances across the three picture cards without moving them, slightly draws back her free front foot as if pre-calibrating a future push, relaxes it, blinks once, and returns to the exact starting pose for a seamless loop.";

function dataUrl(file) {
  const extension = path.extname(file).toLowerCase() === ".jpg" ? "jpeg" : "png";
  return `data:image/${extension};base64,${fs.readFileSync(file).toString("base64")}`;
}

async function main() {
  const characterDir = path.resolve(process.argv[2]);
  const sleep = process.argv.includes("--sleep");
  const idle = process.argv.includes("--idle");
  const workDrawing = process.argv.includes("--work-drawing");
  const workRestart = process.argv.includes("--work-restart");
  const requestedModel = process.argv.find((argument) => argument.startsWith("--model="))?.slice("--model=".length);
  const apex = idle ? IDLE_APEX : sleep ? SLEEP_APEX : workRestart ? WORK_RESTART_APEX : workDrawing ? WORK_DRAWING_APEX : WORK_APEX;
  const prompt = `Render only this animation apex: ${apex} Preserve every visual detail from the attached canonical anchor exactly: do not add, remove, duplicate, or deform any limb, prop, attached item, or body part. Keep each prop in its canonical placement unless this apex explicitly gives that prop a logical state change; only an explicit directive may stow it, conceal it, release it, or move it offscreen. Never make a prop the focus merely because it appears on the anchor. Make the requested objective or recognizable character solution clearly readable. One full character, centered at fixed widget scale with safe margins, solid #00FF00 background, no floor, shadow, text, UI, or extra creatures.`;
  if (prompt.length > 2500) throw new Error(`Kling prompt exceeds limit: ${prompt.length}`);
  const source = workDrawing || workRestart
    ? path.join(characterDir, "animation", "_assets", "minimal_work_v3", "anchor_minimal_work_v3.jpg")
    : path.join(characterDir, "canonical", "anchor_00.jpg");
  const duration = workRestart ? 8 : 5;
  const env = loadEnvFileDefaults({ env: process.env, workspaceRoot: path.resolve(ROOT, "..") });
  const model = requestedModel || env.KLING_MODEL || "kwaivgi/kling-v3.0-pro";
  const providerLabel = model === "bytedance/seedance-2.0" ? "seedance_2" : "kling";
  const outputDir = path.join(characterDir, "animation", idle ? `idle_${providerLabel}_direct_banana_prompt_probe` : sleep ? `sleep_${providerLabel}_direct_banana_prompt_probe` : workRestart ? `work_${providerLabel}_draw_crumple_restart_loop_probe` : workDrawing ? `work_${providerLabel}_minimal_work_v3_probe` : `work_${providerLabel}_direct_banana_prompt_probe`);
  fs.mkdirSync(outputDir, { recursive: true });

  const bridge = createVeoBridgeFromEnv({ env });
  const response = await bridge.submitVideo({
    payload: {
      model,
      prompt,
      frame_images: [{
        type: "image_url",
        image_url: { url: dataUrl(source) },
        frame_type: "first_frame",
      }, ...(workRestart ? [{
        type: "image_url",
        image_url: { url: dataUrl(source) },
        frame_type: "last_frame",
      }] : [])],
      aspect_ratio: "1:1",
      duration,
      generate_audio: false,
    },
    timeoutMs: 30000,
  });

  fs.writeFileSync(path.join(outputDir, "request.json"), `${JSON.stringify({
    source,
    apex,
    prompt,
    prompt_source: "run_character_pipeline.js renderKeyframes template",
    model,
    duration,
  }, null, 2)}\n`);
  fs.writeFileSync(path.join(outputDir, "veo_job.json"), `${JSON.stringify(response.data, null, 2)}\n`);
  console.log(JSON.stringify({ status: "video-submitted", dir: outputDir, job: response.data.id }, null, 2));
}

main().catch((error) => {
  console.error(error.stack || error.message);
  if (error.body !== undefined) console.error(`PROVIDER_ERROR_BODY ${JSON.stringify(error.body)}`);
  process.exitCode = 1;
});
