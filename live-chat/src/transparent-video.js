// Remove only the dark region connected to the frame boundary. Interior dark
// details (eyes, armour, outlines) remain opaque; no model call is involved.
export function createTransparentVideo(video, image) {
  const canvas = document.createElement('canvas');
  canvas.id = 'heroCanvas'; canvas.hidden = true;
  canvas.setAttribute('role', 'img'); canvas.setAttribute('aria-label', 'Анимация маскота без фона');
  video.after(canvas);
  const context = canvas.getContext('2d', { willReadFrequently: true });
  const toggle = document.createElement('button');
  toggle.className = 'playback-toggle'; toggle.hidden = true;
  toggle.type = 'button'; toggle.textContent = 'Пауза';
  canvas.after(toggle);
  let mask, queue, callback, firstPaint, failed = false, sourceVersion = 0;
  video.crossOrigin = 'anonymous'; video.loop = true; video.controls = false;
  video.classList.add('keyed-source');
  const syncToggle = () => { toggle.textContent = video.paused ? 'Продолжить' : 'Пауза'; };
  toggle.onclick = () => { if (video.paused) video.play().catch(() => {}); else video.pause(); };
  video.addEventListener('play', syncToggle); video.addEventListener('pause', syncToggle);

  function draw() {
    if (failed || !video.videoWidth || video.readyState < 2) return;
    const width = video.videoWidth, height = video.videoHeight, count = width * height;
    if (canvas.width !== width || canvas.height !== height) {
      canvas.width = width; canvas.height = height;
      mask = new Uint8Array(count); queue = new Uint32Array(count);
    }
    try {
      context.drawImage(video, 0, 0, width, height);
      const frame = context.getImageData(0, 0, width, height), pixels = frame.data;
      mask.fill(0); let head = 0, tail = 0;
      const add = index => {
        if (mask[index]) return;
        const p = index * 4;
        const hi = Math.max(pixels[p], pixels[p + 1], pixels[p + 2]);
        const lo = Math.min(pixels[p], pixels[p + 1], pixels[p + 2]);
        // Dark red armour and boots can touch the background. Only key near-black,
        // nearly neutral pixels; do not flood through coloured shadow details.
        if (hi > 22 || hi - lo > Math.max(5, hi * 0.25)) return;
        mask[index] = 1; queue[tail++] = index;
      };
      for (let x = 0; x < width; x++) { add(x); add(count - width + x); }
      for (let y = 1; y < height - 1; y++) { add(y * width); add(y * width + width - 1); }
      while (head < tail) {
        const index = queue[head++], x = index % width;
        if (x > 0) add(index - 1); if (x + 1 < width) add(index + 1);
        if (index >= width) add(index - width); if (index + width < count) add(index + width);
      }
      // A gap enclosed by an arm can be disconnected from the outside.
      // Recognise only sizeable, nearly neutral black islands; leave small dark
      // details such as eyes and seams alone.
      const islandAdd = index => {
        if (mask[index]) return;
        const p = index * 4;
        const hi = Math.max(pixels[p], pixels[p + 1], pixels[p + 2]);
        const lo = Math.min(pixels[p], pixels[p + 1], pixels[p + 2]);
        if (hi > 18 || hi - lo > 4) return;
        mask[index] = 2; queue[tail++] = index;
      };
      let removed = tail;
      for (let start = 0; start < count; start++) {
        if (mask[start]) continue;
        head = tail = 0; islandAdd(start);
        while (head < tail) {
          const index = queue[head++], x = index % width;
          if (x > 0) islandAdd(index - 1); if (x + 1 < width) islandAdd(index + 1);
          if (index >= width) islandAdd(index - width); if (index + width < count) islandAdd(index + width);
        }
        if (tail >= Math.max(100, count * 0.0004)) {
          for (let i = 0; i < tail; i++) mask[queue[i]] = 1;
          removed += tail;
        }
      }
      for (let index = 0; index < count; index++) {
        const p = index * 4;
        if (mask[index] === 1) { pixels[p + 3] = 0; continue; }
        const x = index % width;
        const edge = (x > 0 && mask[index - 1] === 1) || (x + 1 < width && mask[index + 1] === 1) || (index >= width && mask[index - width] === 1) || (index + width < count && mask[index + width] === 1);
        if (edge) {
          const hi = Math.max(pixels[p], pixels[p + 1], pixels[p + 2]);
          const lo = Math.min(pixels[p], pixels[p + 1], pixels[p + 2]);
          // Preserve coloured edge pixels; only soften neutral black fringes.
          const alpha = Math.min(1, Math.max(hi / 56, (hi - lo) / 10));
          pixels[p + 3] = Math.round(alpha * 255);
          for (let channel = 0; channel < 3; channel++) pixels[p + channel] = Math.min(255, Math.round(pixels[p + channel] / alpha));
        }
      }
      context.putImageData(frame, 0, 0);
      canvas.hidden = false; image.hidden = true; toggle.hidden = false;
      canvas.dataset.backgroundRemoved = 'true';
      canvas.dataset.transparentPixels = String(removed);
      canvas.dataset.frames = String(Number(canvas.dataset.frames || 0) + 1);
    } catch {
      // Keep the playable original if a CDN ever stops allowing canvas access.
      failed = true; canvas.hidden = true; video.classList.remove('keyed-source');
      video.controls = true; toggle.hidden = true;
    }
    if (firstPaint) {
      const notify = firstPaint, version = sourceVersion; firstPaint = null;
      requestAnimationFrame(() => { if (version === sourceVersion) notify(); });
    }
  }
  function nextFrame() { draw(); callback = video.requestVideoFrameCallback(nextFrame); }
  return {
    oncePainted(fn) { firstPaint = fn; },
    start() {
      video.cancelVideoFrameCallback(callback); callback = video.requestVideoFrameCallback(nextFrame);
    },
    reset() {
      sourceVersion++; firstPaint = null; failed = false;
      video.cancelVideoFrameCallback(callback); canvas.hidden = true; toggle.hidden = true;
      video.classList.add('keyed-source'); video.controls = false;
    },
  };
}
