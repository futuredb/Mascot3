const fs = require("node:fs/promises");
const os = require("node:os");
const path = require("node:path");
const { spawnSync } = require("node:child_process");

const FFMPEG_TIMEOUT_MS = 180_000;

function run(command, args, cwd, timeoutMs = FFMPEG_TIMEOUT_MS) {
  const result = spawnSync(command, ["-loglevel", "error", ...args], {
    cwd, encoding: "utf8", maxBuffer: 20 * 1024 * 1024, timeout: timeoutMs, killSignal: "SIGKILL",
  });
  if (result.error?.code === "ETIMEDOUT") throw new Error(`${command} timed out after ${timeoutMs}ms and was terminated`);
  if (result.status !== 0) throw new Error(`${command}: ${result.stderr || result.stdout || result.error?.message || "failed"}`);
}
function runPython(args, cwd, timeoutMs = FFMPEG_TIMEOUT_MS) {
  const result = spawnSync(process.env.PET_V2_PYTHON || "python3", args, {
    cwd, encoding: "utf8", maxBuffer: 20 * 1024 * 1024, timeout: timeoutMs, killSignal: "SIGKILL",
  });
  if (result.error?.code === "ETIMEDOUT") throw new Error(`python cleanup timed out after ${timeoutMs}ms and was terminated`);
  if (result.status !== 0) throw new Error(`python cleanup: ${result.stderr || result.stdout || result.error?.message || "failed"}`);
}
function assertFfmpeg() {
  run("ffmpeg", ["-version"], process.cwd());
}
function rawFrame(videoPath, seekSeconds) {
  const result = spawnSync("ffmpeg", ["-loglevel", "error", "-ss", String(seekSeconds), "-i", videoPath, "-frames:v", "1", "-vf", "scale=64:64,format=gray", "-f", "rawvideo", "-"], {
    encoding: null, maxBuffer: 20 * 1024 * 1024, timeout: 30_000, killSignal: "SIGKILL",
  });
  if (result.error?.code === "ETIMEDOUT") throw new Error("ffmpeg frame extraction timed out after 30000ms and was terminated");
  if (result.status !== 0 || !result.stdout?.length) throw new Error(`ffmpeg frame extraction failed: ${result.stderr?.toString() || ""}`);
  return result.stdout;
}
function meanAbsoluteDelta(left, right) {
  if (left.length !== right.length) throw new Error("frame buffers have different sizes");
  let total = 0;
  for (let index = 0; index < left.length; index += 1) total += Math.abs(left[index] - right[index]);
  return total / (left.length * 255);
}
function patchWebpFrameDisposal(webpPath) {
  const data = Buffer.from(require("node:fs").readFileSync(webpPath));
  if (data.toString("ascii", 0, 4) !== "RIFF" || data.toString("ascii", 8, 12) !== "WEBP") throw new Error("Not a RIFF WebP file");
  const originalLength = data.length;
  let offset = 12; let frameCount = 0;
  while (offset + 8 <= data.length) {
    const type = data.toString("ascii", offset, offset + 4);
    const size = data.readUInt32LE(offset + 4);
    const payload = offset + 8;
    if (payload + size > data.length) throw new Error(`Invalid ${type} chunk size`);
    if (type === "ANMF") {
      if (size < 16) throw new Error("Invalid ANMF header");
      data[payload + 15] |= 0x03;
      frameCount += 1;
    }
    offset = payload + size + (size & 1);
  }
  if (!frameCount) throw new Error("WebP has no animated frames");
  if (data.length !== originalLength) throw new Error("WebP patch changed file length");
  require("node:fs").writeFileSync(webpPath, data);
  return { frame_count: frameCount, blend: false, dispose_to_background: true };
}
async function assemble({
  videoPath, outputDir, chromaKey, fps = 12, keepFrames = true, createPreview = true,
  edgeCleanup = null,
}) {
  assertFfmpeg();
  const frames = keepFrames ? path.join(outputDir, "frames") : await fs.mkdtemp(path.join(os.tmpdir(), "pet-v2-frames-"));
  if (keepFrames) {
    await fs.rm(frames, { recursive: true, force: true });
    await fs.mkdir(frames, { recursive: true });
  }
  const key = String(chromaKey || "#00ff00").replace("#", "0x");
  // Key a hard alpha mask, remove green spill from foreground RGB, then feather
  // only the alpha channel. This avoids reintroducing green into soft edges.
  const matte = `chromakey=${key}:0.25:0,format=rgba,despill=green:mix=1:expand=0,split[fg][masksrc];[masksrc]alphaextract,gblur=sigma=0.7[alpha];[fg][alpha]alphamerge,format=rgba`;
  let cleanedFrames = null;
  try {
    run("ffmpeg", ["-y", "-i", videoPath, "-filter_complex", matte, "-r", String(fps), path.join(frames, "frame_%04d.png")], outputDir);
    if (edgeCleanup) {
      cleanedFrames = `${frames}-cleaned`;
      runPython([
        path.join(__dirname, "..", "scripts", "neutralize_green_edge.py"),
        "--input", frames, "--output", cleanedFrames,
        "--hue-min", String(edgeCleanup.hueMin ?? 26), "--hue-max", String(edgeCleanup.hueMax ?? 34),
        "--min-saturation", String(edgeCleanup.minSaturation ?? 80), "--edge-width", String(edgeCleanup.edgeWidth ?? 5),
        "--replace-with-neighbor",
      ], outputDir);
      await fs.rm(frames, { recursive: true, force: true });
      await fs.rename(cleanedFrames, frames);
    }
    const frameFiles = (await fs.readdir(frames)).filter((file) => file.endsWith(".png")).sort();
    if (frameFiles.length < 2) throw new Error("Video yielded fewer than two frames");
    const webp = path.join(outputDir, "animation.webp");
    let webpAvailable = false;
    let webpDisposal = null;
    try {
      run("ffmpeg", ["-y", "-framerate", String(fps), "-i", path.join(frames, "frame_%04d.png"), "-frames:v", String(frameFiles.length), "-loop", "0", "-c:v", "libwebp", "-pix_fmt", "yuva420p", webp], outputDir);
      webpDisposal = patchWebpFrameDisposal(webp);
      webpAvailable = true;
    } catch (error) {
      if (!String(error.message).includes("Unknown encoder 'libwebp'")) throw error;
    }
    const preview = createPreview ? path.join(outputDir, "preview.mp4") : null;
    if (preview) run("ffmpeg", ["-y", "-framerate", String(fps), "-i", path.join(frames, "frame_%04d.png"), "-frames:v", String(frameFiles.length), "-filter_complex", "color=c=0x252525:s=1024x1024[bg];[bg][0:v]overlay=(W-w)/2:(H-h)/2,format=yuv420p", "-movflags", "+faststart", preview], outputDir);
    const usableDuration = Math.max(0, (frameFiles.length - 2) / fps);
    const endpointDrift = meanAbsoluteDelta(rawFrame(videoPath, 0), rawFrame(videoPath, usableDuration));
    const sampleOffsets = [0.25, 0.5, 0.75].map((ratio) => usableDuration * ratio);
    const anchor = rawFrame(videoPath, 0);
    const sampledIdentityDrift = sampleOffsets.map((offset) => ({ offset_seconds: offset, delta: meanAbsoluteDelta(anchor, rawFrame(videoPath, offset)) }));
    return {
      frames: keepFrames ? frames : null, frame_count: frameFiles.length, animation_webp: webpAvailable ? webp : null, preview_mp4: preview, webp_disposal: webpDisposal,
      endpoint_drift: endpointDrift,
      sampled_identity_drift: sampledIdentityDrift,
      calibration: "Thresholds require labeled visible_drift/no_visible_drift pilot clips; metrics are reported but do not auto-reject.",
    };
  } finally {
    if (!keepFrames) {
      await fs.rm(frames, { recursive: true, force: true });
      if (cleanedFrames) await fs.rm(cleanedFrames, { recursive: true, force: true });
    }
  }
}
async function createContactSheet({ videoPath, outputDir, fps = 2 }) {
  assertFfmpeg();
  const qaDir = path.join(outputDir, "qa");
  await fs.mkdir(qaDir, { recursive: true });
  const contactSheet = path.join(qaDir, "contact_sheet.jpg");
  run("ffmpeg", ["-y", "-i", videoPath, "-vf", `fps=${fps},scale=256:-1,tile=5x4`, "-frames:v", "1", contactSheet], outputDir);
  return contactSheet;
}
module.exports = { assertFfmpeg, assemble, createContactSheet, patchWebpFrameDisposal };
