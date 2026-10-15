const sharp = require('sharp');

/** Remove chroma contamination only beside the alpha silhouette, never in its interior. */
async function cleanMatteEdges(matte, backgroundImage) {
  const { data, info } = await sharp(matte).ensureAlpha().raw().toBuffer({ resolveWithObject: true });
  const unchanged = () => ({ data, info, report: { applied: false, changed_pixels: 0 } });
  if (!backgroundImage) return unchanged();
  const source = await sharp(backgroundImage).resize(info.width, info.height).ensureAlpha().raw().toBuffer();
  const sums = [0, 0, 0]; let samples = 0;
  for (let i = 0; i < data.length; i += 4) {
    if (data[i + 3] > 8 || source[i + 3] < 250) continue;
    for (let k = 0; k < 3; k++) sums[k] += source[i + k];
    samples++;
  }
  if (samples < 64) return unchanged();
  const background = sums.map(value => value / samples);
  const dominant = background[1] > background[2] ? 1 : 2;
  const others = [0, 1, 2].filter(k => k !== dominant);
  if (background[dominant] < 90 || background[dominant] - Math.max(...others.map(k => background[k])) < 60) return unchanged();
  const w = info.width, h = info.height, radius = Math.max(2, Math.ceil(w / 240));
  const distance = new Uint8Array(w * h).fill(255);
  for (let i = 0; i < distance.length; i++) if (data[i * 4 + 3] <= 8) distance[i] = 0;
  for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) {
    const i = y * w + x;
    distance[i] = Math.min(distance[i], x ? distance[i - 1] + 1 : 255, y ? distance[i - w] + 1 : 255);
  }
  for (let y = h - 1; y >= 0; y--) for (let x = w - 1; x >= 0; x--) {
    const i = y * w + x;
    distance[i] = Math.min(distance[i], x + 1 < w ? distance[i + 1] + 1 : 255, y + 1 < h ? distance[i + w] + 1 : 255);
  }
  function smooth(a, b, value) { const t = Math.max(0, Math.min(1, (value - a) / (b - a))); return t * t * (3 - 2 * t); }
  let changed = 0;
  for (let i = 0; i < distance.length; i++) {
    const p = i * 4;
    if (distance[i] > radius || data[p + 3] === 0) continue;
    const other = Math.max(...others.map(k => data[p + k]));
    const excess = (data[p + dominant] - other) / 255;
    const matteValue = 1 - smooth(.07, .22, excess);
    if (matteValue >= 1) continue;
    data[p + 3] = Math.round(data[p + 3] * matteValue);
    const edge = 1 - smooth(.70, .98, matteValue);
    data[p + dominant] = Math.round(data[p + dominant] + (Math.min(data[p + dominant], other + 255 * .045) - data[p + dominant]) * edge);
    changed++;
  }
  return { data, info, report: { applied: true, background_chroma: dominant === 1 ? 'green' : 'blue', edge_radius_px: radius, changed_pixels: changed } };
}
module.exports = { cleanMatteEdges };
