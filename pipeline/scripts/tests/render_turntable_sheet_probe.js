const fs = require("node:fs/promises");
const path = require("node:path");
const { loadEnvFileDefaults } = require("../../../extensions/02_tools_and_apps/_shared/env_file.js");
const { createNanobananaBridgeFromEnv } = require("../../../extensions/01_bridges/nanobanana/bridge.js");
const { TURNTABLE_SYSTEM_PROMPT, buildTurntablePrompt } = require("../../lib/canonical.js");
const { splitTurntableSheet } = require("../../lib/framing.js");

const ROOT = path.resolve(__dirname, "..", "..");
const dataUrl = async (file) => `data:image/${path.extname(file).toLowerCase() === ".jpg" ? "jpeg" : "png"};base64,${(await fs.readFile(file)).toString("base64")}`;

async function main() {
  const characterDir = path.resolve(process.argv[2] || "");
  if (!characterDir) throw new Error("Usage: node render_turntable_sheet_probe.js <character-dir>");
  const identity = JSON.parse(await fs.readFile(path.join(characterDir, "visual_identity.json"), "utf8"));
  const refsDir = path.join(characterDir, "refs_used");
  const refs = (await fs.readdir(refsDir)).filter((file) => /\.(png|jpe?g|webp)$/i.test(file)).sort();
  if (!refs.length) throw new Error(`No references found: ${refsDir}`);
  const env = loadEnvFileDefaults({ env: process.env, workspaceRoot: path.resolve(ROOT, "..") });
  const bridge = createNanobananaBridgeFromEnv({ env });
  const model = env.IMAGE_RENDER_MODEL || "google/gemini-3.1-flash-image-preview";
  const content = [{ type: "text", text: buildTurntablePrompt({ renderIdentity: identity.render_identity, anchorProp: identity.anchor_prop }) }];
  for (const ref of refs) content.push({ type: "image_url", image_url: { url: await dataUrl(path.join(refsDir, ref)) } });
  const response = await bridge.renderRaw({ payload: { model, messages: [{ role: "system", content: TURNTABLE_SYSTEM_PROMPT }, { role: "user", content }], modalities: ["image", "text"], image_config: { aspect_ratio: "1:1" }, stream: false }, timeoutMs: 300000 });
  const url = response.data?.choices?.[0]?.message?.images?.[0]?.image_url?.url || response.data?.choices?.[0]?.message?.images?.[0]?.url;
  const match = /^data:image\/([^;]+);base64,(.+)$/s.exec(url || "");
  if (!match) throw new Error("Nano Banana did not return an image data URL");
  const outputDir = path.join(characterDir, "canonical", "turntable_sheet_probe", new Date().toISOString().replace(/[:.]/g, "-"));
  await fs.mkdir(outputDir, { recursive: true });
  const sheet = path.join(outputDir, `sheet.${match[1].includes("jpeg") ? "jpg" : "png"}`);
  await fs.writeFile(sheet, Buffer.from(match[2], "base64"));
  const qa = splitTurntableSheet(sheet, outputDir);
  await fs.writeFile(path.join(outputDir, "result.json"), `${JSON.stringify({ contract: "pet_generation_v2.turntable_sheet_probe.v1", prompt: content[0].text, model, refs, qa }, null, 2)}\n`);
  console.log(`TURNTABLE_SHEET_READY ${outputDir} QA_${qa.pass ? "PASS" : "FAIL"}`);
}

main().catch((error) => { console.error(error.stack || error.message); process.exitCode = 1; });
