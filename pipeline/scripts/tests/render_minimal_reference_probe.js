const fs = require("node:fs/promises");
const path = require("node:path");
const { loadEnvFileDefaults } = require("../../../extensions/02_tools_and_apps/_shared/env_file.js");
const { createNanobananaBridgeFromEnv } = require("../../../extensions/01_bridges/nanobanana/bridge.js");
const { addSafeChromaFrame } = require("../../lib/framing.js");
const { STYLE_TRANSFER_CONTRACT, TECHNICAL_FRAME_SYSTEM_PROMPT } = require("../../lib/canonical.js");

const ROOT = path.resolve(__dirname, "..", "..");
const option = (name) => process.argv.find((argument) => argument.startsWith(`--${name}=`))?.slice(name.length + 3);
const dataUrl = async (file) => `data:image/${path.extname(file).toLowerCase() === ".jpg" ? "jpeg" : "png"};base64,${(await fs.readFile(file)).toString("base64")}`;

async function main() {
  const characterDir = path.resolve(process.argv[2] || "");
  const identity = option("identity");
  const identitySheet = option("identity-sheet");
  const view = option("view") || "front";
  const extra = option("extra");
  if (!characterDir || !identity) throw new Error("Usage: node render_minimal_reference_probe.js <character-dir> --identity=\"...\"");
  const refsDir = path.join(characterDir, "refs_used");
  const refs = (await fs.readdir(refsDir)).filter((file) => /\.(png|jpe?g|webp)$/i.test(file)).sort();
  if (!refs.length) throw new Error(`No references found: ${refsDir}`);
  const prompt = [
    `Generate exactly one ${identity}.`,
    `True ${view} view in a neutral upright hovering pose.`,
    extra || "",
    STYLE_TRANSFER_CONTRACT,
  ].join(" ");
  const env = loadEnvFileDefaults({ env: process.env, workspaceRoot: path.resolve(ROOT, "..") });
  const bridge = createNanobananaBridgeFromEnv({ env });
  const model = env.IMAGE_RENDER_MODEL || "google/gemini-3.1-flash-image-preview";
  const content = [{ type: "text", text: prompt }];
  for (const ref of refs) content.push({ type: "image_url", image_url: { url: await dataUrl(path.join(refsDir, ref)) } });
  if (identitySheet) {
    content.push({ type: "text", text: "The following image is an identity reference only. Preserve the same character; render only the requested view." });
    content.push({ type: "image_url", image_url: { url: await dataUrl(path.resolve(identitySheet)) } });
  }
  const response = await bridge.renderRaw({
    payload: { model, messages: [{ role: "system", content: TECHNICAL_FRAME_SYSTEM_PROMPT }, { role: "user", content }], modalities: ["image", "text"], image_config: { aspect_ratio: "1:1" }, stream: false },
    timeoutMs: 300000,
  });
  const url = response.data?.choices?.[0]?.message?.images?.[0]?.image_url?.url || response.data?.choices?.[0]?.message?.images?.[0]?.url;
  const match = /^data:image\/([^;]+);base64,(.+)$/s.exec(url || "");
  if (!match) throw new Error("Nano Banana did not return an image data URL");
  const outputDir = path.join(characterDir, "canonical", "explicit_style_reference_probe");
  await fs.mkdir(outputDir, { recursive: true });
  const raw = path.join(outputDir, `raw.${match[1].includes("jpeg") ? "jpg" : "png"}`);
  const output = path.join(outputDir, "anchor_00.png");
  await fs.writeFile(raw, Buffer.from(match[2], "base64"));
  const safeFrame = addSafeChromaFrame(raw, output);
  await fs.writeFile(path.join(outputDir, "request.json"), `${JSON.stringify({ contract: "pet_generation_v2.minimal_reference_probe.v1", prompt, identity, model, refs, safe_frame: safeFrame }, null, 2)}\n`);
  console.log(output);
}

main().catch((error) => { console.error(error.stack || error.message); process.exitCode = 1; });
