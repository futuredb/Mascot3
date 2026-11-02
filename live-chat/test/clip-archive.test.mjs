import test from 'node:test';
import assert from 'node:assert/strict';
import {createClipArchive} from '../src/clip-archive.js';

test('saves all response chunks in order and never archives idle footage', async () => {
  const bytes = [];
  const events = [];
  const bridge = {
    beginClip: (title, mime) => { events.push([title, mime]); return 'clip'; },
    appendClip: (id, part) => { assert.equal(id, 'clip'); bytes.push(Buffer.from(part, 'base64')); return true; },
    finishClip: id => { events.push(['finish', id]); return true; },
    abortClip: () => assert.fail('should not abort'),
  };
  assert.equal(createClipArchive(bridge, '', 'video/webm'), null);
  const archive = createClipArchive(bridge, 'Теннис', 'video/webm;codecs=vp8');
  const a = Buffer.alloc(240000, 17), b = Buffer.from('last frame');
  archive.append(new Blob([a])); archive.append(new Blob([b]));
  await archive.finish();
  assert.deepEqual(Buffer.concat(bytes), Buffer.concat([a, b]));
  assert.deepEqual(events, [['Теннис', 'video/webm;codecs=vp8'], ['finish', 'clip']]);
});

test('a failed write aborts the partial clip without publishing it', async () => {
  let aborted = 0, finished = 0, reported = 0;
  const bridge = {beginClip: () => 'clip', appendClip: () => false,
    abortClip: () => aborted++, finishClip: () => { finished++; return true; }};
  const archive = createClipArchive(bridge, 'Привет', 'video/webm', () => reported++);
  archive.append(new Blob(['broken']));
  await archive.finish();
  assert.equal(aborted, 1); assert.equal(finished, 0); assert.equal(reported, 1);
});
