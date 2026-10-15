#!/usr/bin/env node
const fs = require('node:fs/promises');
const path = require('node:path');
const { summarizeCosts } = require('../physics/costs');
const { readJson, atomicJson } = require('../physics/providers');

async function main() {
  const options = Object.fromEntries(process.argv.slice(2).map(arg => {
    const match = /^--([a-z-]+)=(.+)$/.exec(arg);
    if (!match) throw new Error('Use --job=<id> --invoice=<export.json> --scope=<scope.json> [--root=<physics-directory>]');
    return [match[1], match[2]];
  }));
  if (!/^[a-f0-9]{32}$/.test(options.job || '') || !options.invoice || !options.scope) throw new Error('job, invoice and scope are required');
  const directory = path.join(path.resolve(options.root || 'server-data/physics'), options.job);
  const job = await readJson(path.join(directory, 'job.json'));
  if (!job || ['queued', 'running'].includes(job.status)) throw new Error('Only reconcile an inactive known job');
  if (await fs.stat(path.join(directory, 'run.lock')).catch(() => null)) throw new Error('Job is locked');
  const providerDirectory = path.join(directory, 'providers');
  const stages = [];
  for (const file of (await fs.readdir(providerDirectory)).filter(name => name.endsWith('.json')).sort()) {
    const record = await readJson(path.join(providerDirectory, file));
    if (['submitting', 'submission_unknown', 'submitted', 'received'].includes(record.state)) throw new Error('A provider operation is not settled');
    stages.push({ stage: file.slice(0, -5), model: record.model, provider_id: record.provider_id, cost_usd: record.cost_usd ?? null, compute_seconds: record.metrics?.predict_time ?? null });
  }
  const invoice = await readJson(options.invoice);
  const scope = await readJson(options.scope);
  const cost = summarizeCosts(stages, invoice, scope);
  await atomicJson(path.join(directory, 'billing-reconciliation.json'), { invoice, scope });
  await atomicJson(path.join(directory, 'job.json'), { ...job, cost });
  console.log(JSON.stringify({ job: job.id, known_usd: cost.known_usd, complete: cost.complete, replicate_billing: cost.replicate_billing }));
}
main().catch(error => { console.error(error.message); process.exitCode = 1; });
