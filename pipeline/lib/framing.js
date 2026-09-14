const { spawnSync } = require("node:child_process");
const fs = require("node:fs");

const DEFAULT_GREEN = Object.freeze({ r: 0, g: 255, b: 0 });

function run(command, args) {
  const result = spawnSync(command, args, { encoding: "utf8", maxBuffer: 20 * 1024 * 1024 });
  if (result.status !== 0) throw new Error(`${command}: ${result.stderr || result.stdout}`);
  return result;
}

function imageSize(file) {
  const result = run("ffprobe", ["-v", "error", "-select_streams", "v:0", "-show_entries", "stream=width,height", "-of", "json", file]);
  const stream = JSON.parse(result.stdout).streams?.[0];
  if (!stream?.width || !stream?.height) throw new Error(`Cannot determine image dimensions: ${file}`);
  return { width: Number(stream.width), height: Number(stream.height) };
}

function imageRgb(file, { seekSeconds } = {}) {
  const { width, height } = imageSize(file);
  const args = ["-v", "error"];
  if (seekSeconds !== undefined) args.push("-ss", String(seekSeconds));
  args.push("-i", file, "-frames:v", "1", "-vf", "format=rgb24", "-f", "rawvideo", "-");
  const result = spawnSync("ffmpeg", args, { encoding: null, maxBuffer: Math.max(20 * 1024 * 1024, width * height * 4) });
  if (result.status !== 0 || !result.stdout?.length) throw new Error(`ffmpeg frame extraction failed: ${result.stderr?.toString() || ""}`);
  return { width, height, rgb: result.stdout };
}

function isGreen(r, g, b, tolerance = 70) {
  const distance = Math.hypot(r - DEFAULT_GREEN.r, g - DEFAULT_GREEN.g, b - DEFAULT_GREEN.b);
  return distance <= tolerance && g >= r + 25 && g >= b + 25;
}

function connectedComponents({ width, height, rgb }, { greenTolerance = 70, minPixels = 64 } = {}) {
  const pixels = width * height;
  const subject = new Uint8Array(pixels);
  for (let index = 0; index < pixels; index += 1) {
    const offset = index * 3;
    subject[index] = isGreen(rgb[offset], rgb[offset + 1], rgb[offset + 2], greenTolerance) ? 0 : 1;
  }
  const visited = new Uint8Array(pixels);
  const components = [];
  const queue = new Int32Array(pixels);
  for (let start = 0; start < pixels; start += 1) {
    if (!subject[start] || visited[start]) continue;
    let head = 0; let tail = 0; let count = 0;
    let left = width; let top = height; let right = 0; let bottom = 0;
    queue[tail++] = start; visited[start] = 1;
    while (head < tail) {
      const index = queue[head++]; const x = index % width; const y = Math.floor(index / width);
      count += 1; left = Math.min(left, x); right = Math.max(right, x); top = Math.min(top, y); bottom = Math.max(bottom, y);
      const neighbors = [index - 1, index + 1, index - width, index + width];
      for (const neighbor of neighbors) {
        if (neighbor < 0 || neighbor >= pixels || visited[neighbor] || !subject[neighbor]) continue;
        if ((neighbor === index - 1 && x === 0) || (neighbor === index + 1 && x === width - 1)) continue;
        visited[neighbor] = 1; queue[tail++] = neighbor;
      }
    }
    if (count >= minPixels) components.push({ left, top, right, bottom, pixels: count });
  }
  return components;
}

function unionBox(components, width, height) {
  if (!components.length) throw new Error("No significant non-chroma components found");
  const box = components.reduce((result, component) => ({
    left: Math.min(result.left, component.left), top: Math.min(result.top, component.top),
    right: Math.max(result.right, component.right), bottom: Math.max(result.bottom, component.bottom),
  }), { left: width, top: height, right: 0, bottom: 0 });
  const pixelWidth = box.right - box.left + 1; const pixelHeight = box.bottom - box.top + 1;
  return {
    ...box, pixel_width: pixelWidth, pixel_height: pixelHeight,
    width_ratio: pixelWidth / width, height_ratio: pixelHeight / height,
    margins: { left: box.left / width, top: box.top / height, right: (width - 1 - box.right) / width, bottom: (height - 1 - box.bottom) / height },
  };
}

function measureFraming(file, options = {}) {
  const frame = imageRgb(file, options);
  const components = connectedComponents(frame, options);
  return { width: frame.width, height: frame.height, component_count: components.length, bbox: unionBox(components, frame.width, frame.height) };
}

function assertCanonicalFraming(measurement, { minHeight = 0, maxHeight = 0.70, minMargin = 0.15 } = {}) {
  const { bbox } = measurement;
  const minObservedMargin = Math.min(...Object.values(bbox.margins));
  return {
    pass: bbox.height_ratio >= minHeight && bbox.height_ratio <= maxHeight && minObservedMargin >= minMargin,
    min_height: minHeight, max_height: maxHeight, min_margin: minMargin,
    observed_height: bbox.height_ratio, observed_min_margin: minObservedMargin,
  };
}

function addSafeChromaFrame(sourceFile, outputFile, { targetFill = 0.68, edgePaddingPixels = 6 } = {}) {
  if (!(targetFill > 0 && targetFill <= 0.70)) throw new Error("targetFill must be in (0, 0.70]");
  const measurement = measureFraming(sourceFile);
  const { width, height, bbox } = measurement;
  const cropLeft = Math.max(0, bbox.left - edgePaddingPixels);
  const cropTop = Math.max(0, bbox.top - edgePaddingPixels);
  const cropRight = Math.min(width - 1, bbox.right + edgePaddingPixels);
  const cropBottom = Math.min(height - 1, bbox.bottom + edgePaddingPixels);
  const cropWidth = cropRight - cropLeft + 1;
  const cropHeight = cropBottom - cropTop + 1;
  const scale = Math.min((width * targetFill) / cropWidth, (height * targetFill) / cropHeight);
  const scaledWidth = Math.max(1, Math.round(cropWidth * scale));
  const scaledHeight = Math.max(1, Math.round(cropHeight * scale));
  run("ffmpeg", [
    "-y", "-i", sourceFile, "-frames:v", "1",
    "-vf", `crop=${cropWidth}:${cropHeight}:${cropLeft}:${cropTop},scale=${scaledWidth}:${scaledHeight},pad=${width}:${height}:(ow-iw)/2:(oh-ih)/2:color=0x00ff00`,
    outputFile,
  ]);
  const outputMeasurement = measureFraming(outputFile);
  return {
    contract: "pet_generation_v2.safe_chroma_frame.v1",
    source: sourceFile, output: outputFile, target_fill: targetFill, edge_padding_pixels: edgePaddingPixels,
    source_measurement: measurement, output_measurement: outputMeasurement,
    validation: assertCanonicalFraming(outputMeasurement),
  };
}

function splitTurntableSheet(sourceFile, outputDir, { minInternalSeamMargin = 0.03 } = {}) {
  const { width, height } = imageSize(sourceFile);
  if (width % 2 || height % 2) throw new Error("Turntable sheet dimensions must be even");
  const panelWidth = width / 2;
  const panelHeight = height / 2;
  const panels = [
    { id: "front", x: 0, y: 0, seams: ["right", "bottom"] },
    { id: "side", x: panelWidth, y: 0, seams: ["left", "bottom"] },
    { id: "back", x: 0, y: panelHeight, seams: ["right", "top"] },
    { id: "top", x: panelWidth, y: panelHeight, seams: ["left", "top"] },
  ].map((panel, index) => {
    const output = `${outputDir}/panel_${String(index).padStart(2, "0")}_${panel.id}.png`;
    run("ffmpeg", ["-y", "-i", sourceFile, "-frames:v", "1", "-vf", `crop=${panelWidth}:${panelHeight}:${panel.x}:${panel.y}`, output]);
    const measurement = measureFraming(output);
    const internalSeamMargins = Object.fromEntries(panel.seams.map((side) => [side, measurement.bbox.margins[side]]));
    return {
      ...panel, output, measurement, internal_seam_margins: internalSeamMargins,
      pass: measurement.component_count === 1 && Object.values(internalSeamMargins).every((margin) => margin >= minInternalSeamMargin),
    };
  });
  return { contract: "pet_generation_v2.turntable_panel_qa.v1", source: sourceFile, panel_size: { width: panelWidth, height: panelHeight }, min_internal_seam_margin: minInternalSeamMargin, panels, pass: panels.every((panel) => panel.pass) };
}

function addSharedSafeChromaFrames(panels, outputDir, { targetFill = 0.68, outputSize = 1024 } = {}) {
  const measurements = panels.map((panel) => measureFraming(panel.output));
  const maxWidth = Math.max(...measurements.map((measurement) => measurement.bbox.pixel_width));
  const maxHeight = Math.max(...measurements.map((measurement) => measurement.bbox.pixel_height));
  return panels.map((panel, index) => {
    const box = measurements[index].bbox;
    const pad = 6;
    const cropWidth = box.pixel_width + pad * 2; const cropHeight = box.pixel_height + pad * 2;
    const scale = Math.min((outputSize * targetFill) / maxWidth, (outputSize * targetFill) / maxHeight);
    const output = `${outputDir}/anchor_${String(index).padStart(2, "0")}.png`;
    run("ffmpeg", ["-y", "-i", panel.output, "-frames:v", "1", "-vf", `crop=${cropWidth}:${cropHeight}:${Math.max(0, box.left - pad)}:${Math.max(0, box.top - pad)},scale=${Math.round(cropWidth * scale)}:${Math.round(cropHeight * scale)}:flags=bicubic,pad=${outputSize}:${outputSize}:(ow-iw)/2:(oh-ih)/2:color=0x00ff00`, output]);
    return { view: panel.id, output, validation: assertCanonicalFraming(measureFraming(output)) };
  });
}

function videoFramingQa(videoPath, {
  minMargin = 0.10,
  hardMinMargin = 0.015,
  maxEndpointScaleDrift = 0.03,
  sampleFps = 12,
  analysisSize = 240,
} = {}) {
  const durationResult = run("ffprobe", ["-v", "error", "-show_entries", "format=duration", "-of", "default=noprint_wrappers=1:nokey=1", videoPath]);
  const duration = Number(durationResult.stdout.trim());
  const width = analysisSize;
  const height = analysisSize;
  const result = spawnSync("ffmpeg", ["-v", "error", "-i", videoPath, "-vf", `fps=${sampleFps},scale=${width}:${height}:flags=area,format=rgb24`, "-f", "rawvideo", "-"], { encoding: null, maxBuffer: Math.max(20 * 1024 * 1024, width * height * 3 * Math.ceil(duration * sampleFps + 2)) });
  if (result.status !== 0 || !result.stdout?.length) throw new Error(`ffmpeg full-frame extraction failed: ${result.stderr?.toString() || ""}`);
  const bytesPerFrame = width * height * 3;
  const frameCount = Math.floor(result.stdout.length / bytesPerFrame);
  const frames = Array.from({ length: frameCount }, (_, index) => {
    const rgb = result.stdout.subarray(index * bytesPerFrame, (index + 1) * bytesPerFrame);
    // Video compression shifts the matte luminance much more than still PNGs.
    // Use the same wider chroma tolerance as the Android player/QA so a codec
    // halo is not mistaken for a full-frame foreground component.
    const components = connectedComponents(
      { width, height, rgb },
      { greenTolerance: 105, minPixels: Math.max(32, Math.floor(width * height / 2000)) },
    );
    // The authored contract requires one connected body. Judge its largest
    // component for framing; isolated codec noise at a canvas border must not
    // turn a healthy clip into a false crop failure.
    const primary = components.reduce((largest, component) => (
      !largest || component.pixels > largest.pixels ? component : largest
    ), null);
    return {
      index,
      seek_seconds: index / sampleFps,
      component_count: components.length,
      bbox: unionBox(primary ? [primary] : components, width, height),
    };
  });
  if (frameCount < 2) throw new Error("Video yielded fewer than two frames for framing QA");
  const first = frames[0].bbox; const last = frames.at(-1).bbox;
  const scaleLastMinusFirst = Math.abs(last.height_ratio - first.height_ratio);
  const observedMinMargin = Math.min(...frames.flatMap((frame) => Object.values(frame.bbox.margins)));
  return {
    contract: "pet_generation_v2.framing_qa.v1", duration_seconds: duration, sampled_frames: frames,
    thresholds: { min_margin: minMargin, hard_min_margin: hardMinMargin, max_endpoint_scale_drift: maxEndpointScaleDrift },
    observed: { min_margin: observedMinMargin, endpoint_scale_drift: scaleLastMinusFirst },
    hard_pass: observedMinMargin >= hardMinMargin,
    framing_pass: observedMinMargin >= minMargin && scaleLastMinusFirst <= maxEndpointScaleDrift,
  };
}

module.exports = { measureFraming, assertCanonicalFraming, addSafeChromaFrame, splitTurntableSheet, addSharedSafeChromaFrames, videoFramingQa };
