const fs = require("node:fs");
const path = require("node:path");
const { spawnSync } = require("node:child_process");

function run(command, args) {
  const result = spawnSync(command, args, { encoding: "utf8", maxBuffer: 20 * 1024 * 1024 });
  if (result.status !== 0) throw new Error(`${command}: ${result.stderr || result.stdout}`);
  return result.stdout;
}

function sourceFps(videoPath) {
  const output = run("ffprobe", [
    "-v", "error",
    "-select_streams", "v:0",
    "-show_entries", "stream=avg_frame_rate",
    "-of", "default=noprint_wrappers=1:nokey=1",
    videoPath,
  ]).trim();
  const [numerator, denominator = "1"] = output.split("/").map(Number);
  const fps = numerator / denominator;
  if (!Number.isFinite(fps) || fps <= 0) throw new Error(`Invalid source FPS: ${output}`);
  return fps;
}

function main() {
  const [sourceArg, outputArg] = process.argv.slice(2);
  if (!sourceArg || !outputArg) {
    throw new Error("Usage: node assemble_exact_frames_probe.js <source-video> <output-dir>");
  }
  const source = path.resolve(sourceArg);
  const outputDir = path.resolve(outputArg);
  const framesDir = path.join(outputDir, "frames");
  if (!fs.existsSync(source)) throw new Error(`Missing source video: ${source}`);
  fs.mkdirSync(framesDir, { recursive: true });

  const fps = sourceFps(source);
  run("ffmpeg", [
    "-loglevel", "error", "-y",
    "-i", source,
    "-vf", "chromakey=0x00ff00:0.12:0.08,format=rgba",
    "-fps_mode", "passthrough",
    path.join(framesDir, "frame_%05d.png"),
  ]);
  run("ffmpeg", [
    "-loglevel", "error", "-y",
    "-framerate", String(fps),
    "-i", path.join(framesDir, "frame_%05d.png"),
    "-loop", "0",
    "-c:v", "libwebp",
    "-lossless", "1",
    "-compression_level", "4",
    "-fps_mode", "passthrough",
    path.join(outputDir, "animation_exact_frames.webp"),
  ]);

  const frameCount = fs.readdirSync(framesDir).filter((file) => file.endsWith(".png")).length;
  fs.writeFileSync(path.join(outputDir, "assembly.json"), `${JSON.stringify({
    contract: "pet_generation_v2.exact_frame_assembly_probe.v1",
    source,
    source_fps: fps,
    frame_count: frameCount,
    interpolation: false,
    crossfade: false,
    seam_transition: "hard",
  }, null, 2)}\n`);
  console.log(JSON.stringify({ outputDir, fps, frameCount }, null, 2));
}

try {
  main();
} catch (error) {
  console.error(error.stack || error.message);
  process.exitCode = 1;
}
