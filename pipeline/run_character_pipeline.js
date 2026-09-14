const crypto = require("node:crypto");
const fs = require("node:fs/promises");
const fssync = require("node:fs");
const path = require("node:path");
const { loadEnvFileDefaults } = require("../extensions/02_tools_and_apps/_shared/env_file.js");
const {
  createOpenRouterDirectBridgeFromEnv,
  createOpenRouterProxyBridgeFromEnv,
} = require("../extensions/01_bridges/openrouter/bridge.js");
const { createNanobananaBridgeFromEnv } = require("../extensions/01_bridges/nanobanana/bridge.js");
const { loadDictionaries, buildPrimaryData, buildPrimaryDataWithBags, choose, namePool, rankNames } = require("./lib/deal.js");
const { corpusHash, claimName, drawBag } = require("./lib/registry.js");
const { composeLore, resolveGpt56, extractJson, extractText } = require("./lib/composer.js");
const { buildReferenceManifest, copySelectedReferences } = require("./lib/refs.js");
const { addSafeChromaFrame } = require("./lib/framing.js");
const { TECHNICAL_FRAME_SYSTEM_PROMPT, validateRenderIdentity, buildCanonicalRenderPrompt } = require("./lib/canonical.js");

const { runProductionAnimation } = require("./lib/production_animation.js");

const ROOT = __dirname;
const DATA_ROOT = process.env.PET_V2_DATA_DIR ? path.resolve(process.env.PET_V2_DATA_DIR) : ROOT;
const THROUGH = ["deal", "lore", "refs", "character_design", "canonical"];
const technicalSystemPrompt = TECHNICAL_FRAME_SYSTEM_PROMPT;

function createTextBridge({ env, directOpenRouter = false }) {
  if (!directOpenRouter) return createOpenRouterProxyBridgeFromEnv({ env, allowDirectFallback: false });
  if (!String(env.OPENROUTER_API_KEY || "").trim()) throw new Error("--direct-openrouter=yes requires OPENROUTER_API_KEY");
  return createOpenRouterDirectBridgeFromEnv({ env });
}

function args(argv) {
  const out = {};
  for (const arg of argv) {
    if (arg === "--dry-run") out.dryRun = true;
    else if (arg === "--resume") out.resume = true;
    else if (arg === "--series") out.series = true;
    else if (arg === "--approve-canonical") out.approveCanonical = true;
    else if (arg === "--rerun-canonical") out.rerunCanonical = true;
    else if (arg === "--rerun-refs") out.rerunRefs = true;
    else if (arg === "--rerun-character-design") out.rerunCharacterDesign = true;
    else if (arg === "--rerun-lore") out.rerunLore = true;
    else if (arg === "--extend-canonical") out.extendCanonical = true;
    else if (arg === "--style-only-canonical") out.styleOnlyCanonical = true;
    else if (arg === "--structural-lock-canonical") out.structuralLockCanonical = true;
    else if (arg === "--strip-rendering-text") out.stripRenderingText = true;
    else if (arg === "--reference-master-canonical") out.referenceMasterCanonical = true;
    else if (arg === "--skip-canonical-ai-review") out.skipCanonicalAiReview = true;
    else if (arg === "--ensemble-scene") out.ensembleScene = true;
    else if (arg.startsWith("--rerun-stage=")) out.rerunStage = arg.slice(14);
    else {
      const m = arg.match(/^--([a-z-]+)=(.*)$/);
      if (m) out[m[1]] = m[2];
    }
  }
  return out;
}
const json = async (file) => JSON.parse(await fs.readFile(file, "utf8"));
const write = (file, data) => fs.writeFile(file, `${JSON.stringify(data, null, 2)}\n`);
const stageIndex = (name) => {
  const n = THROUGH.indexOf(name);
  if (n < 0) throw new Error(`Unknown stage: ${name}`);
  return n;
};
const slug = (value) => String(value).toLowerCase().replace(/[^a-zа-яё0-9]+/gi, "_").replace(/^_|_$/g, "") || "character";
const dataUrl = (file) => `data:image/${path.extname(file).slice(1) === "jpg" ? "jpeg" : path.extname(file).slice(1)};base64,${fssync.readFileSync(file).toString("base64")}`;
function identityContract(visualIdentity) {
  return { character_brief: visualIdentity.character_brief, anchor_prop: visualIdentity.anchor_prop, view_mode: visualIdentity.view_mode, chroma_key: visualIdentity.chroma_key };
}
function bodyContract(visualIdentity) {
  return { character_brief: visualIdentity.character_brief };
}
function anatomyRule(lore) {
  const parts = lore.body_profile?.visible_parts || [];
  const contacts = lore.body_profile?.movement_contact || [];
  return `Use exactly the body topology established by the character: ${parts.join(", ")}. Movement-capable parts are only: ${contacts.join(", ")}. Do not add, duplicate, remove, or repurpose body parts.`;
}
function viewRule(view) {
  const rules = {
    front: "DIRECT FRONT VIEW: the character faces the viewer squarely; never use a side or three-quarter profile.",
    side: "TRUE 90-DEGREE SIDE VIEW: show one side only; never use a front, rear, or three-quarter view.",
    back: "DIRECT REAR VIEW: show the back of the body; the face and front-side features must not be visible.",
    top: "TRUE TOP-DOWN VIEW: camera looks vertically down on the character; never use eye-level, front, side, or rear framing.",
  };
  return rules[view] || "";
}
function propModeRule(mode) {
  const rules = {
    held: "Hold the prop in exactly one hand or paw; no other prop may be free-standing.",
    hovering_nearby: "Keep the prop visibly separated from every body part; it must not be held, worn, attached, or touch the body.",
    worn_or_attached: "Fix the prop at one explicit body attachment point; it must not be held or float.",
    orbiting: "Keep the prop on a clearly separated orbit path around the body; it must not touch, be held, or be attached.",
    resting_against_body: "The prop may touch the body at the stated resting point, but must not be held.",
  };
  if (!rules[mode]) throw new Error(`unknown anchor prop mode: ${mode}`);
  return rules[mode];
}
function propViewRule(anchorProp, view) {
  const modeRule = propModeRule(anchorProp.mode);
  if (view === "front") return `Show it ${anchorProp.placement}. ${modeRule}`;
  if (view === "side") return `Preserve its physical attachment or position from the front in true profile: ${anchorProp.placement}. It may be partly occluded, but do not relocate it. ${modeRule}`;
  return `Preserve its physical attachment or position from the front. It may be naturally hidden behind the body from this ${view} view; do not move it to another body part or force it into view. ${modeRule}`;
}
function anatomyViewRule(visualIdentity, view) {
  if (view !== "back") return "";
  const subject = String(visualIdentity.render_identity?.subject || "").toLowerCase();
  if (!subject.includes("unicorn")) return "Rear anatomy must preserve one complete compact torso and naturally occluded limbs; do not add markings.";
  return "REAR ANATOMY BLUEPRINT: preserve one compact rounded equine torso and four short legs, which may be partly occluded. The mane begins behind the ears and must not replace or conceal the torso. The horn and chest prop are naturally hidden from the rear; do not move them into view. Do not add markings, symbols, or decorations to the tail.";
}
function anchorPropMode(primary) {
  const living = ["held", "hovering_nearby", "worn_or_attached", "orbiting", "resting_against_body"];
  return choose(living, primary.seed, "canonical.anchor_prop_mode");
}
function propIds(primary) {
  return [
    ...(primary.selected.external_modifiers?.prop || []).map((prop) => prop.id),
    ...(primary.selected.requested_object ? [primary.selected.requested_object.id] : []),
  ];
}
function hasRequestedObject(lore, requestedObject) {
  const requested = String(requestedObject.label).trim().toLocaleLowerCase();
  return (lore.passport?.props || []).some((prop) => String(prop).trim().toLocaleLowerCase() === requested);
}
async function claimGeneratedName(primary, dictionaries) {
  if (primary.input.name_source !== "generated") return;
  const pool = rankNames(namePool(dictionaries, primary.selected.gender.id), primary.seed);
  const registryPath = path.join(DATA_ROOT, "_state", "name_registry.json");
  for (const name of [primary.input.name, ...pool.filter((candidate) => candidate !== primary.input.name)]) {
    if (await claimName(registryPath, name, primary.fingerprint)) {
      primary.input.name = name;
      return;
    }
  }
  throw new Error(`No available names in ${primary.selected.gender.id} pool`);
}
async function recentSubjects(limit = 10) {
  const runsDir = path.join(DATA_ROOT, "runs");
  await fs.mkdir(runsDir, { recursive: true });
  const entries = await fs.readdir(runsDir, { withFileTypes: true });
  const subjects = [];
  for (const entry of entries.filter((item) => item.isDirectory()).sort((a, b) => b.name.localeCompare(a.name))) {
    try {
      const lore = await json(path.join(runsDir, entry.name, "lore", "raw_response.md"));
      const subject = String(lore.identity?.subject || "").trim();
      if (subject && !subjects.includes(subject)) subjects.push(subject);
      if (subjects.length >= limit) break;
    } catch (error) {
      if (error.code !== "ENOENT") throw error;
    }
  }
  return subjects;
}
function imageFromResponse(body) {
  const images = body?.choices?.[0]?.message?.images || [];
  const url = images[0]?.image_url?.url || images[0]?.url;
  if (!url?.startsWith("data:image/")) throw new Error("Nano Banana did not return an image data URL");
  const m = /^data:image\/([^;]+);base64,(.+)$/s.exec(url);
  return { extension: m[1].includes("jpeg") ? ".jpg" : ".png", buffer: Buffer.from(m[2], "base64") };
}
async function imageModel(env) {
  const bridge = createNanobananaBridgeFromEnv({ env });
  const models = (await bridge.listModels({ timeoutMs: 30000 })).data?.data || [];
  const model = models.find((item) => item.id === (env.IMAGE_RENDER_MODEL || "google/gemini-3.1-flash-image-preview"));
  if (!model) throw new Error("Nano Banana image model is unavailable");
  return { bridge, model };
}
async function characterDesign({ env, primary, lore, characterDir, requested, requestedView, directOpenRouter }) {
  const bridge = createTextBridge({ env, directOpenRouter });
  const model = await resolveGpt56(bridge, requested);
  const [prompt, world] = await Promise.all([
    fs.readFile(path.join(ROOT, "prompts", "character_designer.md"), "utf8"),
    fs.readFile(path.join(ROOT, "world.md"), "utf8"),
  ]);
  const pilotView = requestedView ? `\n\nPILOT REQUIREMENT: use view_mode "${requestedView}" if it is physically compatible with the body.` : "";
  const presentation = anchorPropMode(primary);
  const contents = [{ type: "text", text: `${prompt}\n\nФИЗИЧЕСКИЙ КОНТРАКТ МЕЖСЛОЯ:\n${world}\n\nANCHOR_PROP_MODE: ${presentation}\nAVAILABLE_PROP_IDS: ${JSON.stringify(propIds(primary))}\n\nPRIMARY:\n${JSON.stringify(primary)}\n\nLORE:\n${JSON.stringify(lore)}${pilotView}` }];
  const result = await bridge.chatCompletions({ payload: { model: model.id, temperature: 0.8, max_tokens: 3500, messages: [{ role: "user", content: contents }] }, timeoutMs: 240000 });
  const raw = extractText(result);
  const parsed = extractJson(raw);
  for (const key of ["character_brief", "anchor_prop", "render_identity", "view_mode", "canonical_anchor_plan"]) {
    if (parsed[key] == null) throw new Error(`character design missing ${key}`);
  }
  if (!["front", "top"].includes(parsed.view_mode)) throw new Error("character design has invalid view_mode");
  if (!propIds(primary).includes(parsed.anchor_prop.prop_id) || parsed.anchor_prop.mode !== presentation || !parsed.anchor_prop.placement) throw new Error("character design has invalid anchor_prop");
  validateRenderIdentity(parsed.render_identity, parsed.anchor_prop, primary?.input?.name);
  if (requestedView && parsed.view_mode !== requestedView) throw new Error(`character design ignored requested view_mode ${requestedView}`);
  parsed.chroma_key = "#00FF00";
  parsed.subject_kind = primary.selected.subject_kind;
  if (!Array.isArray(parsed.canonical_anchor_plan) || parsed.canonical_anchor_plan.length !== 3 || !["front", "side", "back"].every((view, index) => parsed.canonical_anchor_plan[index]?.view === view)) {
    throw new Error("canonical_anchor_plan must be front, side, back");
  }
  await fs.writeFile(path.join(characterDir, "character_design_raw.md"), `${raw}\n`);
  return { visualIdentity: parsed, model: model.id };
}
async function inspectCanonicalMaster({ env, requested, file, characterBrief, anchorProp, subjectKind, expectedView, references, directOpenRouter }) {
  try {
    const reviewer = createTextBridge({ env, directOpenRouter });
    const model = await resolveGpt56(reviewer, requested);
    const content = [
      { type: "text", text: `Inspect this ${expectedView} canonical character anchor against the attached style-reference pack. Return JSON only: {"one_character":true,"correct_view":true,"full_silhouette":true,"solid_chroma_background":true,"anatomy_matches_contract":true,"reads_as_widget_character":true,"miniature_proportions":true,"style_matches_reference_pack":true,"prop_presentation_matches":true,"no_extra_props":true,"no_floor_or_shadow":true,"visible_brief_features":["..."],"notes":"..."}. Count only visible facts. correct_view is false if the intended ${expectedView} view is not depicted. anatomy_matches_contract is false only for an extra, duplicated, or physically impossible body part. Do not mark normal occlusion as missing anatomy: in side, back, and top views, limbs and a physically placed prop may be hidden by the body. Set prop_presentation_matches false, rather than anatomy false, when a visible prop is relocated. Set style_matches_reference_pack false when rendering medium, contour treatment, materials, lighting, texture, or colour behaviour differs from the shared style of the references. miniature_proportions must be false when the torso or limbs retain ordinary elongated full-size species proportions. It requires a visibly compressed torso, visibly shortened limbs where the subject has limbs, and an enlarged identity-bearing mass appropriate to the subject; it does not require a fixed head-to-body ratio. Expectation: ${JSON.stringify({ characterBrief, anchorProp, subjectKind })}` },
      { type: "image_url", image_url: { url: dataUrl(file) } },
    ];
    for (const reference of references) content.push({ type: "image_url", image_url: { url: dataUrl(reference.source) } });
    const response = await reviewer.chatCompletions({ payload: { model: model.id, temperature: 0, max_tokens: 600, messages: [{ role: "user", content }] }, timeoutMs: 240000 });
    const report = extractJson(extractText(response));
    const hardRequired = ["one_character", "correct_view", "full_silhouette", "solid_chroma_background", "anatomy_matches_contract", "miniature_proportions", "no_floor_or_shadow"];
    const manualReviewChecks = ["reads_as_widget_character", "style_matches_reference_pack", "prop_presentation_matches", "no_extra_props"];
    const hard_pass = hardRequired.every((key) => report[key] === true);
    const manual_review_flags = manualReviewChecks.filter((key) => report[key] !== true);
    return {
      ...report,
      hard_pass,
      manual_review_flags,
      status: hard_pass ? "accepted_pending_manual_review" : "rejected",
    };
  } catch (error) {
    return { status: "unavailable", error: error.message };
  }
}
async function renderCanonicalAnchors({ env, visualIdentity, lore, refs, characterDir, requested, referenceMaster = true, anchorLimit, existingCanonical, identitySheet, directOpenRouter, skipAiReview = false, maxAttempts = 3 }) {
  const canonicalDir = path.join(characterDir, "canonical");
  await fs.mkdir(canonicalDir, { recursive: true });
  const reviewDir = path.join(canonicalDir, "reviews");
  await fs.mkdir(reviewDir, { recursive: true });
  const { bridge, model } = await imageModel(env);
  const savedFrontAnchor = path.join(canonicalDir, "anchor_00.png");
  const existingAnchors = existingCanonical?.anchors?.filter((file) => fssync.existsSync(file)) || [];
  const outputs = existingCanonical?.turnaround_status === "incomplete"
    ? existingAnchors.slice(0, 1)
    : [...existingAnchors];
  if (!outputs.length && fssync.existsSync(savedFrontAnchor)) outputs.push(savedFrontAnchor);
  let master_validation = existingCanonical?.master_validation || { status: "not_run" };
  const framing_validations = [...(existingCanonical?.framing_validations || [])];
  const anchor_validations = [...(existingCanonical?.anchor_validations || [])];
  const requestedAnchors = visualIdentity.canonical_anchor_plan.slice(0, anchorLimit || undefined);
  for (let index = outputs.length; index < requestedAnchors.length; index += 1) {
    const anchor = requestedAnchors[index];
    const requestedView = String(anchor.view).replace(/_/g, " ");
    const prompt = buildCanonicalRenderPrompt({
      renderIdentity: visualIdentity.render_identity,
      anchorProp: visualIdentity.anchor_prop,
      viewRule: viewRule(anchor.view),
      propViewRule: propViewRule(visualIdentity.anchor_prop, anchor.view),
      anatomyViewRule: anatomyViewRule(visualIdentity, anchor.view),
    });
    const content = [{ type: "text", text: prompt }];
    for (const ref of refs.references) content.push({ type: "image_url", image_url: { url: dataUrl(ref.source) } });
    if (identitySheet && index) {
      content.push({ type: "text", text: "The following approved turntable sheet is an identity reference only. Preserve its character identity while rendering only the requested view." });
      content.push({ type: "image_url", image_url: { url: dataUrl(identitySheet) } });
    }
    if (referenceMaster && index) {
      content.push({ type: "text", text: "The following are identity checks of the same new character from earlier views. Preserve the character identity; change only to the requested view." });
      for (const output of outputs) content.push({ type: "image_url", image_url: { url: dataUrl(output) } });
    }
    let file;
    let framingValidation;
    let anchorValidation;
    for (let attempt = 1; attempt <= maxAttempts; attempt += 1) {
      const response = await bridge.renderRaw({ payload: { model: model.id, messages: [{ role: "system", content: technicalSystemPrompt }, { role: "user", content }], modalities: ["image", "text"], image_config: { aspect_ratio: "1:1" }, stream: false }, timeoutMs: 300000 });
      const image = imageFromResponse(response.data);
      const rawFile = path.join(canonicalDir, `anchor_${String(index).padStart(2, "0")}_raw_attempt_${attempt}${image.extension}`);
      file = path.join(canonicalDir, `anchor_${String(index).padStart(2, "0")}.png`);
      await fs.writeFile(rawFile, image.buffer);
      const validation = skipAiReview
        ? {
            status: "skipped_budget_profile",
            hard_pass: true,
            manual_review_flags: ["ai_review_skipped"],
            notes: "Mascot 3 keeps local framing QA and requires user acceptance instead of a paid AI review.",
          }
        : await inspectCanonicalMaster({ env, requested, file: rawFile, characterBrief: visualIdentity.character_brief, anchorProp: visualIdentity.anchor_prop, subjectKind: visualIdentity.subject_kind, expectedView: requestedView, references: refs.references, directOpenRouter });
      validation.attempt = attempt;
      await write(path.join(reviewDir, `anchor_${String(index).padStart(2, "0")}_attempt_${attempt}.json`), validation);
      anchorValidation = validation;
      if (!index) master_validation = validation;
      if (validation.status !== "rejected") {
        const safeFrame = addSafeChromaFrame(rawFile, file);
        framingValidation = safeFrame.validation;
        if (framingValidation.pass) {
          await fs.unlink(rawFile);
          break;
        }
      }
      if (fssync.existsSync(file)) await fs.rename(file, path.join(canonicalDir, `anchor_${String(index).padStart(2, "0")}_rejected_attempt_${attempt}.png`));
      await fs.rename(rawFile, path.join(canonicalDir, `anchor_${String(index).padStart(2, "0")}_raw_rejected_attempt_${attempt}${image.extension}`));
    }
    anchor_validations.push({ view: anchor.view, ...anchorValidation });
    framing_validations.push({ view: anchor.view, ...framingValidation });
    if (!framingValidation?.pass || anchorValidation.status === "rejected") {
      if (!index) {
        await write(path.join(canonicalDir, "manifest.json"), { status: "canonical_rejected", model: model.id, attempts: maxAttempts, master_validation, anchor_validations, framing_validations });
        throw new Error(`canonical_rejected after ${maxAttempts} attempt(s): ${JSON.stringify({ anchor: anchor.view, framingValidation })}`);
      }
      continue;
    }
    outputs.push(file);
  }
  let vision_similarity = skipAiReview ? { status: "skipped_budget_profile" } : { status: "unavailable" };
  try {
    if (skipAiReview) throw Object.assign(new Error("skip"), { skipReview: true });
    const reviewer = createTextBridge({ env, directOpenRouter });
    const reviewModel = await resolveGpt56(reviewer, requested);
    const content = [{ type: "text", text: `Compare these canonical anchors of one character. Return JSON only: {"pairs":[{"left":0,"right":1,"score":0,"notes":"..."}],"overall_score":0,"notes":"..."}. Scores are 0-1 and assess subject, silhouette cues, attached details, and visual consistency. Character brief: ${JSON.stringify(visualIdentity.character_brief)}` }];
    for (const file of outputs) content.push({ type: "image_url", image_url: { url: dataUrl(file) } });
    const review = await reviewer.chatCompletions({ payload: { model: reviewModel.id, temperature: 0, max_tokens: 800, messages: [{ role: "user", content }] }, timeoutMs: 240000 });
    vision_similarity = extractJson(extractText(review));
  } catch (error) {
    if (!error.skipReview) vision_similarity = { status: "unavailable", error: error.message };
  }
  const turnaround_status = outputs.length === requestedAnchors.length ? "complete" : "incomplete";
  const manifest = {
    status: turnaround_status === "complete" ? "pending_manual_review" : "front_ready",
    turnaround_status, model: model.id, chroma_key: visualIdentity.chroma_key,
    anchors: turnaround_status === "complete" ? outputs : outputs.slice(0, 1),
    turnaround_candidates: turnaround_status === "complete" ? [] : outputs.slice(1),
    plan: requestedAnchors, style_reference_folder: refs.style_pack,
    style_reference_hashes: refs.references.map((ref) => ref.hash), style_reference_count_sent: refs.references.length,
    master_validation, anchor_validations, framing_validations, reference_master_experiment: referenceMaster, vision_similarity,
  };
  await write(path.join(canonicalDir, "manifest.json"), manifest);
  return { canonicalDir, ...manifest };
}
async function renderEnsembleScene({ env, primary, lore, refs, characterDir, requested, directOpenRouter }) {
  const { bridge, model } = await imageModel(env);
  const dir = path.join(characterDir, "ensemble_scene");
  await fs.mkdir(dir, { recursive: true });
  const brief = primary.input.brief || "Nyusha is the primary fish.";
  const content = [{ type: "text", text: `Render an ensemble scene from this client brief: ${brief} Primary character: ${lore.identity.name}, ${lore.identity.subject}. Show one clearly dominant primary fish, exactly three smaller anonymous secondary fish, and one visible aquarium. The three secondary fish have no props, names, distinct stories, or competing focal actions. Use no text, UI, labels, or watermark. The attached images are the mandatory exclusive style source: reproduce their shared visual language exactly.` }];
  for (const ref of refs.references) content.push({ type: "image_url", image_url: { url: dataUrl(ref.source) } });
  const response = await bridge.renderRaw({ payload: { model: model.id, messages: [{ role: "system", content: "Render one contained aquarium ensemble scene. Do not apply the single-character canonical-anchor rules." }, { role: "user", content }], modalities: ["image", "text"], image_config: { aspect_ratio: "1:1" }, stream: false }, timeoutMs: 300000 });
  const image = imageFromResponse(response.data);
  const file = path.join(dir, `scene_00${image.extension}`);
  await fs.writeFile(file, image.buffer);
  const reviewer = createTextBridge({ env, directOpenRouter });
  const reviewModel = await resolveGpt56(reviewer, requested);
  const reviewContent = [{ type: "text", text: `Review this aquarium ensemble image against attached style references. Return JSON only: {"one_primary_fish":true,"exactly_three_secondary_fish":true,"visible_aquarium":true,"no_text_or_ui":true,"style_matches_reference_pack":true,"notes":"..."}. The primary fish must be visually dominant; the three secondary fish must be anonymous and secondary.` }, { type: "image_url", image_url: { url: dataUrl(file) } }];
  for (const ref of refs.references) reviewContent.push({ type: "image_url", image_url: { url: dataUrl(ref.source) } });
  const review = extractJson(extractText(await reviewer.chatCompletions({ payload: { model: reviewModel.id, temperature: 0, max_tokens: 500, messages: [{ role: "user", content: reviewContent }] }, timeoutMs: 240000 })));
  review.status = ["one_primary_fish", "exactly_three_secondary_fish", "visible_aquarium", "no_text_or_ui", "style_matches_reference_pack"].every((key) => review[key] === true) ? "accepted" : "rejected";
  await write(path.join(dir, "manifest.json"), { status: review.status, model: model.id, scene: file, style_reference_folder: refs.style_pack, review });
  return { dir, file, review };
}
async function main() {
  const options = args(process.argv.slice(2));
  if (options["canonical-attempts"] !== undefined) {
    const attempts = Number(options["canonical-attempts"]);
    if (!Number.isInteger(attempts) || attempts < 1 || attempts > 3) throw new Error("--canonical-attempts must be 1, 2, or 3");
  }
  const directOpenRouter = options["direct-openrouter"] === "yes";
  if (options["character-dir"] && options.animation) {
    if (options.phase && options.phase !== "loop") throw new Error("Animation phases were removed; production supports one bookended loop only");
    if (options.through) throw new Error("--through is only available while creating a character; production animation has one bookended-loop stage");
    const env = loadEnvFileDefaults({ env: process.env, workspaceRoot: path.resolve(ROOT, "..") });
    const result = await runProductionAnimation({ root: ROOT, env, characterDir: path.resolve(options["character-dir"]), animationId: options.animation, options });
    console.log(JSON.stringify(result, null, 2));
    return;
  }
  if (options.phase && options.phase !== "canonical") throw new Error("Character creation ends at canonical; submit a loop with --character-dir and --animation");
  const through = options.dryRun ? "deal" : (options.through || "lore");
  const upTo = stageIndex(through);
  const env = loadEnvFileDefaults({ env: process.env, workspaceRoot: path.resolve(ROOT, "..") });
  const runId = `${new Date().toISOString().replace(/[:.]/g, "-")}_${crypto.randomUUID().slice(0, 8)}`;
  const runDir = path.join(DATA_ROOT, "runs", runId); await fs.mkdir(runDir, { recursive: true });
  let primary;
  let dictionaries;
  let composed;
  let characterDir;
  if (options["character-dir"]) {
    characterDir = path.resolve(options["character-dir"]);
    primary = await json(path.join(characterDir, "primary_data.json"));
    const lorePath = path.join(characterDir, "lore.json");
    composed = fssync.existsSync(lorePath) ? { lore: await json(lorePath) } : null;
  } else {
    dictionaries = await loadDictionaries(ROOT);
    const dealOptions = { seed: options.seed, name: options.name, subject: options.subject, subjectKind: options["subject-kind"], gender: options.gender, age: options.age, origin: options.origin, brief: options.brief };
    primary = options.series
      ? await buildPrimaryDataWithBags(dictionaries, {
        ...dealOptions,
        draw: (request) => drawBag(options["bag-state"] || path.join(DATA_ROOT, "_state", "series_bags.json"), {
          corpus_version: corpusHash(dictionaries),
          ...request,
        }),
        drawName: (request) => drawBag(options["name-bag-state"] || path.join(DATA_ROOT, "_state", "name_bags.json"), {
          corpus_version: corpusHash(dictionaries),
          ...request,
        }),
      })
      : buildPrimaryData(dictionaries, dealOptions);
    composed = null;
  }
  if (dictionaries && !options.dryRun) await claimGeneratedName(primary, dictionaries);
  if (options.rerunLore) composed = null;
  await write(path.join(runDir, "primary_data.json"), primary);
  await fs.writeFile(path.join(runDir, "fingerprint.txt"), `${primary.fingerprint}\n`);
  await write(path.join(runDir, "cost_plan.json"), { text_jobs: upTo >= 1 ? 2 : 0, character_design_jobs: upTo >= 3 ? 1 : 0, canonical_anchor_jobs_once_per_character: upTo >= 4 ? 3 : 0, note: "Animation is a separate, explicitly requested bookended-loop production operation." });
  if (upTo === 0) return console.log(JSON.stringify({ status: "dry-run", runDir, fingerprint: primary.fingerprint }, null, 2));
  if (!composed) {
    const loreRun = path.join(runDir, "lore"); await fs.mkdir(loreRun);
    composed = await composeLore({
      root: ROOT, env, primaryData: primary, modelId: options["text-model"] || env.PET_V2_TEXT_MODEL,
      runDir: loreRun, recentSubjects: await recentSubjects(), directOpenRouter,
    });
  }
  if (composed.lore.identity.subject_kind !== primary.selected.subject_kind) throw new Error("lore changed subject_kind");
  if (primary.selected.requested_object) {
    if (composed.lore.identity.subject.trim().toLocaleLowerCase() === primary.selected.requested_object.label.trim().toLocaleLowerCase()) {
      throw new Error("requested object became character subject");
    }
    if (!hasRequestedObject(composed.lore, primary.selected.requested_object)) throw new Error("lore omitted requested object from passport props");
  }
  if (primary.selected.subject_request && composed.lore.identity.subject !== primary.selected.subject_request) throw new Error("lore changed requested subject");
  characterDir ||= path.join(DATA_ROOT, "characters", `${slug(composed.lore.identity.name)}_${slug(composed.lore.identity.subject)}_${primary.fingerprint.slice(0, 8)}`);
  await fs.mkdir(characterDir, { recursive: true });
  await write(path.join(characterDir, "primary_data.json"), primary); await write(path.join(characterDir, "lore.json"), composed.lore);
  if (upTo === 1) return console.log(JSON.stringify({ status: "lore-complete", characterDir, runDir }, null, 2));
  const manifestPath = path.join(characterDir, "reference_manifest.json");
  if (options.rerunRefs) {
    await fs.rm(manifestPath, { force: true });
    await fs.rm(path.join(characterDir, "refs_used"), { recursive: true, force: true });
  }
  const refs = fssync.existsSync(manifestPath)
    ? await json(manifestPath)
    : await buildReferenceManifest(options["refs-root"] || path.join(ROOT, "refs"), primary.seed);
  if (!fssync.existsSync(manifestPath)) {
    await write(manifestPath, refs);
    await copySelectedReferences(refs, path.join(characterDir, "refs_used"));
  }
  refs.references = refs.references.map((reference, index) => {
    if (fssync.existsSync(reference.source)) return reference;
    const copied = path.join(characterDir, "refs_used", `ref_${String(index).padStart(2, "0")}${path.extname(reference.source).toLowerCase()}`);
    if (!fssync.existsSync(copied)) throw new Error(`reference source and saved copy are unavailable: ${reference.source}`);
    return { ...reference, source: copied };
  });
  if (options.ensembleScene) {
    const ensemble = await renderEnsembleScene({
      env, primary, lore: composed.lore, refs, characterDir,
      requested: options["art-model"] || options["text-model"] || env.PET_V2_TEXT_MODEL, directOpenRouter,
    });
    return console.log(JSON.stringify({ status: "ensemble-scene-complete", characterDir, runDir, ...ensemble }, null, 2));
  }
  if (upTo === 2) return console.log(JSON.stringify({ status: "refs-complete", characterDir, runDir }, null, 2));
  const visualIdentityPath = path.join(characterDir, "visual_identity.json");
  if (options.rerunCharacterDesign) await fs.rm(visualIdentityPath, { force: true });
  const visualIdentity = fssync.existsSync(visualIdentityPath)
    ? await json(visualIdentityPath)
    : (await characterDesign({
      env, primary, lore: composed.lore, characterDir,
      requested: options["art-model"] || options["text-model"] || env.PET_V2_TEXT_MODEL,
      requestedView: options["view-mode"], directOpenRouter,
    })).visualIdentity;
  if (!fssync.existsSync(visualIdentityPath)) await write(visualIdentityPath, visualIdentity);
  if (upTo === 3) return console.log(JSON.stringify({ status: "character-design-complete", characterDir, runDir }, null, 2));
  const canonicalManifestPath = path.join(characterDir, "canonical", "manifest.json");
  if (options.rerunCanonical) await fs.rm(path.dirname(canonicalManifestPath), { recursive: true, force: true });
  const existingCanonical = fssync.existsSync(canonicalManifestPath) ? await json(canonicalManifestPath) : null;
  const canonical = existingCanonical && !options.extendCanonical
    ? existingCanonical
    : await renderCanonicalAnchors({
      env, visualIdentity, lore: composed.lore, refs, characterDir,
      requested: options["art-model"] || options["text-model"] || env.PET_V2_TEXT_MODEL,
      referenceMaster: true, anchorLimit: options["anchor-limit"] ? Number(options["anchor-limit"]) : undefined,
      existingCanonical: options.extendCanonical ? existingCanonical : null,
      identitySheet: options["identity-sheet"] ? path.resolve(options["identity-sheet"]) : null,
      directOpenRouter, skipAiReview: options.skipCanonicalAiReview,
      maxAttempts: options["canonical-attempts"] ? Number(options["canonical-attempts"]) : 3,
    });
  if (options.approveCanonical && canonical.status !== "approved") {
    canonical.status = "approved";
    canonical.approved_at = new Date().toISOString();
    canonical.manual_verdict = "approved";
    canonical.manual_review_notes = options["canonical-review-notes"] || null;
    await write(canonicalManifestPath, canonical);
  }
  const canonicalUsableForFrontPipeline = ["approved", "front_ready"].includes(canonical.status);
  if (upTo === 4 || !canonicalUsableForFrontPipeline) return console.log(JSON.stringify({ status: canonical.status === "front_ready" ? "turnaround-incomplete-front-ready" : "canonical-review-required", characterDir, runDir, canonicalDir: canonical.canonicalDir || path.dirname(canonicalManifestPath) }, null, 2));
  return console.log(JSON.stringify({ status: "canonical-complete", characterDir, runDir }, null, 2));
}
main().catch((error) => { console.error(error.stack || error.message); if (error.body !== undefined) console.error(`PROVIDER_ERROR_BODY ${JSON.stringify(error.body)}`); process.exitCode = 1; });
