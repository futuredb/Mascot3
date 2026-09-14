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
  const state = process.argv.find((argument) => argument.startsWith("--state="))?.slice("--state=".length);
  const minimal = process.argv.includes("--minimal");
  const workV2 = process.argv.includes("--work-v2");
  const workV3 = process.argv.includes("--work-v3");
  const atGlassV2 = process.argv.includes("--at-glass-v2");
  const refusalV2 = process.argv.includes("--refusal-v2");
  const refusalV3 = process.argv.includes("--refusal-v3");
  if (!["sleep", "work", "at_glass", "refusal"].includes(state)) throw new Error("Use --state=sleep, --state=work, --state=at_glass, or --state=refusal");

  const lore = JSON.parse(await fs.readFile(path.join(characterDir, "lore.json"), "utf8"));
  const visualIdentity = JSON.parse(await fs.readFile(path.join(characterDir, "visual_identity.json"), "utf8"));
  const canonicalManifest = JSON.parse(await fs.readFile(path.join(characterDir, "canonical", "manifest.json"), "utf8"));
  const canonicals = minimal
    ? [path.resolve(canonicalManifest.anchors[0])]
    : canonicalManifest.anchors.map((file) => path.resolve(file));
  const stateInstruction = state === "sleep"
    ? "Show how this specific character naturally sleeps. Infer a biologically plausible and character-specific sleeping state from the subject, anatomy, personality, habits, props, and lore. This is the stable sleeping state, not the transition into sleep or waking up."
    : "Show this specific character actively occupied with her main work described in the lore. Depict one clear continuing work moment, not preparation, success, failure, celebration, or completion.";
  const minimalPrompts = {
    sleep: [
      "Edit the supplied image. Keep everything unchanged except:",
      "1. Fold the three picture cards and place them beside the shell.",
      "2. Retract the head, neck, and all four legs fully inside the shell.",
      "Show only the same closed shell and the folded cards.",
      "Same scale, position, style, lighting, and green background. Add nothing.",
    ].join("\n"),
    work: [
      "Edit the supplied image. Keep everything unchanged except:",
      "1. Place the same connected three-panel picture card between both front feet.",
      "2. One front foot holds its left edge while the other front foot folds the right hinge inward.",
      "3. Turn the eyes precisely toward the hinge being folded.",
      "Keep exactly three connected panels in the same order with unchanged drawings.",
      "Same body, pose, scale, style, lighting, and green background. Add nothing.",
    ].join("\n"),
    at_glass: [
      "Edit the supplied image into one clear interaction-with-glass state.",
      "Replace the current free front foot on the image-right with that exact same limb moved forward and pressed against invisible glass.",
      "The old image-right front-foot position must become empty; do not preserve or duplicate that limb.",
      "Show exactly four legs total: one card-holding front foot, one pressed front foot, and two rear legs.",
      "The pressed sole has ordinary spotted turtle skin matching the other feet; it has no shell scutes or shell pattern.",
      "Move the face slightly closer behind that foot and turn both eyes toward the viewer.",
      "Keep the other front foot holding exactly the same connected three-panel picture card with unchanged drawings.",
      "Preserve the same shell, body, face, scale, style, lighting, and solid green background.",
      "Do not draw the glass, reflections, handprints, text, scenery, floor, shadow, or any new object.",
    ].join("\n"),
    refusal: [
      "Edit the supplied image into one clear voluntary refusal state.",
      "Translate the one existing connected three-panel picture card straight upward by exactly one card height in the same flat image plane.",
      "The card's old lower position must be completely empty.",
      "This is the same single card object moved upward, not a copy.",
      "Preserve its exact bounding-box width and height, panel proportions, fold angles, perspective, outlines, texture, and the same three drawings in the same order.",
      "Do not enlarge, shrink, redraw, rotate, foreshorten, or move the card closer to the camera.",
      "Move the two existing front feet to hold the left and right outer edges of the raised card; do not preserve their old positions and do not add limbs.",
      "Draw the head slightly back, lift the chin a little, and turn both eyes calmly to the side.",
      "The expression is firm and deliberate, not angry, frightened, sad, sleepy, or playful.",
      "Show exactly four legs total and preserve the same shell, body, face, scale, style, lighting, and solid green background.",
      "Add no external requester, text, UI, scenery, floor, shadow, or new object.",
    ].join("\n"),
  };
  const workV3Prompt = [
    "Edit the supplied image into one clear work-in-progress moment.",
    "Keep the existing connected three-panel picture card completely unchanged as the finished reference sequence.",
    "Add exactly one new blank square card immediately beside it.",
    "The free front foot draws one simple curved route symbol on the blank card with a thin hard-light line extending from the tip of that foot.",
    "Turn both eyes precisely toward the line being drawn.",
    "Preserve the exact body, four legs, shell, face, scale, style, lighting, and solid green background.",
    "Add no pen, tools, extra cards, text, scenery, floor, or shadow.",
  ].join("\n");
  const lorePrompt = [
    "Use all supplied canonical views as binding identity references for one existing character.",
    stateInstruction,
    "Choose a direct front or mild three-quarter view that makes the state immediately readable.",
    "Preserve the exact character identity, body construction, shell, proportions, face, palette, texture, rendering style, and every persistent prop.",
    "Keep prop count, design, and drawings consistent; change placement only when physically required by the depicted state.",
    "Show exactly one full character at the same widget scale on a solid #00FF00 background.",
    "Do not add text, UI, scenery, floor, shadow, another creature, or unrelated objects.",
    `VISUAL_IDENTITY:\n${JSON.stringify(visualIdentity)}`,
    `LORE:\n${JSON.stringify(lore)}`,
  ].join("\n\n");
  const prompt = workV3 ? workV3Prompt : minimal ? minimalPrompts[state] : lorePrompt;

  const content = [{ type: "text", text: prompt }];
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
  const variantSuffix = workV3 || refusalV3 ? "_v3" : workV2 || atGlassV2 || refusalV2 ? "_v2" : "";
  const outputDir = path.join(characterDir, "animation", "_assets", `${minimal ? "minimal" : "lore"}_${state}${variantSuffix}`);
  await fs.mkdir(outputDir, { recursive: true });
  const output = path.join(outputDir, `anchor_${minimal ? "minimal" : "lore"}_${state}${variantSuffix}${result.extension}`);
  await fs.writeFile(output, result.buffer);
  await fs.writeFile(path.join(outputDir, "request.json"), `${JSON.stringify({
    state,
    minimal,
    canonicals,
    prompt,
    model,
  }, null, 2)}\n`);
  console.log(output);
}

main().catch((error) => {
  console.error(error.stack || error.message);
  process.exitCode = 1;
});
