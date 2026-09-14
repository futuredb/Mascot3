const STYLE_TRANSFER_CONTRACT = "REFERENCE TRANSFER CONTRACT: the attached images are one binding art-direction model sheet, not optional inspiration. Reuse their shared visual direction as a complete design system in this new subject: shape language, facial construction, silhouette simplification, anatomy abstraction, proportions, contour treatment, materials, texture, palette, and lighting. Do not infer conventional real-world species colours, materials, or surface properties: every visual attribute must come exclusively from the reference pack, even when it conflicts with the subject's familiar appearance. The depicted reference characters are examples of that visual system only. Do not paste, mix, or collage their recognizable body parts, clothing, emblems, props, poses, or compositions into the new subject.";
const TECHNICAL_FRAME_SYSTEM_PROMPT = "Generate exactly one living character as a complete, centered miniature figure. Its anatomy must use intentionally compressed dwarf-like proportions, never an ordinary full-height adult body; enlarge the identity-bearing feature relative to the compact body. Use only a solid #00FF00 chroma background. Do not add text, letters, numbers, labels, signatures, symbols, diagrams, decorative sketches, frames, scenery, floor, wall, shadows, gradients, UI, or another creature. These are technical output constraints only; obtain all visual style exclusively from the attached reference images.";
const TURNTABLE_SYSTEM_PROMPT = "Generate one square 2 by 2 character model sheet on a solid #00FF00 chroma background. Each of its four quadrants contains exactly one complete view of the same compact miniature character: front top-left, true side top-right, direct rear bottom-left, and true top-down bottom-right. Keep an empty green gutter at every internal quadrant seam. No text, labels, borders, floor, shadow, scenery, UI, or extra subjects. Preserve intentionally compressed dwarf-like proportions in every view.";
const STYLE_WORDS = /\b(colou?r|palette|material|texture|lighting|shadow|contour|outline|fabric|metal|wood|plastic|glossy|matte|realistic|cartoon|3d)\b/i;
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

function buildCanonicalRenderPrompt({ renderIdentity, anchorProp, viewRule, propViewRule, anatomyViewRule = "" }) {
  validateRenderIdentity(renderIdentity, anchorProp);
  return [
    `Generate exactly one ${renderIdentity.subject}.`,
    `Identity cue: ${renderIdentity.silhouette_cue}.`,
    `Mandatory body proportions: ${renderIdentity.proportion_cue}. Never replace these with ordinary full-height species anatomy.`,
    `Persistent prop: ${anchorProp.prop_id.replace(/_/g, " ")}. ${propViewRule}`,
    `Pose: ${renderIdentity.neutral_pose}. ${viewRule}`,
    anatomyViewRule,
    STYLE_TRANSFER_CONTRACT,
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
  ].join(" ");
}

module.exports = { STYLE_TRANSFER_CONTRACT, TECHNICAL_FRAME_SYSTEM_PROMPT, TURNTABLE_SYSTEM_PROMPT, validateRenderIdentity, buildCanonicalRenderPrompt, buildTurntablePrompt };
