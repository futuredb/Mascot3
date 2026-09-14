const fs = require("node:fs");
const path = require("node:path");

function parseEnv(contents) {
  const result = {};
  for (const line of contents.split(/\r?\n/)) {
    const value = line.trim();
    if (!value || value.startsWith("#") || !value.includes("=")) continue;
    const index = value.indexOf("=");
    const key = value.slice(0, index).trim();
    const raw = value.slice(index + 1).trim();
    result[key] = raw.replace(/^(['"])(.*)\1$/, "$2");
  }
  return result;
}

function loadEnvFileDefaults({ env = process.env, workspaceRoot = process.cwd() } = {}) {
  const merged = { ...env };
  for (const candidate of [path.join(workspaceRoot, ".env"), path.join(workspaceRoot, ".env.local")]) {
    if (!fs.existsSync(candidate)) continue;
    const values = parseEnv(fs.readFileSync(candidate, "utf8"));
    for (const [key, value] of Object.entries(values)) {
      if (merged[key] === undefined || merged[key] === "") merged[key] = value;
    }
  }
  return merged;
}

module.exports = { loadEnvFileDefaults, parseEnv };
