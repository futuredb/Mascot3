const fs = require("node:fs");
const path = require("node:path");

function main() {
  const [inputArg, outputArg] = process.argv.slice(2);
  if (!inputArg || !outputArg) {
    throw new Error("Usage: node patch_webp_frame_disposal.js <input.webp> <output.webp>");
  }
  const input = path.resolve(inputArg);
  const output = path.resolve(outputArg);
  const data = Buffer.from(fs.readFileSync(input));
  if (data.toString("ascii", 0, 4) !== "RIFF" || data.toString("ascii", 8, 12) !== "WEBP") {
    throw new Error(`Not a RIFF WebP file: ${input}`);
  }

  let offset = 12;
  let frameCount = 0;
  while (offset + 8 <= data.length) {
    const type = data.toString("ascii", offset, offset + 4);
    const size = data.readUInt32LE(offset + 4);
    const payload = offset + 8;
    if (payload + size > data.length) throw new Error(`Invalid ${type} chunk size`);
    if (type === "ANMF") {
      if (size < 16) throw new Error("Invalid ANMF frame header");
      // ANMF flag bits: bit 1 disables alpha blending; bit 0 disposes to background.
      data[payload + 15] |= 0x03;
      frameCount += 1;
    }
    offset = payload + size + (size & 1);
  }
  if (frameCount === 0) throw new Error("No animated WebP frames found");
  fs.mkdirSync(path.dirname(output), { recursive: true });
  fs.writeFileSync(output, data);
  console.log(JSON.stringify({ input, output, frameCount, blend: false, disposeToBackground: true }, null, 2));
}

try {
  main();
} catch (error) {
  console.error(error.stack || error.message);
  process.exitCode = 1;
}
