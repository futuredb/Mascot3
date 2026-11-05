const assert = require("node:assert/strict");
const { mkdtemp, rm, mkdir, writeFile } = require("node:fs/promises");
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
      PHYSICS_ENABLED: "false",
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

  const diagnostics = await fetch(`http://127.0.0.1:${port}/api/health`).then((response) => response.json());
  assert.equal(diagnostics.profile.videoModel, "bytedance/seedance-2.0-mini");
  assert.equal(diagnostics.profile.pipelineAnimations.length, 17);
  assert.equal(diagnostics.profile.uiStates.length, 17);
  assert.equal(diagnostics.profile.totalVideoSeconds, 112);
  assert.equal(diagnostics.profile.estimatedVideoCostUsd, 8.526);
  assert.equal(diagnostics.profile.maxAnimationCostUsd, 9);
  assert.equal(diagnostics.profile.maxHeroCostUsd, 11);

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

  const physics = `http://127.0.0.1:${port}/v1/physics/jobs`;
  assert.equal((await fetch(physics, {method:"POST",headers:{"content-type":"application/json"},body:"{}"})).status,401);
  const disabled = await fetch(physics,{method:"POST",headers:{"content-type":"application/json","x-mascot-client-token":"test-client-token","idempotency-key":"fixture-idempotency"},body:"{}"});
  assert.equal(disabled.status,422);
  assert.equal((await disabled.json()).error,"physics_pilot_disabled");
  assert.equal((await fetch(`${physics}/${"a".repeat(32)}`,{headers:{"x-mascot-client-token":"test-client-token"}})).status,404);
});


test("deleting a hero keeps the others and refuses a running generation", async (t) => {
  const dataRoot = await mkdtemp(path.join(os.tmpdir(), "mascot3-delete-test-"));
  await mkdir(path.join(dataRoot, "server-data"), { recursive: true });
  await writeFile(path.join(dataRoot, "server-data", "mascots.json"), JSON.stringify([
    { id: "one", name: "First", status: "READY", active: true, acceptedAt: "2026-01-01" },
    { id: "two", name: "Second", status: "READY", active: false, acceptedAt: "2026-01-01" },
    { id: "busy", status: "BASE_GENERATING", active: false },
  ]));
  const port = 20000 + Math.floor(Math.random() * 1000);
  const child = spawn(process.execPath, [path.join(ROOT, "server", "server.js")], {
    cwd: ROOT, env: { ...process.env, MASCOT3_PORT: String(port), MASCOT3_DATA_DIR: dataRoot,
      MASCOT3_SKIP_SEED: "true", MASCOT3_CLIENT_TOKEN: "test-client-token", PHYSICS_ENABLED: "false" },
    stdio: "ignore",
  });
  t.after(async () => { child.kill("SIGTERM"); await new Promise((resolve) => child.once("exit", resolve));
    await rm(dataRoot, { recursive: true, force: true }); });
  await waitForServer(port, child);
  const root = `http://127.0.0.1:${port}/v1/mascots`;
  const auth = { "x-mascot-client-token": "test-client-token" };
  assert.equal((await fetch(`${root}/one`, { method: "DELETE" })).status, 401);
  assert.equal((await fetch(`${root}/busy`, { method: "DELETE", headers: auth })).status, 409);
  assert.equal((await fetch(`${root}/one`, { method: "DELETE", headers: auth })).status, 200);
  assert.equal((await fetch(`${root}/one`)).status, 404);
  const remaining = await (await fetch(`${root}/library`)).json();
  assert.deepEqual(remaining.map((hero) => hero.id), ["two"]);
  assert.equal((await (await fetch(`${root}/current/active`)).json()).id, "two");
});
