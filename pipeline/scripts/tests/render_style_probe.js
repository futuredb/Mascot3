const fs = require("node:fs/promises");
const path = require("node:path");
const { loadEnvFileDefaults } = require("../../../extensions/02_tools_and_apps/_shared/env_file.js");
const { createNanobananaBridgeFromEnv } = require("../../../extensions/01_bridges/nanobanana/bridge.js");

const ROOT = path.resolve(__dirname, "..", "..");
const dataUrl = async (file) => `data:image/png;base64,${(await fs.readFile(file)).toString("base64")}`;
const TECHNICAL_FRAME_SYSTEM_PROMPT = "Generate exactly one character as a complete, centered full figure with clear margins. Use only a solid #00FF00 chroma background. Do not add text, letters, numbers, labels, signatures, symbols, diagrams, decorative sketches, frames, props, scenery, floor, wall, shadows, gradients, UI, or another creature. These are technical output constraints only; obtain all visual style exclusively from the attached reference images.";

async function main() {
  const env = loadEnvFileDefaults({ env: process.env, workspaceRoot: path.resolve(ROOT, "..") });
  const pack = process.argv[2] || "5";
  const refDir = path.resolve(ROOT, "refs", pack);
  const arguments_ = process.argv.slice(3);
  const timAnchorContext = arguments_.includes("--tim-anchor-context");
  const timPlainBrief = arguments_.includes("--tim-plain-brief");
  const subjectPrompt = arguments_.filter((argument) => !["--tim-anchor-context", "--tim-plain-brief"].includes(argument)).join(" ")
    || (timPlainBrief
      ? "Generate Tim, a sixteen-year-old male domestic cat with a focused, quietly cheerful expression, a long trailing tail, and a small soft charm at his chest. Show his whole figure in a neutral floating pose."
      : "Generate a cat wearing a hoodie.");
  const refs = (await fs.readdir(refDir)).filter((file) => /\.png$/i.test(file)).sort();
  const context = timAnchorContext
    ? ` Character context: Tim is a sixteen-year-old male domestic cat with quick, precise forepaw movement and a focused but quietly cheerful expression. He has a long trailing tail and a small soft charm fixed at his chest. Show one whole character in a neutral floating pose, front view, centered with safe margins on a solid green chroma background; no crop, floor, shadow, text, UI, or extra creature.`
    : "";
  const anatomy = timPlainBrief ? " Use ordinary domestic-cat anatomy: exactly four legs and one tail; no extra limbs or duplicate body parts." : "";
  const content = [{ type: "text", text: `${subjectPrompt}${context}${anatomy} Use the attached images strictly as the visual-style reference: match their rendering medium, material response, lighting, colour behaviour, and texture. Do not copy their subject or composition.` }];
  for (const file of refs) content.push({ type: "image_url", image_url: { url: await dataUrl(path.join(refDir, file)) } });
  const bridge = createNanobananaBridgeFromEnv({ env });
  const response = await bridge.renderRaw({ payload: { model: "google/gemini-3.1-flash-image-preview", messages: [{ role: "system", content: TECHNICAL_FRAME_SYSTEM_PROMPT }, { role: "user", content }], modalities: ["image", "text"], image_config: { aspect_ratio: "1:1" }, stream: false }, timeoutMs: 300000 });
  const url = response.data?.choices?.[0]?.message?.images?.[0]?.image_url?.url || response.data?.choices?.[0]?.message?.images?.[0]?.url;
  const match = /^data:image\/([^;]+);base64,(.+)$/s.exec(url || "");
  if (!match) throw new Error("Nano Banana did not return an image data URL");
  const outputDir = path.join(ROOT, "experiments"); await fs.mkdir(outputDir, { recursive: true });
  const packSlug = path.basename(refDir).replace(/[^a-z0-9]+/gi, "_");
  const subjectSlug = `${subjectPrompt}${timAnchorContext ? "_tim_anchor_context" : ""}${timPlainBrief ? "_tim_plain_brief" : ""}`.toLowerCase().replace(/[^a-z0-9]+/g, "_").replace(/^_|_$/g, "").slice(0, 48) || "probe";
  const output = path.join(outputDir, `${subjectSlug}_refs_${packSlug}.${match[1].includes("jpeg") ? "jpg" : "png"}`);
  await fs.writeFile(output, Buffer.from(match[2], "base64"));
  console.log(output);
}

main().catch((error) => { console.error(error.stack || error.message); process.exitCode = 1; });
