const DEFAULT_VIDEO_MODEL = "bytedance/seedance-2.0-mini";

function resolveVideoModel({ env = {}, options = {} } = {}) {
  return options["video-model"] || options["veo-model"] || env.PET_V2_VIDEO_MODEL || DEFAULT_VIDEO_MODEL;
}

function requiresSeedanceProfile(model) {
  return String(model).startsWith("bytedance/seedance-");
}

module.exports = { DEFAULT_VIDEO_MODEL, resolveVideoModel, requiresSeedanceProfile };
