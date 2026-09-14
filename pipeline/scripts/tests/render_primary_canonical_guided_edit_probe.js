const fs = require("node:fs/promises");
const path = require("node:path");
const { loadEnvFileDefaults } = require("../../../extensions/02_tools_and_apps/_shared/env_file.js");
const { createNanobananaBridgeFromEnv } = require("../../../extensions/01_bridges/nanobanana/bridge.js");

const ROOT = path.resolve(__dirname, "..", "..");
const PROMPT = [
  "The first supplied image is the PRIMARY EDIT IMAGE.",
  "Edit only that first image. The later side, back, and top images are identity and anatomy checks only; do not blend their viewpoints, poses, or visible details into the result.",
  "Make exactly one local pose change:",
  "Move the existing head and neck slightly backward toward the shell while keeping a direct front view. Turn both pupils calmly toward image-right and make the closed mouth firm and neutral.",
  "This reads as deliberate refusal, not anger, fear, sadness, sleepiness, or playfulness.",
  "Do not move, redraw, resize, duplicate, or otherwise change the shell, four legs, connected three-panel card, its drawings, body position, framing, lighting, style, or solid #00FF00 background.",
  "Return one full character. Add nothing.",
].join("\n");

async function dataUrl(file) {
  const extension = path.extname(file).toLowerCase() === ".jpg" ? "jpeg" : "png";
  return `data:image/${extension};base64,${(await fs.readFile(file)).toString("base64")}`;
}

function imageFromResponse(body) {
  const image = body?.choices?.[0]?.message?.images?.[0];
  const url = image?.image_url?.url || image?.url;
  const match = /^data:image\/([^;]+);base64,(.+)$/s.exec(url || "");
  if (!match) throw new Error("Nano Banana did not return an image data URL.");
  return {
    extension: match[1].includes("jpeg") ? ".jpg" : ".png",
    buffer: Buffer.from(match[2], "base64"),
  };
}

async function main() {
  const characterDir = path.resolve(process.argv[2]);
  const manifest = JSON.parse(await fs.readFile(path.join(characterDir, "canonical", "manifest.json"), "utf8"));
  const canonicals = manifest.anchors.map((file) => path.resolve(file));
  const labels = [
    "PRIMARY EDIT IMAGE — front canonical:",
    "IDENTITY CHECK ONLY — side canonical:",
    "IDENTITY CHECK ONLY — back canonical:",
    "IDENTITY CHECK ONLY — top canonical:",
  ];
  const content = [{ type: "text", text: PROMPT }];
  for (const [index, canonical] of canonicals.entries()) {
    content.push({ type: "text", text: labels[index] });
    content.push({ type: "image_url", image_url: { url: await dataUrl(canonical) } });
  }

  const env = loadEnvFileDefaults({ env: process.env, workspaceRoot: path.resolve(ROOT, "..") });
  const bridge = createNanobananaBridgeFromEnv({ env });
  const model = env.IMAGE_RENDER_MODEL || "google/gemini-3.1-flash-image-preview";
  const response = await bridge.renderRaw({
    payload: {
      model,
      messages: [{ role: "user", content }],
      modalities: ["image", "text"],
      image_config: { aspect_ratio: "1:1" },
      stream: false,
    },
    timeoutMs: 300000,
  });

  const result = imageFromResponse(response.data);
  const outputDir = path.join(characterDir, "animation", "_assets", "canonical_guided_refusal_step1");
  await fs.mkdir(outputDir, { recursive: true });
  const output = path.join(outputDir, `anchor_canonical_guided_refusal_step1${result.extension}`);
  await fs.writeFile(output, result.buffer);
  await fs.writeFile(path.join(outputDir, "request.json"), `${JSON.stringify({
    canonicals,
    prompt: PROMPT,
    model,
  }, null, 2)}\n`);
  console.log(output);
}

main().catch((error) => {
  console.error(error.stack || error.message);
  process.exitCode = 1;
});
