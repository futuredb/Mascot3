"use strict";

const { hash } = require("./prompt_catalog");

// Fingerprint of v44's complete render contract, excluding verification metadata.
// Removing the duplicate welcome also leaves the complete legacy render contract unchanged.
const PRE_WELCOME_RENDER_HASH = "a4b3e6d0fa7758be6371020a931ecfa8e1c175028035294629a460bd4552dd32";

function canResumeAdditiveWelcomeUpdate(existing, catalog) {
  if (!((existing?.catalog_version === 44 && [45, 46].includes(catalog?.version)) ||
        (existing?.catalog_version === 45 && catalog?.version === 46)) || !existing.states?.length) return false;
  const legacyEntries = catalog.entries.filter((entry) => entry.id !== "welcome");
  const legacyIds = new Set(legacyEntries.map((entry) => entry.id));
  if (!existing.states.every((state) => legacyIds.has(state.id))) return false;
  const renderContract = {
    prefix: catalog.prefix,
    suffix: catalog.suffix,
    entries: legacyEntries.map((entry) => ({
      id: entry.id, template: entry.template, duration_seconds: entry.duration_seconds, source_hash: entry.source_hash,
    })),
  };
  return hash(JSON.stringify(renderContract)) === PRE_WELCOME_RENDER_HASH;
}

module.exports = { canResumeAdditiveWelcomeUpdate };
