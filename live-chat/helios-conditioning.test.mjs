import test from 'node:test';
import assert from 'node:assert/strict';
import {conditionMascot, updateMascotPrompt} from './src/helios-conditioning.js';

test('starts with a reference and updates the reaction without replacing the image', async () => {
  const conditioning = [];
  const updates = [];
  const model = {
    async setConditioning(input) {
      conditioning.push(input);
      return {has_image: true, has_prompt: true};
    },
    async setPrompt(input) {
      updates.push(input);
      return true;
    },
  };
  const reference = {id: 'akim-reference'};
  const appearance = 'A purple bald figure in a coat';
  await conditionMascot(model, reference, 'waves', '', appearance);
  await updateMascotPrompt(model, 'ponders', 'books', appearance);
  assert.equal(conditioning.length, 1);
  assert.equal(conditioning[0].image, reference);
  assert.match(conditioning[0].prompt, /waves/);
  assert.equal(updates.length, 1);
  assert.deepEqual(Object.keys(updates[0]), ['prompt']);
  assert.match(updates[0].prompt, /purple bald figure/);
  assert.match(updates[0].prompt, /ponders/);
});

test('a missing or rejected reference fails rather than silently falling back', async () => {
  await assert.rejects(conditionMascot({},null,'waves'),/референс/);
  await assert.rejects(conditionMascot({setConditioning:async()=>undefined},{id:'x'},'waves'),/не подтвердил/);
});
