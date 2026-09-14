const fs = require("node:fs");
const path = require("node:path");
const { loadEnvFileDefaults } = require("../../../extensions/02_tools_and_apps/_shared/env_file.js");
const { createVeoBridgeFromEnv } = require("../../../extensions/01_bridges/veo/bridge.js");
const { assemble, createContactSheet } = require("../../lib/media.js");
const { videoFramingQa } = require("../../lib/framing.js");

const delay = (milliseconds) => new Promise((resolve) => setTimeout(resolve, milliseconds));
const MAX_WAIT_MS = 10 * 60 * 1000;

async function main() {
  const [jobId, outputDir] = process.argv.slice(2);
  if (!jobId || !outputDir) throw new Error("Usage: node wait_for_video_probe.js <job-id> <output-dir>");
  const env = loadEnvFileDefaults({ env: process.env, workspaceRoot: path.resolve(__dirname, "..", "..", "..") });
  const bridge = createVeoBridgeFromEnv({ env });
  const deadline = Date.now() + MAX_WAIT_MS;
  for (;;) {
    const job = await bridge.getVideoJob(jobId, { timeoutMs: 30000 });
    const status = String(job.data.status || "unknown");
    console.log(`VIDEO_STATUS ${status}`);
    if (status === "completed") {
      const video = await bridge.downloadVideo(jobId, { timeoutMs: 120000 });
      fs.mkdirSync(outputDir, { recursive: true });
      const destination = path.join(outputDir, `video${video.extension}`);
      fs.writeFileSync(destination, video.buffer);
      fs.writeFileSync(path.join(outputDir, "veo_job.json"), `${JSON.stringify(job.data, null, 2)}\n`);
      console.log(`VIDEO_READY ${destination}`);
      const requestPath = path.join(outputDir, "request.json");
      const request = fs.existsSync(requestPath) ? JSON.parse(fs.readFileSync(requestPath, "utf8")) : {};
      try {
        const assembly = await assemble({ videoPath: destination, outputDir, chromaKey: "#00FF00" });
        const contactSheet = await createContactSheet({ videoPath: destination, outputDir });
        const framing = videoFramingQa(destination);
        const qa = { ...assembly, contact_sheet: contactSheet, framing };
        const experiment = {
          contract: "pet_generation_v2.probe_record.v1",
          preset: request.preset || null, hypothesis: "pending human label",
          variable: "pending human label", seed: request.seed ?? null,
          automated: { framing: framing.framing_pass ? "pass" : "fail", motion: "unreviewed", identity: "unreviewed", prop: "unreviewed", loop: "unreviewed" },
          decision: framing.framing_pass ? "pending_visual_review" : "rejected_framing",
        };
        fs.writeFileSync(path.join(outputDir, "qa.json"), `${JSON.stringify(qa, null, 2)}\n`);
        fs.writeFileSync(path.join(outputDir, "experiment.json"), `${JSON.stringify(experiment, null, 2)}\n`);
        console.log(`POSTPROCESS_READY FRAMING_${framing.framing_pass ? "PASS" : "FAIL"}`);
      } catch (error) {
        fs.writeFileSync(path.join(outputDir, "postprocess_error.json"), `${JSON.stringify({
          message: error.message,
          video: destination,
        }, null, 2)}\n`);
        console.warn(`POSTPROCESS_SKIPPED ${error.message}`);
      }
      return;
    }
    if (status === "failed" || status === "cancelled") throw new Error(`Video job ${jobId} ended as ${status}.`);
    if (Date.now() >= deadline) throw new Error(`Video job ${jobId} did not finish within ${MAX_WAIT_MS / 60000} minutes.`);
    await delay(15000);
  }
}
main().catch((error) => { console.error(error); process.exitCode = 1; });
