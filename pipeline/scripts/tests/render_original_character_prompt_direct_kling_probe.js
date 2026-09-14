const fs = require("node:fs");
const path = require("node:path");
const { loadEnvFileDefaults } = require("../../../extensions/02_tools_and_apps/_shared/env_file.js");
const { createVeoBridgeFromEnv } = require("../../../extensions/01_bridges/veo/bridge.js");

const ROOT = path.resolve(__dirname, "..", "..");
const TECHNICAL_SYSTEM_PROMPT = "Generate exactly one living character. The attached reference images are a binding style pack: first infer their shared rendering medium, shapes, materials, contours, lighting, texture, and colour behaviour, then reproduce that exact visual language. The references exclusively determine every rendering decision; do not substitute a generic mascot, 3D render, illustration, or character-design style. Show the complete silhouette, centered, at 45–60% of frame height with safe margins. Use only a solid #00FF00 chroma background. Do not add text, letters, numbers, labels, signatures, diagrams, decorative sketches, frames, scenery, floor, wall, shadows, gradients, UI, or another creature.";

function dataUrl(file) {
  const extension = path.extname(file).toLowerCase() === ".jpg" ? "jpeg" : path.extname(file).slice(1);
  return `data:image/${extension};base64,${fs.readFileSync(file).toString("base64")}`;
}

async function main() {
  const characterDir = path.resolve(process.argv[2]);
  const visualIdentity = JSON.parse(fs.readFileSync(path.join(characterDir, "visual_identity.json"), "utf8"));
  const referenceManifest = JSON.parse(fs.readFileSync(path.join(characterDir, "reference_manifest.json"), "utf8"));
  const characterBrief = visualIdentity.character_brief;
  const masterPrompt = [
    "The attached images are the mandatory, exclusive style source: reproduce their shared visual language exactly and do not use a default style.",
    `Generate this character: ${characterBrief}`,
    "DIRECT FRONT VIEW: the character faces the viewer squarely; never use a side or three-quarter profile.",
    `Prop presentation: ${visualIdentity.anchor_prop.placement}`,
    "Hold the prop in exactly one hand or paw; no other prop may be free-standing.",
    "Use ordinary turtle anatomy: exactly four legs total, one head, one neck, one shell, and no extra limbs, flippers, tails, or duplicate body parts.",
    "Do not copy a reference subject or composition.",
  ].join(" ");
  const prompt = `${TECHNICAL_SYSTEM_PROMPT}\n\n${masterPrompt}`;
  const references = referenceManifest.references.map((reference) => path.resolve(reference.source));
  const outputDir = path.join(characterDir, "animation", "original_character_prompt_refs_direct_kling_probe");
  fs.mkdirSync(outputDir, { recursive: true });

  const env = loadEnvFileDefaults({ env: process.env, workspaceRoot: path.resolve(ROOT, "..") });
  const model = env.KLING_MODEL || "kwaivgi/kling-v3.0-pro";
  const bridge = createVeoBridgeFromEnv({ env });
  const response = await bridge.submitVideo({
    payload: {
      model,
      prompt,
      input_references: references.map((file) => ({
        type: "image_url",
        image_url: { url: dataUrl(file) },
      })),
      aspect_ratio: "1:1",
      duration: 5,
      generate_audio: false,
    },
    timeoutMs: 30000,
  });

  fs.writeFileSync(path.join(outputDir, "request.json"), `${JSON.stringify({
    references,
    technical_system_prompt: TECHNICAL_SYSTEM_PROMPT,
    master_prompt: masterPrompt,
    combined_video_prompt: prompt,
    prompt_source: "run_character_pipeline.js renderCanonicalAnchors first-anchor request",
    model,
    duration: 5,
    canonical_frames_sent: 0,
  }, null, 2)}\n`);
  fs.writeFileSync(path.join(outputDir, "veo_job.json"), `${JSON.stringify(response.data, null, 2)}\n`);
  console.log(JSON.stringify({ status: "video-submitted", dir: outputDir, job: response.data.id }, null, 2));
}

main().catch((error) => {
  console.error(error.stack || error.message);
  process.exitCode = 1;
});
