// The six approved chat transitions: 01, 03, 07, 08, 09, 10.
// All pixels are transparent outside the figure and translucent colour layers.
export const CHAT_GLITCH_EFFECTS = Object.freeze(['slices', 'pixels', 'packets', 'dropout', 'columns', 'echo']);
export const CHAT_GLITCH_DURATION = 420;
let nextEffect = 0;
export function nextChatGlitch() { return CHAT_GLITCH_EFFECTS[nextEffect++ % CHAT_GLITCH_EFFECTS.length]; }

export function createGlitchRenderer(palette = {}) {
const unit = 256, strength = .65, warm = palette.warm || '#ffad7a', cool = palette.cool || '#aaa0ff';
function surface(size = unit) {
  const canvas = document.createElement('canvas');
  canvas.width = canvas.height = size;
  return {canvas, ctx:canvas.getContext('2d', {willReadFrequently:true})};
}
const transitionFrames = [surface(), surface()];
const images = [surface(), surface()];
const output = surface(), pixelated = surface(), tinted = surface(), noise = surface(64);
const visited = new Uint8Array(unit * unit), queue = new Int32Array(unit * unit);
// Remove only edge-connected neutral background; light details on the hero stay.
function prepare(source, target) {
  const ctx = target.ctx;
  ctx.clearRect(0, 0, unit, unit);
  ctx.drawImage(source, 0, 0, unit, unit);
  const image = ctx.getImageData(0, 0, unit, unit), pixels = image.data;
  const corners = [0, (unit-1)*4, (unit*(unit-1))*4, (unit*unit-1)*4];
  const key = [0,1,2].map(channel => corners.reduce((sum, p) => sum+pixels[p+channel], 0)/4);
  visited.fill(0);
  let read = 0, write = 0;
  function add(index) {
    if (visited[index]) return;
    visited[index] = 1;
    const p = index * 4, r = pixels[p], g = pixels[p+1], b = pixels[p+2];
    const distance = Math.max(Math.abs(r-key[0]), Math.abs(g-key[1]), Math.abs(b-key[2]));
    if (pixels[p+3] && distance > 34) return;
    queue[write++] = index;
    pixels[p+3] = distance < 12 ? 0 : Math.round(pixels[p+3] * (distance-12) / 22);
  }
  for (let x=0;x<unit;x++) { add(x); add((unit-1)*unit+x); }
  for (let y=1;y<unit-1;y++) { add(y*unit); add(y*unit+unit-1); }
  while (read<write) {
    const i=queue[read++], x=i%unit;
    if(x) add(i-1); if(x+1<unit) add(i+1);
    if(i>=unit) add(i-unit); if(i<unit*(unit-1)) add(i+unit);
  }
  ctx.putImageData(image,0,0);
}
function random(seed) {
  const value = Math.sin(seed * 127.1 + 311.7) * 43758.5453;
  return value - Math.floor(value);
}
function clamp(value, lo = 0, hi = 1) { return Math.max(lo, Math.min(hi, value)); }
function smooth(value) { const x = clamp(value); return x * x * (3 - 2 * x); }
function envelope(progress) { return Math.sin(Math.PI * progress); }
function occlusion(progress) {
  return smooth((progress - .29) / .15) * smooth((.71 - progress) / .15);
}
function draw(ctx, image, alpha = 1, dx = 0, dy = 0) {
  ctx.globalAlpha = clamp(alpha);
  ctx.drawImage(image, dx, dy, unit, unit);
  ctx.globalAlpha = 1;
}
function tint(image, color) {
  const ctx = tinted.ctx;
  ctx.clearRect(0, 0, unit, unit);
  ctx.globalCompositeOperation = 'source-over';
  ctx.drawImage(image, 0, 0);
  ctx.globalCompositeOperation = 'source-in';
  ctx.fillStyle = color;
  ctx.fillRect(0, 0, unit, unit);
  ctx.globalCompositeOperation = 'source-over';
  return tinted.canvas;
}
function scanlines(ctx, alpha, spacing = 6) {
  ctx.fillStyle = cool;
  ctx.globalAlpha = alpha;
  for (let y = 0; y < unit; y += spacing) ctx.fillRect(0, y, unit, 1);
  ctx.globalAlpha = 1;
}
function imageStrip(ctx, image, x, y, width, height, dx, dy) {
  if (width <= 0 || height <= 0) return;
  ctx.drawImage(image, x, y, width, height, x + dx, y + dy, width, height);
}
function renderEffect(ctx, id, outgoing, incoming, progress) {
  const t = clamp(progress);
  const wave = envelope(t);
  const cover = occlusion(t);
  const power = .25 + strength * .75;
  const tick = Math.floor(t * 8);
  // Both poses dissolve into transparency at the swap, so the cut never
  // exposes two misaligned faces. Coloured fragments stay translucent.
  if (cover > 0) {
    [outgoing, incoming] = [outgoing, incoming].map((frame, index) => {
      const target = transitionFrames[index];
      target.ctx.clearRect(0, 0, unit, unit);
      target.ctx.globalAlpha = 1 - cover;
      target.ctx.drawImage(frame, 0, 0);
      target.ctx.globalAlpha = 1;
      return target.canvas;
    });
  }
  const image = t < .5 ? outgoing : incoming;
  draw(ctx, image);

  switch (id) {
    case 'slices': {
      const step = 14;
      for (let y = 0; y < unit; y += step) {
        const offset = Math.round((random(y + tick * 17) - .5) * 82 * wave * power);
        ctx.clearRect(0, y, unit, step);
        imageStrip(ctx, image, 0, y, unit, Math.min(step, unit - y), offset, 0);
        ctx.globalAlpha = cover * .2;
        ctx.fillStyle = y % 28 === 0 ? warm : cool;
        ctx.fillRect(0, y, unit, step);
        ctx.globalAlpha = wave * .58 * power;
        ctx.fillStyle = y % 28 === 0 ? warm : cool;
        ctx.fillRect(12 + random(y + tick) * 140, y, 25 + random(y) * 50, 2);
      }
      ctx.globalAlpha = 1;
      break;
    }
    case 'pixels': {
      const block = Math.round(2 + wave * (20 + 14 * power));
      const resolution = Math.max(6, Math.round(unit / block));
      pixelated.ctx.clearRect(0, 0, unit, unit);
      pixelated.ctx.drawImage(image, 0, 0, resolution, resolution);
      ctx.clearRect(0, 0, unit, unit);
      ctx.imageSmoothingEnabled = false;
      ctx.drawImage(pixelated.canvas, 0, 0, resolution, resolution, 0, 0, unit, unit);
      ctx.imageSmoothingEnabled = true;
      for (let y = 0; y < unit; y += 20) for (let x = 0; x < unit; x += 20) {
        const n = random(x + y * 2 + tick * 11);
        if (n < .48 * wave) {
          ctx.globalAlpha = wave * .38;
          ctx.fillStyle = n < .24 ? warm : cool;
          ctx.fillRect(x, y, 18, 18);
        }
      }
      ctx.globalAlpha = 1;
      break;
    }
    case 'packets': {
      ctx.clearRect(0, 0, unit, unit);
      const size = 32;
      for (let y = 0; y < unit; y += size) for (let x = 0; x < unit; x += size) {
        const n = random(x * 3 + y + tick * 13);
        const offsetX = Math.round((n - .5) * 70 * wave * power);
        const offsetY = Math.round((random(x + y * 3) - .5) * 38 * wave * power);
        ctx.globalAlpha = 1;
        imageStrip(ctx, image, x, y, size, size, offsetX, offsetY);
        ctx.globalAlpha = wave * (.08 + n * .3);
        ctx.fillStyle = n > .5 ? warm : cool;
        ctx.fillRect(x + 2, y + 2, size - 4, size - 4);
        if (n > .58) {
          ctx.fillStyle = n > .5 ? cool : warm;
          ctx.fillRect(x + 6, y + 7, 13, 2);
        }
      }
      ctx.globalAlpha = 1;
      break;
    }
    case 'dropout': {
      const noiseData = noise.ctx.createImageData(64, 64);
      for (let i = 0; i < noiseData.data.length; i += 4) {
        const value = random(i + tick * 132);
        noiseData.data[i] = value > .5 ? 255 : 170;
        noiseData.data[i + 1] = value > .5 ? 173 : 160;
        noiseData.data[i + 2] = value > .5 ? 122 : 255;
        noiseData.data[i + 3] = value < .35 ? 0 : 90;
      }
      noise.ctx.putImageData(noiseData, 0, 0);
      ctx.globalAlpha = wave * power;
      ctx.imageSmoothingEnabled = false;
      ctx.drawImage(noise.canvas, 0, 0, unit, unit);
      ctx.imageSmoothingEnabled = true;
      ctx.globalAlpha = 1;
      scanlines(ctx, wave * .18, 4);
      ctx.fillStyle = warm;
      ctx.globalAlpha = wave * .6;
      ctx.fillRect(40 + random(tick + 5) * 80, 76 + random(tick) * 90, 74, 3);
      ctx.globalAlpha = 1;
      break;
    }
    case 'columns': {
      ctx.clearRect(0, 0, unit, unit);
      const width = 18;
      for (let x = 0; x < unit; x += width) {
        const h = Math.min(width, unit - x);
        const direction = Math.floor(x / width) % 2 ? 1 : -1;
        const shift = Math.round(direction * wave * power * (20 + random(x) * 55));
        imageStrip(ctx, image, x, 0, h, unit, 0, shift);
        ctx.globalAlpha = cover * .22;
        ctx.fillStyle = direction > 0 ? warm : cool;
        ctx.fillRect(x, 0, h, unit);
        ctx.globalAlpha = wave * .5;
        ctx.fillStyle = direction > 0 ? cool : warm;
        ctx.fillRect(x, 32 + random(x + tick) * 158, 1, 34);
      }
      ctx.globalAlpha = 1;
      break;
    }
    case 'echo': {
      const offset = wave * (16 + 13 * power);
      ctx.clearRect(0, 0, unit, unit);
      draw(ctx, tint(image, warm), .45 * wave, offset, offset * .35);
      draw(ctx, tint(image, cool), .45 * wave, -offset, -offset * .35);
      draw(ctx, image, 1 - wave * .45);
      ctx.globalAlpha = wave * .5;
      ctx.lineWidth = 1;
      for (let i = 0; i < 3; i++) {
        ctx.strokeStyle = i % 2 ? warm : cool;
        const distance = (i + 1) * 7 * wave * power;
        ctx.strokeRect(66 - distance, 30 - distance, 124 + distance * 2, 196 + distance * 2);
      }
      ctx.globalAlpha = 1;
      break;
    }
  }
}


return {
  capturePrevious(source) { prepare(source, images[0]); },
  render(context, source, effect, progress, width, height) {
    prepare(source, images[1]);
    output.ctx.clearRect(0, 0, unit, unit);
    renderEffect(output.ctx, effect, images[0].canvas, images[1].canvas, progress);
    context.clearRect(0, 0, width, height);
    context.drawImage(output.canvas, 0, 0, width, height);
  },
};
}

// Captures the actual displayed frame before drawing the next decoded source.
// Waiting for the incoming frame never consumes the transition or shows black.
export function createPortraitTransition(canvas, context, {duration=CHAT_GLITCH_DURATION, onTransition=()=>{}, effect:fixedEffect, palette, enabled=()=>true}={}) {
  const renderer = createGlitchRenderer(palette);
  let pending=false, started=null, effect=null;
  const reduced = globalThis.matchMedia?.('(prefers-reduced-motion: reduce)');
  return {
    begin() { pending=true; },
    cancel() { pending=false; started=null; },
    render(source, now, draw) {
      if (!enabled()) {
        pending = false;
        started = null;
        draw(source);
        return;
      }
      if (pending) {
        renderer.capturePrevious(canvas);
        pending=false;
        started=now;
        effect=CHAT_GLITCH_EFFECTS.includes(fixedEffect) ? fixedEffect : nextChatGlitch();
        onTransition(effect);
      }
      draw(source);
      if (started===null) return;
      const progress=Math.max(0, Math.min(1, (now-started)/duration));
      if (progress>=1 || reduced?.matches) { started=null; return; }
      renderer.render(context, canvas, effect, progress, canvas.width, canvas.height);
    },
  };
}
