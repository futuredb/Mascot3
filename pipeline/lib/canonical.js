const STYLE_TRANSFER_CONTRACT = "REFERENCE TRANSFER CONTRACT: use the attached style references as the binding source for the rendering style, contour treatment, materials, texture and palette. Preserve the requested subject, body topology, age cues, silhouette and chosen prop; do not copy recognizable body parts, outfits, props or poses from the reference characters. Do not infer conventional real-world species colours when they conflict with the reference palette.";
const TECHNICAL_FRAME_SYSTEM_PROMPT = "Generate exactly one living character as a complete, centered miniature figure. Use intentionally compressed proportions and enlarge the identity-bearing feature relative to the compact body. Use a solid #00FF00 chroma background. No text, symbols, frames, scenery, floor, wall, cast or contact shadows, gradients, UI, or another creature. Use broad neutral soft diffuse illumination with gentle shading on the character and restrained highlights. Take the remaining rendering style from the attached style references.";
const TURNTABLE_SYSTEM_PROMPT = "Generate one square 2 by 2 character model sheet on a solid #00FF00 chroma background. Each of its four quadrants contains exactly one complete view of the same compact miniature character: front top-left, true side top-right, direct rear bottom-left, and true top-down bottom-right. Keep an empty green gutter at every internal quadrant seam. No text, labels, borders, floor, shadow, scenery, UI, or extra subjects. Preserve intentionally compressed dwarf-like proportions in every view.";
const STYLE_WORDS = /\b(colou?r|palette|material|texture|lighting|shadow|contour|outline|fabric|metal|wood|plastic|glossy|matte|realistic|cartoon|3d)\b/i;
const RELIGHTABLE_CONTRACT = "RELIGHTABLE SURFACE: preserve the reference's design, texture and material identity, while using broad neutral soft diffuse illumination with gentle form shading and readable relief. This lighting requirement overrides dramatic reference lighting. Keep specular response restrained and broad, without clipped highlights, glossy hotspots, hard directional shadows, rim light, bloom or painted glow. Keep detail readable in light and dark areas; do not flatten the form.";
const PLACEMENT_PRONOUN = /\b(?:he|she|his|her|him|hers|they|their|theirs)\b/i;
const escapeRegex = (value) => String(value).replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
const identityNames = (renderIdentity, characterName) => [
  characterName,
  String(renderIdentity?.subject || "").split(",")[0].trim(),
].filter((value) => /^[A-ZА-ЯЁ][a-zа-яё-]+$/u.test(value));

function validateRenderIdentity(renderIdentity, anchorProp, characterName = null) {
  if (!renderIdentity || typeof renderIdentity !== "object") throw new Error("character design missing render_identity");
  for (const key of ["subject", "silhouette_cue", "proportion_cue", "neutral_pose"]) {
    if (typeof renderIdentity[key] !== "string" || !renderIdentity[key].trim()) throw new Error(`render_identity.${key} must be a non-empty string`);
    if (STYLE_WORDS.test(renderIdentity[key])) throw new Error(`render_identity.${key} contains a forbidden style attribute`);
  }
  if (renderIdentity.persistent_prop !== anchorProp.prop_id || renderIdentity.prop_placement !== anchorProp.placement) {
    throw new Error("render_identity persistent prop must match anchor_prop");
  }
  if (typeof anchorProp?.placement !== "string" || !anchorProp.placement.trim()) throw new Error("anchor_prop.placement must be a non-empty string");
  if (PLACEMENT_PRONOUN.test(anchorProp.placement) || identityNames(renderIdentity, characterName).some((name) => new RegExp(`\\b${escapeRegex(name)}(?:'s)?\\b`, "i").test(anchorProp.placement))) {
    throw new Error("anchor_prop.placement must not contain a character name or gendered pronoun");
  }
  return renderIdentity;
}

function validateAnchorPlan(viewMode, plan) {
  return ["front", "top"].includes(viewMode) && Array.isArray(plan) && plan.length === 3
    && [viewMode, "side", "back"].every((view, index) => plan[index]?.view === view);
}

function buildCanonicalRenderPrompt({ renderIdentity, anchorProp, characterBrief, propLabel, viewRule, propViewRule, anatomyViewRule = "" }) {
  validateRenderIdentity(renderIdentity, anchorProp);
  return [
    `Generate exactly one ${renderIdentity.subject}.`,
    `Identity cue: ${renderIdentity.silhouette_cue}.`,
    characterBrief ? `Character identity details: ${characterBrief} Use only the persistent prop named below; the requested view and pose take priority.` : "",
    `Mandatory body proportions: ${renderIdentity.proportion_cue}. Never replace these with ordinary full-height species anatomy.`,
    `Persistent prop: ${propLabel || anchorProp.prop_id.replace(/_/g, " ")}. ${propViewRule}`,
    `Pose: ${renderIdentity.neutral_pose}. ${viewRule}`,
    anatomyViewRule,
    STYLE_TRANSFER_CONTRACT,
    RELIGHTABLE_CONTRACT,
  ].join(" ");
}
function buildTurntablePrompt({ renderIdentity, anchorProp }) {
  validateRenderIdentity(renderIdentity, anchorProp);
  return [
    `Generate exactly one ${renderIdentity.subject} shown four times as the same character in the required model-sheet views.`,
    `Identity cue: ${renderIdentity.silhouette_cue}.`,
    `Mandatory body proportions in every view: ${renderIdentity.proportion_cue}. Never replace these with ordinary full-height species anatomy.`,
    `Persistent prop: ${anchorProp.prop_id.replace(/_/g, " ")}. Keep its physical attachment or position consistent; it may be naturally occluded from rear or top views and must not be moved merely to remain visible.`,
    `Pose: ${renderIdentity.neutral_pose}.`,
    STYLE_TRANSFER_CONTRACT,
    RELIGHTABLE_CONTRACT,
  ].join(" ");
}

module.exports = { STYLE_TRANSFER_CONTRACT, TECHNICAL_FRAME_SYSTEM_PROMPT, TURNTABLE_SYSTEM_PROMPT, validateRenderIdentity, validateAnchorPlan, buildCanonicalRenderPrompt, buildTurntablePrompt };
