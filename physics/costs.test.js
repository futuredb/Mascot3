const test = require('node:test');
const assert = require('node:assert/strict');
const { summarizeCosts } = require('./costs');

const stages = [
  { model: 'google/gemini-2.5-flash', provider_id: 'or1', cost_usd: .1485981 },
  { model: `mattsays/sam3-image:${'a'.repeat(64)}`, provider_id: 'rep1', cost_usd: null },
  { model: `mattsays/sam3-image:${'a'.repeat(64)}`, provider_id: 'rep1', cost_usd: 0 },
];
const invoice = { invoice: { id: 'bill1', status: 'DRAFT', type: 'monthly-usage', total_cost: '0', total_cost_before_adjustments: '0.0045', total_adjustments: '-0.0045', items: [{ model: 'mattsays/sam3-image', cost: '0.0045' }] } };
const scope = { all_pages: true, checked_at: '2026-10-15T18:44:56Z', predictions: [{ id: 'rep1' }] };

test('unknown costs stay unknown without a billing record', () => {
  assert.equal(summarizeCosts(stages).complete, false);
  assert.equal(summarizeCosts(stages).known_usd, .1485981);
});
test('exclusive account invoice reconciles aggregate cost without inventing per-request prices', () => {
  const result = summarizeCosts(stages, invoice, scope);
  assert.equal(result.complete, true);
  assert.ok(Math.abs(result.known_usd - .1530981) < 1e-10);
  assert.equal(result.replicate_billing.exclusive_prediction_count, 1);
  assert.equal(result.replicate_billing.cost_usd, .0045);
  assert.equal(result.replicate_billing.per_prediction_allocation_known, false);
  assert.equal(result.stages[1].cost_usd, null);
});
test('unrelated account predictions or incomplete pagination cannot be charged to this hero', () => {
  assert.throws(() => summarizeCosts(stages, invoice, { ...scope, predictions: [...scope.predictions, { id: 'other' }] }), /billing_scope_not_exclusive/);
  assert.throws(() => summarizeCosts(stages, invoice, { ...scope, all_pages: false }), /billing_scope_not_exclusive/);
  assert.throws(() => summarizeCosts(stages, invoice, { ...scope, predictions: [{ id: 'other' }] }), /billing_scope_not_exclusive/);
});
test('inconsistent totals and unrelated model line items are rejected', () => {
  assert.throws(() => summarizeCosts(stages, { invoice: { ...invoice.invoice, total_cost_before_adjustments: '10' } }, scope), /invalid_billing_total/);
  assert.throws(() => summarizeCosts(stages, { invoice: { ...invoice.invoice, items: [{ model: 'other/model', cost: '0.0045' }] } }, scope), /billing_models_do_not_match/);
});
test('invalid stage costs fail closed and OpenRouter model suffixes are not Replicate versions', () => {
  assert.throws(() => summarizeCosts([{ cost_usd: -1 }]), /invalid_stage_cost/);
  assert.throws(() => summarizeCosts([{ cost_usd: NaN }]), /invalid_stage_cost/);
  const result = summarizeCosts([...stages, { model: 'google/gemini-2.5-flash:free', cost_usd: .001 }], invoice, scope);
  assert.ok(Math.abs(result.known_usd - .1540981) < 1e-10);
});
