const bg='The background is a uniform solid light grey #EAEBEC. Fixed camera, fixed scale and lighting.';
export const IDENTITY_REFRESH_MS = 15_000;

export function mascotInstruction(action, context='', appearance='', reply='') {
  const identity=appearance.trim() ? `Visible identity, which must remain unchanged: ${appearance.trim().replace(/[\r\n]+/g, ' ').slice(0,1200)}. The reference image takes priority over this description.` : 'Use the exact appearance and natural expression in the reference image.';
  const motion=String(action || '').replace(/[\r\n]+/g, ' ').trim().slice(0,600) || 'Gentle breathing.';
  return `One uninterrupted shot of the same character in the reference image. Keep a fixed camera and framing: preserve the character's scale and general position, with the complete character visible and space around the body. No upward drift, zoom, reframing or camera movement. ${identity} Preserve the character's exact face design, eye shapes and colors, body proportions, hairstyle, markings, clothes, accessories and rendering style throughout. Keep the original temperament. ${bg} Action: ${motion} Perform the action clearly using natural movement and the character's existing limbs and expressions. When the action calls for an object, show and use the relevant object or tools as separate temporary props; keep them within the frame and do not merge them with the character. Do not add or change body parts, clothing, other characters or facial anatomy. Return naturally toward a calm pose after the action. No speech, text or cuts.`;
}

export async function conditionMascot(model, reference, action, context='', appearance='', reply='') {
  if (!reference) throw new Error('Не найден референс персонажа.');
  const accepted=await model.setConditioning({image:reference,prompt:mascotInstruction(action,context,appearance,reply)});
  if (!accepted?.has_image || !accepted?.has_prompt) throw new Error('Helios не подтвердил референс и реакцию.');
}

// The image is already anchored when the stream starts. Replacing it during
// playback can introduce a discontinuity; steer only the next prompt instead.
export async function updateMascotPrompt(model, action, context='', appearance='', reply='') {
  const accepted = await model.setPrompt({prompt: mascotInstruction(action, context, appearance, reply)});
  if (!accepted) throw new Error('Helios не подтвердил реакцию.');
}
