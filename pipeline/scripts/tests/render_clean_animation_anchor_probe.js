const fs = require("node:fs/promises");
const path = require("node:path");
const { loadEnvFileDefaults } = require("../../../extensions/02_tools_and_apps/_shared/env_file.js");
const { createNanobananaBridgeFromEnv } = require("../../../extensions/01_bridges/nanobanana/bridge.js");

const ROOT = path.resolve(__dirname, "..", "..");

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
  const sleepState = process.argv.includes("--sleep-state");
  const view = process.argv.find((argument) => argument.startsWith("--view="))?.slice("--view=".length) || "front";
  const viewIndex = { front: 0, side: 1 }[view];
  if (viewIndex === undefined) throw new Error(`Unsupported view: ${view}`);
  const source = sleepState
    ? path.join(characterDir, "animation", "_assets", "clean_front", "anchor_clean.jpg")
    : path.join(characterDir, "canonical", `anchor_${String(viewIndex).padStart(2, "0")}.jpg`);
  const outputDir = path.join(characterDir, "animation", "_assets", sleepState ? "sleep_front" : `clean_${view}`);
  const prompt = sleepState ? [
    "Edit the supplied clean canonical turtle into her sleeping state.",
    "Keep the domed shell completely unchanged in shape, pattern, size, position, palette, texture, lighting, and front-facing orientation.",
    "Retract the head, neck, and all four legs fully and naturally inside the shell so no face, neck, feet, or limbs remain visible.",
    "Show only the same intact shell with dark natural openings beneath its rim.",
    "Preserve the exact framing and solid #00FF00 background.",
    "Do not add props, floor, shadow, text, scenery, glow, symbols, or another object.",
  ].join(" ") : [
    "Edit the supplied canonical character image rather than redesigning it.",
    "Remove the three folding picture cards completely and reconstruct the occluded front foot naturally.",
    `The result must contain exactly the same single turtle, ${view} view, pose, expression, anatomy, proportions, shell pattern, palette, texture, lighting, scale, framing, and solid #00FF00 background.`,
    "Keep exactly one head, one continuous neck, one rigid shell, and exactly four naturally attached visible legs.",
    "Do not change the face, shell, silhouette, limb placement, image composition, or rendering style.",
    "Do not add any prop, badge, floor, shadow, text, scenery, particles, or new object.",
    "This is a clean animation-safe canonical plate, not a new illustration.",
  ].join(" ");

  const env = loadEnvFileDefaults({ env: process.env, workspaceRoot: path.resolve(ROOT, "..") });
  const bridge = createNanobananaBridgeFromEnv({ env });
  const response = await bridge.renderRaw({
    payload: {
      model: env.IMAGE_RENDER_MODEL || "google/gemini-3.1-flash-image-preview",
      messages: [{
        role: "user",
        content: [
          { type: "text", text: prompt },
          { type: "image_url", image_url: { url: await dataUrl(source) } },
        ],
      }],
      modalities: ["image", "text"],
      image_config: { aspect_ratio: "1:1" },
      stream: false,
    },
    timeoutMs: 300000,
  });
  const result = imageFromResponse(response.data);
  await fs.mkdir(outputDir, { recursive: true });
  const output = path.join(outputDir, `${sleepState ? "anchor_sleep" : "anchor_clean"}${result.extension}`);
  await fs.writeFile(output, result.buffer);
  await fs.writeFile(path.join(outputDir, "request.json"), `${JSON.stringify({ source, prompt }, null, 2)}\n`);
  console.log(output);
}

main().catch((error) => {
  console.error(error.stack || error.message);
  process.exitCode = 1;
});
