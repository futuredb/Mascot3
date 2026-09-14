const fs = require("node:fs");
const path = require("node:path");
const { loadEnvFileDefaults } = require("../../../extensions/02_tools_and_apps/_shared/env_file.js");
const { createKieKlingBridgeFromEnv } = require("../../../extensions/01_bridges/kie_kling/bridge.js");

const delay = (milliseconds) => new Promise((resolve) => setTimeout(resolve, milliseconds));

async function main() {
  const [jobId, outputDir] = process.argv.slice(2);
  if (!jobId || !outputDir) throw new Error("Usage: node wait_for_kie_video_probe.js <job-id> <output-dir>");
  const env = loadEnvFileDefaults({ env: process.env, workspaceRoot: path.resolve(__dirname, "..", "..", "..") });
  const bridge = createKieKlingBridgeFromEnv({ env });
  for (;;) {
    const job = await bridge.getVideoJob(jobId, { timeoutMs: 30000 });
    console.log(`VIDEO_STATUS ${job.data.status}`);
    if (job.data.status === "completed") {
      const video = await bridge.downloadVideo(jobId, { timeoutMs: 120000 });
      fs.mkdirSync(outputDir, { recursive: true });
      const destination = path.join(outputDir, `video${video.extension}`);
      fs.writeFileSync(destination, video.buffer);
      fs.writeFileSync(path.join(outputDir, "kie_job.json"), `${JSON.stringify(job.data, null, 2)}\n`);
      console.log(`VIDEO_READY ${destination}`);
      return;
    }
    if (job.data.status === "failed" || job.data.status === "unknown") {
      throw new Error(`Kie video job ${job.data.id} ended as ${job.data.status}: ${job.data.error || "unknown error"}`);
    }
    await delay(15000);
  }
}

main().catch((error) => { console.error(error); process.exitCode = 1; });
