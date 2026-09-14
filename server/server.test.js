const assert = require("node:assert/strict");
const { mkdtemp, rm } = require("node:fs/promises");
const os = require("node:os");
const path = require("node:path");
const { spawn } = require("node:child_process");
const test = require("node:test");

const ROOT = path.resolve(__dirname, "..");

async function waitForServer(port, child) {
  for (let attempt = 0; attempt < 80; attempt += 1) {
    if (child.exitCode !== null) throw new Error(`server exited with ${child.exitCode}`);
    try {
      const response = await fetch(`http://127.0.0.1:${port}/health`);
      if (response.ok) return;
    } catch { /* server is still starting */ }
    await new Promise((resolve) => setTimeout(resolve, 50));
  }
  throw new Error("server did not become healthy");
}

test("health stays public while mutations require the client token", async (t) => {
  const dataRoot = await mkdtemp(path.join(os.tmpdir(), "mascot3-server-test-"));
  const port = 19000 + Math.floor(Math.random() * 1000);
  const child = spawn(process.execPath, [path.join(ROOT, "server", "server.js")], {
    cwd: ROOT,
    env: {
      ...process.env,
      MASCOT3_PORT: String(port),
      MASCOT3_DATA_DIR: dataRoot,
      MASCOT3_SKIP_SEED: "true",
      MASCOT3_CLIENT_TOKEN: "test-client-token",
    },
    stdio: "ignore",
  });
  t.after(async () => {
    child.kill("SIGTERM");
    await new Promise((resolve) => child.once("exit", resolve));
    await rm(dataRoot, { recursive: true, force: true });
  });

  await waitForServer(port, child);

  const health = await fetch(`http://127.0.0.1:${port}/health`);
  assert.equal(health.status, 200);

  const unauthorized = await fetch(`http://127.0.0.1:${port}/v1/profile/city`, {
    method: "PUT",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ city_id: "524901", name: "Moscow" }),
  });
  assert.equal(unauthorized.status, 401);

  const authorized = await fetch(`http://127.0.0.1:${port}/v1/profile/city`, {
    method: "PUT",
    headers: {
      "content-type": "application/json",
      "x-mascot-client-token": "test-client-token",
    },
    body: JSON.stringify({ city_id: "524901", name: "Moscow" }),
  });
  assert.equal(authorized.status, 200);
});
