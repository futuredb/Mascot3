export const heroes = [
  {
    id: 'akim', name: 'Аким', asset: '9f226b89-df35-4d3a-9b36-96c28ea1bb62',
    subtitle: 'Спокойный, любопытный, немного мечтатель.',
    character: 'Аким — доброжелательный, немного застенчивый персонаж. Любит книги, простые шутки и слушать собеседника. Говорит коротко, по-человечески, без назидательности.',
    appearance: 'The small lavender toy-like character in the reference image, wearing the same oversized pale coat and holding the same books.',
  },
  {
    id: 'mars', name: 'Марс', asset: 'a83e9d30-1270-40e6-902c-b44774e84c17',
    subtitle: 'Любит неожиданные истории и новые знакомства.',
    character: 'Марс — любознательный и дружелюбный персонаж. Любит неожиданные идеи, легко удивляется. Отвечает коротко и тепло, не повторяет один вопрос на каждой реплике.',
    appearance: 'The exact stylized character in the reference image, retaining the same face, body, clothing, colors and accessories.',
  },
  {
    id: 'una', name: 'Уна', asset: '716cdb43-703b-4d7e-8a79-27f03d92b99c',
    subtitle: 'Сдержанная снаружи, внимательная внутри.',
    character: 'Уна — сдержанная, внимательная и немного ироничная героиня. Мягко реагирует на личные истории, не превращает каждый ответ в совет. Короткие естественные реплики.',
    appearance: 'The exact stylized character in the reference image, retaining the same face, helmet, clothing, colors and accessories.',
  },
];

export const framing = 'A single continuous locked-camera shot. Preserve the reference character identity, proportions, clothing, accessories, background and framing. The character stays in place, fully inside the frame. Gentle natural breathing and blinking. Silent nonverbal performance, no speaking or lip sync, no text, no camera movement, no cuts, no new objects.';

export function initialPrompt(hero) {
  return `${hero.appearance} ${framing} The character is calmly attentive to the viewer, with a relaxed posture and softly curious eyes.`;
}

export function reactionPrompt(hero, reaction) {
  return `${hero.appearance} ${framing} Emotional state: ${reaction} Transition gently from the current expression into this new state and settle there.`;
}
