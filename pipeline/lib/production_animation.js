const crypto = require("node:crypto");
const fs = require("node:fs/promises");
const fssync = require("node:fs");
const path = require("node:path");
const { createVeoBridgeFromEnv } = require("../../extensions/01_bridges/veo/bridge.js");
const { assemble } = require("./media.js");
const { resolveVideoModel, requiresSeedanceProfile } = require("./animation.js");
const { resolveTemplate, validateCatalog, hash, PROMPT_LIMIT } = require("./prompt_catalog.js");

const json = async (file) => JSON.parse(await fs.readFile(file, "utf8"));
const write = (file, value) => fs.writeFile(file, `${JSON.stringify(value, null, 2)}\n`);
const dataUrl = (file) => `data:image/${path.extname(file).slice(1) === "png" ? "png" : "jpeg"};base64,${fssync.readFileSync(file).toString("base64")}`;

async function resolveProductionAnimation({ root, env, characterDir, animationId, options = {} }) {
  const [animations, catalog, canonical] = await Promise.all([
    json(path.join(root, "dict", "animations.json")),
    json(path.join(root, "dict", "animation_prompt_catalog.json")),
    json(path.join(characterDir, "canonical", "manifest.json")),
  ]);
  validateCatalog(catalog, animations);
  const allowPendingCanonical = options.verification && options.allowPendingCanonical
    && canonical.status === "pending_manual_review"
    && canonical.master_validation?.hard_pass;
  if (canonical.status !== "approved" && !allowPendingCanonical) throw new Error("Canonical character is not approved");
  const animation = animations.entries.find((entry) => entry.id === animationId);
  const catalogEntry = catalog.entries.find((entry) => entry.id === animationId);
  if (!animation || !catalogEntry) throw new Error(`Unknown animation: ${animationId}`);
  const allowDisabledVerification = options.verification && options.allowDisabledVerification;
  if (catalogEntry.availability === "disabled" && !allowDisabledVerification) throw new Error(`Animation '${animationId}' is temporarily disabled`);
  if (catalogEntry.verified?.verdict !== "accepted" && !options.verification && !options.dryRun && !options["assemble-only"]) throw new Error(`Animation '${animationId}' is pending verification and cannot be submitted to production`);
  const model = resolveVideoModel({ env, options });
  if (!requiresSeedanceProfile(model)) throw new Error("Production animation requires a Seedance video model");
  const prompt = `${resolveTemplate(catalog.prefix)} ${resolveTemplate(catalogEntry.template)} ${resolveTemplate(catalog.suffix)}`;
  if (prompt.length > PROMPT_LIMIT) throw new Error(`Resolved Seedance prompt exceeds ${PROMPT_LIMIT} characters: ${prompt.length}`);
  const anchor = canonical.anchors[0];
  return { animations, catalog, animation, catalogEntry, canonical, model, prompt, anchor };
}
async function runProductionAnimation({ root, env, characterDir, animationId, options = {} }) {
  const setup = await resolveProductionAnimation({ root, env, characterDir, animationId, options });
  const animationDir = path.join(characterDir, "animation", options["animation-output"] || animationId, "loop");
  const payloadSummary = {
    contract: "pet_generation_v2.bookended_animation.v1", animation_id: animationId, model: setup.model,
    duration: setup.catalogEntry.duration_seconds, resolution: options.resolution || env.PET_V2_VIDEO_RESOLUTION || "720p", template: setup.catalogEntry.template, prompt: setup.prompt,
    catalog_version: setup.catalog.version, catalog_entry_hash: hash(JSON.stringify({ prefix: setup.catalog.prefix, template: setup.catalogEntry.template, suffix: setup.catalog.suffix, duration_seconds: setup.catalogEntry.duration_seconds })),
    source_hash: setup.catalogEntry.source_hash, anchor: setup.anchor,
    frame_slots: [{ source: setup.anchor, frame_type: "first_frame" }, { source: setup.anchor, frame_type: "last_frame" }],
  };
  if (options.dryRun) return { status: "dry-run", ...payloadSummary };
  await fs.mkdir(animationDir, { recursive: true });
  if (options["assemble-only"]) {
    const videoPath = path.resolve(options["assemble-only"]);
    const qa = await assemble({
      videoPath, outputDir: animationDir, chromaKey: "#00FF00",
      keepFrames: false, createPreview: false, edgeCleanup: null,
    });
    await write(path.join(animationDir, "qa.json"), qa);
    return { status: "assembled", animationDir, ...qa };
  }
  const jobPath = path.join(animationDir, "veo_job.json");
  let job = fssync.existsSync(jobPath) ? await json(jobPath) : null;
  const veo = createVeoBridgeFromEnv({ env });
  if (!job) {
    const seed = options.seed === undefined ? parseInt(crypto.createHash("sha256").update(String(animationId)).digest("hex").slice(0, 8), 16) : Number(options.seed);
    if (!Number.isInteger(seed)) throw new Error("--seed must be an integer");
    const submit = await veo.submitVideo({ payload: { model: setup.model, prompt: setup.prompt, frame_images: payloadSummary.frame_slots.map((slot) => ({ type: "image_url", image_url: { url: dataUrl(slot.source) }, frame_type: slot.frame_type })), aspect_ratio: "1:1", duration: setup.catalogEntry.duration_seconds, resolution: payloadSummary.resolution, generate_audio: false, seed }, timeoutMs: 30000 });
    job = { ...submit.data, ...payloadSummary };
    await write(jobPath, job);
  }
  const status = await veo.getVideoJob(job.polling_url || job.id, { timeoutMs: 30000 });
  await write(path.join(animationDir, "veo_status.json"), status.data);
  if (status.data?.status !== "completed") return { status: status.data?.status || "pending", animationDir, job: job.id };
  const video = await veo.downloadVideo(status.data.id || job.id, { timeoutMs: 240000 });
  const videoPath = path.join(animationDir, `source_video${video.extension}`);
  await fs.writeFile(videoPath, video.buffer);
  const qa = await assemble({
    videoPath, outputDir: animationDir, chromaKey: "#00FF00",
    keepFrames: false, createPreview: false, edgeCleanup: null,
  });
  await write(path.join(animationDir, "qa.json"), qa);
  await write(path.join(animationDir, "result.json"), { status: "assembled", ...payloadSummary, video: path.basename(videoPath), ...qa });
  return { status: "assembled", animationDir, job: job.id, video: path.basename(videoPath), ...qa };
}
module.exports = { resolveProductionAnimation, runProductionAnimation };
