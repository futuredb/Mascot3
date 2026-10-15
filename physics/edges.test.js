const test = require('node:test');
const assert = require('node:assert/strict');
const sharp = require('sharp');
const { cleanMatteEdges } = require('./edges');

async function image(data, size) { return sharp(data, { raw: { width: size, height: size, channels: 4 } }).png().toBuffer(); }
test('green silhouette fringe is removed while opaque green interior detail is preserved', async () => {
  const size = 64, matte = Buffer.alloc(size * size * 4), source = Buffer.alloc(matte.length);
  for (let y = 0; y < size; y++) for (let x = 0; x < size; x++) {
    const p = (y * size + x) * 4;
    source.set([0, 255, 0, 255], p);
    if (x >= 12 && x < 52 && y >= 12 && y < 52) {
      const fringe = x === 12 || x === 51 || y === 12 || y === 51;
      matte.set(fringe ? [0, 255, 0, 255] : [180, 30, 40, 255], p);
      source.set(matte.subarray(p, p + 4), p);
    }
  }
  matte.set([0, 255, 0, 255], (32 * size + 32) * 4);
  const result = await cleanMatteEdges(await image(matte, size), await image(source, size));
  assert.equal(result.report.applied, true);
  assert.ok(result.report.changed_pixels > 100);
  assert.equal(result.data[(12 * size + 24) * 4 + 3], 0);
  assert.deepEqual([...result.data.subarray((32 * size + 32) * 4, (32 * size + 32) * 4 + 4)], [0, 255, 0, 255]);
  assert.deepEqual([...result.data.subarray((14 * size + 24) * 4, (14 * size + 24) * 4 + 4)], [180, 30, 40, 255]);
});
test('a thin red cord is not eroded and an already transparent source does not become a chroma key', async () => {
  const size = 64, matte = Buffer.alloc(size * size * 4), source = Buffer.alloc(matte.length);
  for (let i = 0; i < source.length; i += 4) source.set([0, 255, 0, 255], i);
  for (let y = 10; y < 54; y++) matte.set([190, 35, 40, 255], (y * size + 32) * 4);
  const bytes = await image(matte, size);
  const keyed = await cleanMatteEdges(bytes, await image(source, size));
  assert.deepEqual(keyed.data, matte);
  const transparent = await cleanMatteEdges(bytes, bytes);
  assert.equal(transparent.report.applied, false);
  assert.deepEqual(transparent.data, matte);
});
