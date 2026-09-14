const fs = require("node:fs");
const path = require("node:path");
const { loadEnvFileDefaults } = require("../../../extensions/02_tools_and_apps/_shared/env_file.js");
const { createVeoBridgeFromEnv } = require("../../../extensions/01_bridges/veo/bridge.js");

async function main() {
  const [jobId, outputDir] = process.argv.slice(2);
  if (!jobId || !outputDir) throw new Error("Usage: node download_video_probe.js <job-id> <output-dir>");
  const env = loadEnvFileDefaults({ env: process.env, workspaceRoot: path.resolve(__dirname, "..", "..", "..") });
  const bridge = createVeoBridgeFromEnv({ env });
  const job = await bridge.getVideoJob(jobId, { timeoutMs: 30000 });
  if (job.data.status !== "completed") throw new Error(`Video ${jobId} is ${job.data.status}.`);
  const video = await bridge.downloadVideo(jobId, { timeoutMs: 120000 });
  fs.mkdirSync(outputDir, { recursive: true });
  const destination = path.join(outputDir, `video${video.extension}`);
  fs.writeFileSync(destination, video.buffer);
  fs.writeFileSync(path.join(outputDir, "veo_job.json"), `${JSON.stringify(job.data, null, 2)}\n`);
  console.log(destination);
}
main().catch((error) => { console.error(error); process.exitCode = 1; });
