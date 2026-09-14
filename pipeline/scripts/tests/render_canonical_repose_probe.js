const fs = require("node:fs/promises");
const path = require("node:path");
const { loadEnvFileDefaults } = require("../../../extensions/02_tools_and_apps/_shared/env_file.js");
const { createNanobananaBridgeFromEnv } = require("../../../extensions/01_bridges/nanobanana/bridge.js");

const ROOT = path.resolve(__dirname, "..", "..");
const PROMPT = [
  "All four supplied images show the same existing character from front, side, back, and top.",
  "Use them only as a binding identity model sheet. Generate exactly one new image containing exactly one instance of that same character in a new pose.",
  "Target pose: a calm, deliberate refusal. Direct front view. The character draws her head and neck slightly back toward the shell and looks firmly to the side.",
  "Relocate the same connected three-panel picture card upward to the lower chest at the same physical size as in the front canonical. Both existing front feet hold its two outer edges.",
  "Keep exactly three connected panels with the same panel proportions, fold geometry, texture, and the same three drawings in the same order.",
  "Show exactly four legs total: two front feet holding the card and two rear legs clearly visible below the shell.",
  "Preserve the exact head, face, wide domed shell, scute pattern, body proportions, spotted skin, palette, texture, and illustration style from the canonicals.",
  "The expression is firm but not angry, frightened, sad, sleepy, or playful.",
  "Same centered widget framing and solid #00FF00 background. No floor, shadow, text, UI, glass, scenery, effects, new objects, or additional cards.",
];

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
  const content = [{ type: "text", text: PROMPT.join("\n") }];
  for (const canonical of canonicals) {
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
  const outputDir = path.join(characterDir, "animation", "_assets", "canonical_repose_refusal");
  await fs.mkdir(outputDir, { recursive: true });
  const output = path.join(outputDir, `anchor_canonical_repose_refusal${result.extension}`);
  await fs.writeFile(output, result.buffer);
  await fs.writeFile(path.join(outputDir, "request.json"), `${JSON.stringify({
    canonicals,
    prompt: PROMPT.join("\n"),
    model,
  }, null, 2)}\n`);
  console.log(output);
}

main().catch((error) => {
  console.error(error.stack || error.message);
  process.exitCode = 1;
});
