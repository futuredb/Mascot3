import test from 'node:test';
import assert from 'node:assert/strict';
import {conditionMascot, mascotInstruction} from './src/helios-conditioning.js';

test('each reaction resubmits the same reference with its new context and appearance', async () => {
  const calls=[];
  const model={setConditioning:async input=>{calls.push(input);return {type:'conditions_ready'}}};
  const reference={id:'akim-reference'};
  await conditionMascot(model,reference,'waves','hello','A purple bald figure in a coat');
  await conditionMascot(model,reference,'ponders','books','A purple bald figure in a coat');
  assert.equal(calls.length,2);
  assert.equal(calls[0].image,reference);
  assert.equal(calls[1].image,reference);
  assert.match(calls[1].prompt,/purple bald figure/);
  assert.match(calls[1].prompt,/books/);
  assert.match(calls[1].prompt,/Do not add human features/);
  assert.doesNotMatch(mascotInstruction('waves'),/skin tone, hairstyle, helmet/);
});

test('a missing or rejected reference fails rather than silently falling back', async () => {
  await assert.rejects(conditionMascot({},null,'waves'),/референс/);
  await assert.rejects(conditionMascot({setConditioning:async()=>undefined},{id:'x'},'waves'),/не подтвердил/);
});
