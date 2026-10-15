const sharp = require('sharp');
const fs = require('node:fs/promises');
const path = require('node:path');
const { requireValue, sha256 } = require('./contracts');

async function rgba(bytes, width, height) {
  const image = sharp(bytes, { limitInputPixels: 2048 * 2048 }).ensureAlpha();
  return (await (width ? image.resize(width, height, { fit: 'fill' }) : image).raw().toBuffer({ resolveWithObject: true }));
}
const png = (data, width, height, channels = 4) => sharp(data, { raw: { width, height, channels } }).png().toBuffer();
function boundsOf(mask, w, h) {
  let left = w, top = h, right = -1, bottom = -1, area = 0;
  for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) if (mask[y * w + x] > 16) {
    left = Math.min(left, x); top = Math.min(top, y); right = Math.max(right, x); bottom = Math.max(bottom, y); area++;
  }
  requireValue(area >= 20, 'mask_empty_or_too_small');
  return { left, top, right, bottom, area };
}
function singleComponent(mask, w, h) {
  const seen = new Uint8Array(w * h); let count = 0, largest = [];
  for (let start = 0; start < mask.length; start++) {
    if (mask[start] <= 16 || seen[start]) continue;
    const queue = [start]; seen[start] = 1;
    for (let n = 0; n < queue.length; n++) {
      const i = queue[n], x = i % w, y = Math.floor(i / w);
      for (const j of [x ? i - 1 : -1, x + 1 < w ? i + 1 : -1, y ? i - w : -1, y + 1 < h ? i + w : -1]) {
        if (j >= 0 && !seen[j] && mask[j] > 16) { seen[j] = 1; queue.push(j); }
      }
    }
    count += queue.length;
    if (queue.length > largest.length) largest = queue;
  }
  requireValue(largest.length >= count * .95, 'mask_has_multiple_disconnected_parts');
  const result = Buffer.alloc(mask.length); for (const i of largest) result[i] = mask[i];
  return result;
}
async function partMask(bytes, alpha, width, height, bounds) {
  const decoded = await rgba(bytes, width, height);
  const mask = Buffer.alloc(width * height);
  const [x0, y0, x1, y1] = bounds;
  for (let y = 0; y < height; y++) for (let x = 0; x < width; x++) {
    const i = y * width + x;
    if (x / width >= x0 && x / width <= x1 && y / height >= y0 && y / height <= y1) mask[i] = Math.min(alpha[i], decoded.data[i * 4]);
  }
  return singleComponent(mask, width, height);
}

function bindAttachment(part, mask, width, height) {
  const x=part.attachment[0]*width, y=part.attachment[1]*height;
  let closest=-1, distance=Infinity;
  for(let i=0;i<mask.length;i++) if(mask[i]>128) {
    const d=Math.hypot(i%width-x,Math.floor(i/width)-y);
    if(d<distance) { closest=i; distance=d; }
  }
  requireValue(closest>=0 && distance/width<=.025,'attachment_not_on_mask');
  return {...part,proposed_attachment:part.attachment,attachment:[(closest%width)/width,Math.floor(closest/width)/height],attachment_snap_distance:distance/width};
}

async function maskOverlay(original, masks, parts) {
  const {data,info}=await rgba(original), w=info.width,h=info.height;
  const colors=[[255,90,40],[40,180,255],[220,50,220],[50,220,110],[240,210,20]];
  masks.forEach((mask,n)=>{
    for(let i=0;i<mask.length;i++) if(mask[i]>128) for(let k=0;k<3;k++) data[i*4+k]=Math.round(data[i*4+k]*.4+colors[n%colors.length][k]*.6);
    const cx=Math.round(parts[n].attachment[0]*w),cy=Math.round(parts[n].attachment[1]*h);
    for(let dy=-4;dy<=4;dy++) for(let dx=-4;dx<=4;dx++) {
      if(dx!==0&&dy!==0)continue;
      if(cx+dx<0||cx+dx>=w||cy+dy<0||cy+dy>=h)continue;
      const i=((cy+dy)*w+cx+dx)*4; data[i]=255;data[i+1]=255;data[i+2]=255;data[i+3]=255;
    }
  });
  return png(data,w,h);
}

/** A clipped regular triangle mesh retains interior vertices, thin silhouettes and holes.
 * Every occupied pixel belongs to a retained cell; texture alpha supplies the exact edge. */
function mesh(mask, depth, w, h, part) {
  const b = boundsOf(mask, w, h);
  const step = Math.max(4, Math.ceil(Math.max(w, h) / 52));
  const vertices = [], triangles = [], vertexMap = new Map(), edges = new Map();
  function vertex(x, y) {
    const key = `${x}:${y}`;
    if (vertexMap.has(key)) return vertexMap.get(key);
    let sample = Math.min(h - 1, y) * w + Math.min(w - 1, x);
    // Silhouette grid corners may be outside the character; never sample background depth there.
    if (mask[sample] <= 16) {
      let best = Infinity;
      for (let yy = Math.max(0,y-step); yy < Math.min(h,y+step+1); yy++) for (let xx = Math.max(0,x-step); xx < Math.min(w,x+step+1); xx++) {
        const distance=(xx-x)**2+(yy-y)**2;
        if (mask[yy*w+xx]>16 && distance<best) { best=distance; sample=yy*w+xx; }
      }
    }
    const z = ((depth[sample] / 255) - .5) * .10;
    const v = vertices.push([x / w, y / w, z, x / w, y / h, 0]) - 1; vertexMap.set(key, v); return v;
  }
  for (let y = Math.max(0, Math.floor(b.top / step) * step); y <= b.bottom; y += step) {
    for (let x = Math.max(0, Math.floor(b.left / step) * step); x <= b.right; x += step) {
      const x2 = Math.min(w, x + step), y2 = Math.min(h, y + step);
      let occupied = false;
      for (let yy = y; yy < y2 && !occupied; yy++) for (let xx = x; xx < x2; xx++) if (mask[yy * w + xx] > 8) { occupied = true; break; }
      if (!occupied) continue;
      const a = vertex(x, y), c = vertex(x2, y2), bb = vertex(x2, y), d = vertex(x, y2);
      triangles.push(a, bb, c, a, c, d);
      for (const [i, j] of [[a, bb], [bb, c], [c, d], [d, a], [a, c], [bb, d]]) {
        const lo = Math.min(i, j), hi = Math.max(i, j);
        edges.set(`${lo}:${hi}`, [lo, hi, Math.hypot(vertices[i][0] - vertices[j][0], vertices[i][1] - vertices[j][1])]);
      }
    }
  }
  const params = { rigid: [12, 150, .004], hair: [4, 24, .035], cord: [5, 35, .03], cloth: [7, 50, .02] }[part.material];
  let root = 0;
  if (part.parent_id != null) {
    const target = [part.attachment[0], part.attachment[1] * h / w];
    root = vertices.reduce((best, v, i) => Math.hypot(v[0] - target[0], v[1] - target[1]) < Math.hypot(vertices[best][0] - target[0], vertices[best][1] - target[1]) ? i : best, 0);
    requireValue(Math.hypot(vertices[root][0] - target[0], vertices[root][1] - target[1]) < .05, 'attachment_not_on_part');
  }
  if (part.material !== 'rigid') {
    // Geodesic distance keeps folded cords soft along their length, not through empty space.
    const distance = vertices.map(() => Infinity), adjacency = vertices.map(() => []);
    for (const [a, b, length] of edges.values()) { adjacency[a].push([b, length]); adjacency[b].push([a, length]); }
    const visited = new Set(); distance[root] = 0;
    for (let n = 0; n < vertices.length; n++) {
      let current = -1;
      for (let i = 0; i < distance.length; i++) if (!visited.has(i) && (current < 0 || distance[i] < distance[current])) current = i;
      if (!Number.isFinite(distance[current])) break;
      visited.add(current);
      for (const [next, length] of adjacency[current]) distance[next] = Math.min(distance[next], distance[current] + length);
    }
    requireValue(distance.every(Number.isFinite), 'disconnected_mesh');
    const maxDistance = Math.max(...distance), pinnedRadius = step / w * 1.5;
    vertices.forEach((v, i) => { v[5] = distance[i] <= pinnedRadius ? 0 : Math.min(1, (distance[i] - pinnedRadius) / Math.max(maxDistance - pinnedRadius, .001)); });
  }
  // Two-cell distances resist bending separately from the cell's stretch/shear edges.
  const bends = [];
  for (const [key, a] of vertexMap) {
    const [x, y] = key.split(':').map(Number);
    for (const [dx, dy] of [[step, 0], [0, step]]) {
      if (!vertexMap.has(`${x + dx}:${y + dy}`)) continue;
      const b = vertexMap.get(`${x + 2 * dx}:${y + 2 * dy}`);
      if (b != null) bends.push([a, b, Math.hypot(vertices[a][0] - vertices[b][0], vertices[a][1] - vertices[b][1])]);
    }
  }
  return { id: part.id, parent_id: part.parent_id, material: part.material, order: part.order, texture: `${part.id}_color`, depth: `${part.id}_depth`, damping: params[0], stiffness: params[1], max_displacement: params[2], vertices, triangles, edges: [...edges.values()], bends, pins: [], root };
}

async function writeAsset(directory, name, bytes) {
  await fs.writeFile(path.join(directory, `${name}.png`), bytes);
  return { file: `${name}.png`, sha256: sha256(bytes), size: bytes.length };
}

/** Only pixels in the swept area may be replaced by generated reconstruction. */
async function assemble({ original, restored, depthImage, parts, masks, directory }) {
  const { data, info } = await rgba(original), w = info.width, h = info.height;
  const numericDepth = (await rgba(depthImage)).data;
  for (let i=0;i<numericDepth.length;i+=4) requireValue(Math.max(numericDepth[i],numericDepth[i+1],numericDepth[i+2])-Math.min(numericDepth[i],numericDepth[i+1],numericDepth[i+2]) <= 2,'depth_must_be_numeric_grayscale');
  const depthRaw = await sharp(depthImage).resize(w, h).removeAlpha().greyscale().raw().toBuffer();
  const alpha = Buffer.alloc(w * h); for (let i = 0; i < alpha.length; i++) alpha[i] = data[i * 4 + 3];
  const union = Buffer.alloc(w * h);
  for (const mask of masks) for (let i = 0; i < union.length; i++) union[i] = Math.max(union[i], mask[i]);
  // Dilate by the permitted maximum movement, including antialiasing guard pixels.
  const swept = union; // Outside the removed pieces the original surface is already known.
  const clean = (await rgba(restored, w, h)).data, body = Buffer.from(data);
  for (let i = 0; i < swept.length; i++) if (swept[i] > 8) clean.copy(body, i * 4, i * 4, i * 4 + 4);
  const bodyMask = Buffer.alloc(alpha.length); for (let i = 0; i < alpha.length; i++) bodyMask[i] = body[i * 4 + 3];
  const bodyPart = { id: 'body', parent_id: null, material: 'rigid', order: 0 };
  const layers = [mesh(bodyMask, depthRaw, w, h, bodyPart)];
  const assets = {};
  assets.body_color = await writeAsset(directory, 'body_color', await png(body, w, h));
  assets.body_depth = await writeAsset(directory, 'body_depth', await png(depthRaw, w, h, 1));
  for (let n = 0; n < parts.length; n++) {
    const part = parts[n], mask = masks[n], color = Buffer.from(data);
    for (let i = 0; i < mask.length; i++) color[i * 4 + 3] = mask[i];
    const layer = mesh(mask, depthRaw, w, h, part);
    const parent = layers.find(l => l.id === part.parent_id);
    requireValue(parent, 'missing_parent_layer');
    if (part.material === 'rigid') layer.max_displacement = parent.max_displacement;
    for (let i = 0; i < layer.vertices.length; i++) {
      const v = layer.vertices[i]; if (v[5] !== 0) continue;
      if (part.material === 'rigid' && i !== layer.root) continue;
      const index = parent.vertices.reduce((best, p, j) => Math.hypot(v[0] - p[0], v[1] - p[1]) < Math.hypot(v[0] - parent.vertices[best][0], v[1] - parent.vertices[best][1]) ? j : best, 0);
      requireValue(Math.hypot(v[0] - parent.vertices[index][0], v[1] - parent.vertices[index][1]) < .06, 'attachment_not_on_parent');
      layer.pins.push([i, index]);
    }
    layers.push(layer);
    assets[layer.texture] = await writeAsset(directory, layer.texture, await png(color, w, h));
    assets[layer.depth] = await writeAsset(directory, layer.depth, await png(depthRaw, w, h, 1));
  }
  return { width: w, height: h, layers, assets };
}

/** Software triangle rasterizer for QA: uses package topology, alpha, depth and actual vertices. */
async function renderPack(pack, directory, { tiltX = 0, tiltY = 0, secondary = 0 } = {}) {
  const w = pack.width, h = pack.height, out = Buffer.alloc(w * h * 4);
  for (const layer of [...pack.layers].sort((a, b) => a.order - b.order)) {
    const texture = (await rgba(await fs.readFile(path.join(directory, pack.assets[layer.texture].file)), w, h)).data;
    const vertices = layer.vertices.map(v => {
      const x = v[0] - .5 + secondary * v[5] * layer.max_displacement, y = v[1] - h / w / 2, z = v[2];
      const xx = x * Math.cos(tiltX) + z * Math.sin(tiltX), zz = -x * Math.sin(tiltX) + z * Math.cos(tiltX);
      return [(xx + .5 + tiltX * .025) * w, (y * Math.cos(tiltY) - zz * Math.sin(tiltY) + h / w / 2 + tiltY * .025) * w, v[3], v[4]];
    });
    for (let t = 0; t < layer.triangles.length; t += 3) {
      const [a, b, c] = layer.triangles.slice(t, t + 3).map(i => vertices[i]);
      const denominator = (b[1] - c[1]) * (a[0] - c[0]) + (c[0] - b[0]) * (a[1] - c[1]);
      requireValue(denominator > .00001, 'flipped_or_degenerate_render_triangle');
      for (let y = Math.max(0, Math.floor(Math.min(a[1], b[1], c[1]))); y <= Math.min(h - 1, Math.ceil(Math.max(a[1], b[1], c[1]))); y++) {
        for (let x = Math.max(0, Math.floor(Math.min(a[0], b[0], c[0]))); x <= Math.min(w - 1, Math.ceil(Math.max(a[0], b[0], c[0]))); x++) {
          const aa = ((b[1] - c[1]) * (x + .5 - c[0]) + (c[0] - b[0]) * (y + .5 - c[1])) / denominator;
          const bb = ((c[1] - a[1]) * (x + .5 - c[0]) + (a[0] - c[0]) * (y + .5 - c[1])) / denominator, cc = 1 - aa - bb;
          if (Math.min(aa, bb, cc) < 0) continue;
          const u = Math.min(w - 1, Math.max(0, Math.floor((aa * a[2] + bb * b[2] + cc * c[2]) * w)));
          const v = Math.min(h - 1, Math.max(0, Math.floor((aa * a[3] + bb * b[3] + cc * c[3]) * h)));
          const src = (v * w + u) * 4, dst = (y * w + x) * 4, alpha = texture[src + 3] / 255, existing = out[dst + 3] / 255, combined = alpha + existing * (1 - alpha);
          if (combined === 0) continue;
          for (let k = 0; k < 3; k++) out[dst + k] = Math.round((texture[src + k] * alpha + out[dst + k] * existing * (1 - alpha)) / combined);
          out[dst + 3] = Math.round(combined * 255);
        }
      }
    }
  }
  return png(out, w, h);
}
async function neutralError(reference, rendered) {
  const a = await rgba(reference), b = await rgba(rendered, a.info.width, a.info.height);
  let error = 0, pixels = 0;
  for (let i = 0; i < a.data.length; i += 4) {
    if (Math.max(a.data[i + 3], b.data[i + 3]) <= 8) continue;
    pixels++;
    for (let k = 0; k < 3; k++) error += Math.abs(a.data[i + k] * a.data[i + 3] / 255 - b.data[i + k] * b.data[i + 3] / 255) / 255;
    error += Math.abs(a.data[i + 3] - b.data[i + 3]) / 255;
  }
  return pixels ? error / (pixels * 4) : 1;
}
module.exports = { rgba, png, boundsOf, partMask, bindAttachment, maskOverlay, mesh, assemble, renderPack, neutralError, writeAsset };
