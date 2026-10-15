const { requireValue } = require('./contracts');

/** Per-request costs remain unknown when only account-level billing is available. */
function summarizeCosts(stages, invoiceExport = null, scope = null) {
  requireValue(stages.every(stage => stage.cost_usd == null || (Number.isFinite(stage.cost_usd) && stage.cost_usd >= 0)), 'invalid_stage_cost');
  const isReplicate = stage => typeof stage.model === 'string' && /:[a-f0-9]{64}$/.test(stage.model);
  const known = stages.reduce((sum, stage) => sum + (stage.cost_usd || 0), 0);
  const summary = { known_usd: known, complete: stages.every(stage => stage.cost_usd != null), stages };
  if (!invoiceExport) return summary;
  const invoice = invoiceExport.invoice;
  const predictions = new Set(stages.filter(stage => isReplicate(stage) && stage.provider_id).map(stage => stage.provider_id));
  const actual = new Set(scope?.predictions?.map(prediction => prediction.id));
  requireValue(scope?.all_pages === true && predictions.size > 0 && predictions.size === actual.size && [...predictions].every(id => actual.has(id)), 'billing_scope_not_exclusive');
  const models = new Set(stages.filter(isReplicate).map(stage => stage.model.split(':')[0]));
  requireValue(invoice?.type === 'monthly-usage' && ['DRAFT', 'PAID', 'FINALIZED', 'OPEN'].includes(invoice.status), 'invalid_billing_invoice');
  requireValue(Array.isArray(invoice.items) && invoice.items.length > 0 && invoice.items.every(item => models.has(item.model)), 'billing_models_do_not_match');
  requireValue(invoice.items.every(item => Number.isFinite(Number(item.cost)) && Number(item.cost) >= 0), 'invalid_billing_total');
  const subtotal = Number(invoice.total_cost_before_adjustments);
  const itemTotal = invoice.items.reduce((sum, item) => sum + Number(item.cost), 0);
  requireValue(Number.isFinite(subtotal) && subtotal >= 0 && Number.isFinite(itemTotal) && Math.abs(subtotal - itemTotal) < 1e-9, 'invalid_billing_total');
  const other = stages.filter(stage => !isReplicate(stage));
  summary.known_usd = other.reduce((sum, stage) => sum + (stage.cost_usd || 0), 0) + subtotal;
  summary.complete = other.every(stage => stage.cost_usd != null);
  summary.replicate_billing = {
    invoice_id: invoice.id, status: invoice.status, checked_at: scope.checked_at,
    cost_usd: subtotal, // Credit use lowers the amount due, not the computation cost.
    exclusive_prediction_count: predictions.size,
    per_prediction_allocation_known: false,
    models: invoice.items.map(({ model, cost }) => ({ model, cost_usd: Number(cost) })),
  };
  return summary;
}

module.exports = { summarizeCosts };
