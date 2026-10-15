const fs = require('node:fs/promises');
const path = require('node:path');
const { sha256, requireValue } = require('./contracts');

const MODELS = Object.freeze({
  matte: 'men1scus/birefnet:f74986db0355b58403ed20963af156525e2891ea3c2d499bfbfb2a28cd87c5d7',
  masks: 'mattsays/sam3-image:d73db077226443ba4fafd34e233b3626b552eac2a433f90c7c32a9ac89bd9e72',
  depth: 'chenxwh/depth-anything-v2:b239ea33cff32bb7abb5db39ffe9a09c14cbc2894331d1ef66fe096eed88ebd4',
});
async function atomicJson(file, value) {
  await fs.mkdir(path.dirname(file), { recursive: true });
  const handle = await fs.open(file + '.pending', 'w', 0o600);
  try { await handle.writeFile(JSON.stringify(value, null, 2)); await handle.sync(); } finally { await handle.close(); }
  await fs.rename(file + '.pending', file);
}
async function readJson(file, fallback = null) {
  try { return JSON.parse(await fs.readFile(file, 'utf8')); } catch (e) { if (e.code === 'ENOENT') return fallback; throw e; }
}
const dataUrl = bytes => `data:image/png;base64,${bytes.toString('base64')}`;

/** Each paid POST is preceded by a durable marker. Unknown outcomes never resubmit. */
class Providers {
  constructor({ env, directory, fetchImpl = fetch }) { this.env = env; this.directory = directory; this.fetch = fetchImpl; }
  async request(url, options = {}) {
    const response = await this.fetch(url, { ...options, signal: AbortSignal.timeout(240000) });
    if (!response.ok) throw Object.assign(new Error(`provider_http_${response.status}`), { httpStatus: response.status });
    return response.json();
  }
  async submissionFailure(file, record, stage, error) {
    // A received 4xx (except timeout) explicitly rejected creation. A transport
    // failure or 5xx can conceal an accepted request and must never be retried.
    const rejected = error.httpStatus >= 400 && error.httpStatus < 500 && error.httpStatus !== 408;
    record.state = rejected ? 'rejected' : 'submission_unknown';
    record.error = error.httpStatus ? `provider_http_${error.httpStatus}` : String(error.cause?.code || error.name || 'transport_error');
    if (error.httpStatus === 429) record.retry_at = Date.now() + 60000;
    if (rejected) record.cost_usd = 0;
    await atomicJson(file, record);
    throw new Error(rejected ? `provider_rejected:${stage}:${error.httpStatus}` : `submission_unknown:${stage}`, { cause: error });
  }
  async download(url) {
    if (url.startsWith('data:image/')) {
      const match = /^data:image\/(?:png|jpeg|webp);base64,([A-Za-z0-9+/=]+)$/.exec(url);
      requireValue(match && match[1].length <= 40 * 1024 * 1024, 'invalid_image_data');
      return Buffer.from(match[1], 'base64');
    }
    const u = new URL(url);
    requireValue(u.protocol === 'https:' && (u.hostname === 'replicate.delivery' || u.hostname.endsWith('.replicate.delivery')), 'unexpected_provider_asset_host');
    const response = await this.fetch(url, { headers: { Authorization: `Bearer ${this.env.REPLICATE_API_TOKEN}` }, redirect: 'error', signal: AbortSignal.timeout(60000) });
    if (!response.ok) throw new Error(`asset_http_${response.status}`);
    const chunks = []; let size = 0;
    for await (const bytes of response.body) { size += bytes.length; requireValue(size <= 30 * 1024 * 1024, 'provider_asset_too_large'); chunks.push(bytes); }
    return Buffer.concat(chunks);
  }
  async replicate(stage, model, input) {
    requireValue(this.env.REPLICATE_API_TOKEN, 'replicate_token_missing');
    const file = path.join(this.directory, `${stage}.json`);
    const requestHash = sha256(JSON.stringify({ model, input }));
    let record = await readJson(file);
    requireValue(!record || record.request_hash === requestHash, 'provider_request_changed');
    if (!record) {
      // A revised segmentation strategy may use a different stage name for the
      // exact same paid request. Reuse its output/ID, including uncertain sends.
      for (const name of (await fs.readdir(this.directory).catch(() => [])).filter(n => n.endsWith('.json'))) {
        const prior = await readJson(path.join(this.directory, name));
        if (prior?.request_hash !== requestHash || prior.state === 'rejected') continue;
        if (prior.state === 'complete') {
          record = { ...prior, cost_usd: 0, metrics: undefined, reused_from: name };
          await atomicJson(file, record); return record.output;
        }
        if (!prior.provider_id) throw new Error(`submission_unknown:${name.slice(0,-5)}`);
        // Poll the original stage so billing/progress remain recorded only once.
        return this.replicate(name.slice(0,-5), model, input);
      }
    }
    if (record?.state === 'complete') return record.output;
    if (record?.state === 'rejected' && record.retry_at > Date.now()) throw new Error(`rate_limited:${stage}`);
    const attempts = record?.attempts || [];
    if (record?.state === 'rejected') { attempts.push({ submitted_at: record.submitted_at, error: record.error, cost_usd: 0 }); record = null; }
    if (record && !record.provider_id) throw new Error(`submission_unknown:${stage}`);
    if (!record) {
      record = { state: 'submitting', model, request_hash: requestHash, submitted_at: new Date().toISOString(), attempts };
      await atomicJson(file, record);
      let prediction;
      try { prediction = await this.request('https://api.replicate.com/v1/predictions', { method: 'POST', headers: { Authorization: `Bearer ${this.env.REPLICATE_API_TOKEN}`, 'Content-Type': 'application/json' }, body: JSON.stringify({ version: model.split(':')[1], input }) }); }
      catch (error) { return this.submissionFailure(file, record, stage, error); }
      requireValue(typeof prediction.id === 'string' && /^[a-z0-9]+$/.test(prediction.id), 'missing_prediction_id');
      record.provider_id = prediction.id;
      record.state = 'submitted';
      await atomicJson(file, record);
    }
    const deadline = Date.now() + 15 * 60 * 1000;
    while (Date.now() < deadline) {
      let prediction;
      try { prediction = await this.request(`https://api.replicate.com/v1/predictions/${record.provider_id}`, { headers: { Authorization: `Bearer ${this.env.REPLICATE_API_TOKEN}` } }); }
      catch (error) { throw new Error(`provider_still_running:${stage}`, {cause:error}); }
      if (prediction.status === 'succeeded') {
        requireValue(prediction.output, 'provider_output_expired');
        record.state = 'complete'; record.output = prediction.output; record.metrics = prediction.metrics;
        record.cost_usd = null; // Replicate metrics are compute time, not a billed dollar amount.
        await atomicJson(file, record);
        return record.output;
      }
      if (['failed', 'canceled'].includes(prediction.status)) {
        record.state = prediction.status; await atomicJson(file, record);
        throw new Error(`provider_${prediction.status}:${stage}`);
      }
      await new Promise(resolve => setTimeout(resolve, 2000));
    }
    throw new Error(`provider_still_running:${stage}`);
  }
  async vision(stage, prompt, images, { imageOutput = false } = {}) {
    requireValue(this.env.OPENROUTER_API_KEY, 'openrouter_key_missing');
    const model = imageOutput ? (this.env.PHYSICS_IMAGE_MODEL || 'google/gemini-3.1-flash-image-preview') : (this.env.PHYSICS_VLM_MODEL || 'google/gemini-2.5-flash');
    const payload = { model, messages: [{ role: 'user', content: [{ type: 'text', text: prompt }, ...images.map(bytes => ({ type: 'image_url', image_url: { url: dataUrl(bytes) } }))] }], max_tokens: imageOutput ? 8000 : 5000, ...(imageOutput ? { modalities: ['image', 'text'] } : { response_format: { type: 'json_object' }, temperature: 0 }) };
    const file = path.join(this.directory, `${stage}.json`);
    const requestHash = sha256(JSON.stringify(payload));
    let previous = await readJson(file);
    requireValue(!previous || previous.request_hash === requestHash, 'provider_request_changed');
    if (previous?.state === 'complete') return previous.result;
    if (previous?.state === 'rejected' && previous.retry_at > Date.now()) throw new Error(`rate_limited:${stage}`);
    const attempts = previous?.attempts || [];
    if (previous?.state === 'rejected') { attempts.push({ submitted_at: previous.submitted_at, error: previous.error, cost_usd: 0 }); previous = null; }
    if (previous && !previous.response) throw new Error(`submission_unknown:${stage}`);
    if (!previous) {
      const submission = { state: 'submitting', model, request_hash: requestHash, submitted_at: new Date().toISOString(), attempts };
      await atomicJson(file, submission);
      let response;
      try { response = await this.request('https://openrouter.ai/api/v1/chat/completions', { method: 'POST', headers: { Authorization: `Bearer ${this.env.OPENROUTER_API_KEY}`, 'Content-Type': 'application/json' }, body: JSON.stringify(payload) }); }
      catch (error) { return this.submissionFailure(file, submission, stage, error); }
      previous = { ...submission, state: 'received', provider_id: response.id, cost_usd: response.usage?.cost ?? null, response };
      await atomicJson(file, previous); // Invalid structured output still retains its request ID and cost.
    }
    const response = previous.response;
    const message = response.choices?.[0]?.message;
    let result;
    if (imageOutput) {
      result = message?.images?.[0]?.image_url?.url;
      requireValue(typeof result === 'string' && result.startsWith('data:image/'), 'missing_inline_generated_image');
    } else {
      const content = Array.isArray(message?.content) ? message.content.map(p => p.text || '').join('') : message?.content;
      result = JSON.parse(String(content).replace(/^```(?:json)?\s*|\s*```$/g, ''));
    }
    await atomicJson(file, { state: 'complete', model, request_hash: requestHash, provider_id: response.id, cost_usd: response.usage?.cost ?? null, attempts: previous.attempts || [], result });
    return result;
  }
}
module.exports = { MODELS, Providers, atomicJson, readJson, dataUrl };
