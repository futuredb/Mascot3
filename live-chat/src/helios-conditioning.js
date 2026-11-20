const bg='The background is a uniform solid light grey #EAEBEC. Fixed camera, fixed scale and lighting.';
export const IDENTITY_REFRESH_MS = 15_000;

export function mascotInstruction(action, context='', appearance='', reply='') {
  const identity = appearance.trim().replace(/[\r\n]+/g, ' ').slice(0, 240);
  const motion = String(action || '').replace(/[\r\n]+/g, ' ').trim().slice(0, 600) || 'Gentle breathing.';
  const appearanceHint = identity ? ` Appearance hint: ${identity}. Follow the reference first.` : '';
  return `Animate the same character from the reference.${appearanceHint} ${bg} Keep the full character in frame at the same position and scale, without zoom or cuts. Action: ${motion} Move naturally. Use separate props only when the action calls for them. No speech or text.`;
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
