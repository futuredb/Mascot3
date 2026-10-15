const crypto = require('node:crypto');

const FORMAT = 'mascot-physics-2.5d';
const VERSION = 1;
const PILOT_ID = '716cdb43-703b-4d7e-8a79-27f03d92b99c';
const sha256 = bytes => crypto.createHash('sha256').update(bytes).digest('hex');
const safeName = value => typeof value === 'string' && /^[a-z][a-z0-9_-]{0,63}$/.test(value);
const finite = (value, min, max) => Number.isFinite(value) && value >= min && value <= max;
function requireValue(condition, message) {
  if (!condition) throw Object.assign(new Error(message), { status: 422 });
}

function validateAnalysis(value) {
  requireValue(value && Array.isArray(value.parts) && value.parts.length >= 1 && value.parts.length <= 16, 'invalid_parts');
  const ids = new Set(['body']);
  for (const part of value.parts) {
    requireValue(safeName(part.id) && !ids.has(part.id), 'invalid_part_id');
    requireValue(typeof part.prompt === 'string' && part.prompt.length >= 3 && part.prompt.length <= 200, 'invalid_part_prompt');
    if (part.mask_prompts !== undefined) requireValue(Array.isArray(part.mask_prompts) && part.mask_prompts.length <= 3 && part.mask_prompts.every(p=>typeof p==='string'&&p.length>=3&&p.length<=100),'invalid_mask_prompts');
    requireValue(['hair', 'cord', 'cloth', 'rigid'].includes(part.material), 'invalid_material');
    requireValue(typeof part.parent_id === 'string' && ids.has(part.parent_id), 'parent_must_precede_child');
    requireValue(Array.isArray(part.bounds) && part.bounds.length === 4 && part.bounds.every(v => finite(v, 0, 1)) && part.bounds[2] > part.bounds[0] && part.bounds[3] > part.bounds[1], 'invalid_part_bounds');
    requireValue(Array.isArray(part.attachment) && part.attachment.length === 2 && part.attachment.every(v => finite(v, 0, 1)), 'invalid_attachment');
    requireValue(Number.isInteger(part.order) && part.order >= 1 && part.order <= 32, 'invalid_layer_order');
    ids.add(part.id);
  }
  requireValue(value.parts.some(p => p.material !== 'rigid'), 'no_secondary_physics_parts');
  return value;
}

function validatePack(pack, { requireApproval = true } = {}) {
  requireValue(pack?.format === FORMAT && pack.version === VERSION, 'unsupported_physics_pack');
  requireValue(/^[a-f0-9]{64}$/.test(pack.source_sha256) && /^[a-f0-9]{64}$/.test(pack.anchor_sha256), 'invalid_source_hash');
  requireValue(Number.isInteger(pack.width) && Number.isInteger(pack.height) && finite(pack.width, 64, 2048) && finite(pack.height, 64, 2048), 'invalid_canvas');
  requireValue(pack.max_tilt_degrees === 12, 'invalid_tilt_limit');
  if (pack.max_body_offset !== undefined) requireValue(finite(pack.max_body_offset,0,.05),'invalid_body_offset');
  if (pack.max_lean_degrees !== undefined) requireValue(finite(pack.max_lean_degrees,0,12),'invalid_lean_limit');
  requireValue(Array.isArray(pack.layers) && pack.layers.length >= 2 && pack.layers.length <= 17, 'invalid_layers');
  requireValue(!requireApproval || (pack.qa?.approved === true && finite(pack.qa.neutral_error, 0, .025)), 'physics_qa_not_approved');
  const ids = new Set();
  const known = new Map();
  let totalVertices = 0;
  for (const layer of pack.layers) {
    requireValue(safeName(layer.id) && !ids.has(layer.id), 'invalid_layer_id');
    requireValue(layer.id === 'body' ? layer.parent_id === null : ids.has(layer.parent_id), 'invalid_layer_parent');
    requireValue(['rigid', 'hair', 'cord', 'cloth'].includes(layer.material), 'invalid_material');
    requireValue(Number.isInteger(layer.order) && finite(layer.order, 0, 32), 'invalid_layer_order');
    requireValue(safeName(layer.texture) && safeName(layer.depth), 'invalid_asset_key');
    requireValue(Array.isArray(layer.vertices) && layer.vertices.length >= 3 && layer.vertices.length <= 12000, 'invalid_vertices');
    totalVertices += layer.vertices.length;
    requireValue(layer.vertices.every(v => Array.isArray(v) && v.length === 6 && v.every(Number.isFinite) && finite(v[0], 0, 1) && finite(v[1], 0, pack.height / pack.width + .001) && finite(v[2], -.1, .1) && finite(v[3], 0, 1) && finite(v[4], 0, 1) && finite(v[5], 0, 1)), 'invalid_vertex');
    requireValue(Array.isArray(layer.triangles) && layer.triangles.length >= 3 && layer.triangles.length % 3 === 0 && layer.triangles.every(i => Number.isInteger(i) && i >= 0 && i < layer.vertices.length), 'invalid_triangles');
    requireValue(Array.isArray(layer.edges) && Array.isArray(layer.bends) && [...layer.edges, ...layer.bends].every(e => Array.isArray(e) && e.length === 3 && e[0] !== e[1] && [e[0], e[1]].every(i => Number.isInteger(i) && i >= 0 && i < layer.vertices.length) && finite(e[2], .000001, 2)), 'invalid_constraints');
    for (let i = 0; i < layer.triangles.length; i += 3) {
      const [a, b, c] = layer.triangles.slice(i, i + 3).map(j => layer.vertices[j]);
      requireValue((b[0]-a[0])*(c[1]-a[1]) - (b[1]-a[1])*(c[0]-a[0]) > 1e-9, 'degenerate_mesh_triangle');
    }
    requireValue(layer.vertices.some(v => v[5] === 0), 'unattached_layer');
    if (layer.material !== 'rigid') requireValue(layer.vertices.some(v => v[5] > .1), 'immobile_soft_part');
    requireValue(finite(layer.damping, 1, 20) && finite(layer.stiffness, 1, 200) && finite(layer.max_displacement, .001, .06), 'invalid_physical_parameters');
    const parent = known.get(layer.parent_id);
    requireValue(Array.isArray(layer.pins) && layer.pins.every(p => Array.isArray(p) && p.length === 2 && p.every(Number.isInteger) && layer.vertices[p[0]]?.[5] === 0 && parent?.vertices[p[1]]), 'invalid_pins');
    if (parent) {
      requireValue(layer.pins.length > 0, 'unattached_layer');
      if (layer.material !== 'rigid') requireValue(layer.vertices.every((v, i) => v[5] !== 0 || layer.pins.some(p => p[0] === i)), 'missing_pin_constraint');
    }
    ids.add(layer.id);
    known.set(layer.id, layer);
  }
  requireValue(totalVertices <= 12000, 'mesh_budget_exceeded');
  requireValue(pack.layers.some(l => l.material !== 'rigid'), 'no_secondary_physics_layers');
  for (const [name, asset] of Object.entries(pack.assets || {})) {
    requireValue(safeName(name) && /^[a-z][a-z0-9_-]*\.png$/.test(asset.file) && /^[a-f0-9]{64}$/.test(asset.sha256) && Number.isInteger(asset.size) && finite(asset.size, 1, 30*1024*1024), 'invalid_asset');
  }
  requireValue(pack.layers.every(l => pack.assets[l.texture] && pack.assets[l.depth]), 'missing_assets');
  return pack;
}

module.exports = { FORMAT, VERSION, PILOT_ID, sha256, safeName, requireValue, validateAnalysis, validatePack };
