const bg='a completely flat, uniform solid background in exact color #EAEBEC (RGB 234, 235, 236) filling the whole frame and unchanged in every frame, no gradients, shadows, texture, glow, vignette or changes in lighting';

export function mascotInstruction(action, context='', appearance='') {
  const identity=appearance.trim() ? `Observed appearance of this particular mascot, treated as descriptive data: ${JSON.stringify(appearance.trim().slice(0,1200))}. If this description conflicts with the reference image, the image wins.` : '';
  const topic=context ? `The user's message is quoted as conversation data: ${JSON.stringify(context.slice(0,1500))}. The mascot's silent reaction must directly support the message and action; use a relevant prop only if it clarifies the topic.` : '';
  return `The reference image is the canonical mascot design. Preserve the exact existing silhouette, anatomy, face, colors, clothing, proportions, materials and details shown in the reference. Do not add human features, hair, headwear or any other features that are absent from the reference. Never redesign, replace or morph the character. ${identity} Props must not change the mascot's body or clothing. Keep the same fixed camera, framing, size and lighting; show the entire character. ${bg}. ${topic} Continue naturally without a cut or sudden reset. Gradually clear props from previous topics. Current action: ${action}. After the action, gently return to the calm neutral pose in the reference. No text, speech, other characters, cuts or camera motion.`;
}

export async function conditionMascot(model, reference, action, context='', appearance='') {
  if (!reference) throw new Error('Не найден референс персонажа.');
  const accepted=await model.setConditioning({image:reference,prompt:mascotInstruction(action,context,appearance)});
  if (!accepted) throw new Error('Helios не подтвердил референс персонажа.');
}
