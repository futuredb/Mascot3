"use strict";

/** Pure planning only: this module never contacts a provider. */
function chooseAnimationBatch(catalog, readyIds, persistedIds, requestedCount) {
  if (!Number.isInteger(requestedCount) || requestedCount < 1 || requestedCount > catalog.length) {
    throw Object.assign(new Error(`animation_count_must_be_1_to_${catalog.length}`), { status: 400 });
  }
  const ready = new Set(readyIds);
  const retainedIds = (persistedIds || []).filter((id) => id !== "welcome");
  const unfinished = retainedIds.filter((id) => !ready.has(id));
  if (unfinished.length) {
    if (unfinished.length > requestedCount) {
      throw Object.assign(new Error(`unfinished_batch_has_${unfinished.length}_animations_resume_that_count_first`), { status: 409 });
    }
    // Keep the exact batch.json directory and provider IDs, not a replanned paid batch.
    if (retainedIds.length !== persistedIds.length) {
      // Keep the original batch/job directories; do not silently replace a paid legacy batch.
      throw Object.assign(new Error("retired_welcome_batch_requires_manual_reconciliation"), { status: 409 });
    }
    return { ids: retainedIds, resume: true };
  }
  const core = ["idle", "happy", "sleep", "playful"].filter((id) => catalog.includes(id));
  const order = [...core, ...catalog.filter((id) => !core.includes(id) && id !== "welcome")];
  return { ids: order.filter((id) => !ready.has(id)).slice(0, requestedCount), resume: false };
}

/** Explicit acceptance never expands into a core/full batch or resumes another paid set. */
function chooseGreetingBatch(catalog, readyIds, persistedIds) {
  if (!catalog.includes("greeting")) throw Object.assign(new Error("greeting_not_supported"), { status: 409 });
  if (readyIds.includes("greeting")) return { ids: [], resume: false };
  const unfinished = (persistedIds || []).filter((id) => !readyIds.includes(id));
  if (unfinished.length) {
    if (persistedIds.length !== 1 || persistedIds[0] !== "greeting") {
      throw Object.assign(new Error("another_animation_batch_requires_reconciliation"), { status: 409 });
    }
    return { ids: ["greeting"], resume: true };
  }
  return { ids: ["greeting"], resume: false };
}

module.exports = { chooseAnimationBatch, chooseGreetingBatch };
