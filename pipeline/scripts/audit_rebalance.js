const path = require("node:path");
const { loadDictionaries, buildPrimaryData } = require("../lib/deal.js");

const root = path.resolve(__dirname, "..");
const nonTaskMotivations = new Set([
  "choose_own_course",
  "play_for_its_own_sake", "seek_pleasant_sensation", "postpone_the_necessary",
  "escape_boredom", "admire_own_shape", "keep_a_favorite_close",
  "be_noticed_if_someone_is_there", "enjoy_being_satisfied", "make_a_small_mischief",
]);
const nonAnalyticalInterests = new Set(["pleasant_surfaces", "possible_presence", "motion_as_spectacle", "favorite_objects"]);
const nonTaskGoals = new Set([
  "make_a_readable_image", "stage_a_visual_surprise",
  "settle_comfortably_and_stay", "make_motion_beautiful_without_end",
  "show_something_to_possible_presence", "remain_content_without_result",
  "linger_before_start", "enjoy_a_favorite_thing",
]);
const registers = {
  play: ["play_for_its_own_sake", "make_a_small_mischief"],
  sensory_pleasure: ["seek_pleasant_sensation", "pleasant_surfaces"],
  delay: ["postpone_the_necessary", "linger_before_start"],
  boredom: ["escape_boredom"],
  self_admiration: ["admire_own_shape"],
  attachment: ["keep_a_favorite_close", "favorite_objects", "enjoy_a_favorite_thing"],
  possible_presence: ["be_noticed_if_someone_is_there", "possible_presence", "show_something_to_possible_presence"],
  contentment: ["enjoy_being_satisfied", "remain_content_without_result"],
};

async function main() {
  const dictionaries = await loadDictionaries(root);
  let allTask = 0;
  const examples = { playful: null, contemplative: null, active: null };
  for (let index = 0; index < 1000; index += 1) {
    const deal = buildPrimaryData(dictionaries, { seed: `rebalance-audit-${index}` });
    const { motivation, interest, goal } = deal.selected.inner_modifiers;
    const allAreTasks = !nonTaskMotivations.has(motivation.id)
      && interest.every((item) => !nonAnalyticalInterests.has(item.id))
      && !nonTaskGoals.has(goal.id);
    if (allAreTasks) allTask += 1;
    const tones = deal.selected.character.emotional_tone.map((item) => item.id);
    if (!examples.playful && (nonTaskMotivations.has(motivation.id) || deal.selected.character.humor_style.some((item) => item.fun))) examples.playful = deal;
    if (!examples.contemplative && tones.includes("contemplative") && (nonAnalyticalInterests.has(interest[0].id) || nonAnalyticalInterests.has(interest[1].id))) examples.contemplative = deal;
    if (!examples.active && allAreTasks) examples.active = deal;
  }
  const allIds = new Set(Object.values(dictionaries).flatMap((dictionary) => {
    const gather = (value) => Array.isArray(value) ? value.flatMap(gather) : value && typeof value === "object" ? (value.id ? [value.id] : Object.values(value).flatMap(gather)) : [];
    return gather(dictionary.sections);
  }));
  const result = {
    sample_size: 1000,
    all_task_deals: allTask,
    all_task_share: allTask / 1000,
    expected_share: (12 / 22) * ((10 * 9) / (14 * 13)) * (8 / 16),
    registers_present: Object.fromEntries(Object.entries(registers).map(([name, ids]) => [name, ids.some((id) => allIds.has(id))])),
    required_presence: {
      interest: allIds.has("possible_presence"),
      goal: allIds.has("show_something_to_possible_presence"),
    },
    examples: Object.fromEntries(Object.entries(examples).map(([name, deal]) => [name, deal ? { seed: deal.seed, fingerprint: deal.fingerprint } : null])),
  };
  console.log(JSON.stringify(result, null, 2));
  if (result.all_task_share > 0.25 || Object.values(result.registers_present).includes(false) || !result.required_presence.interest || !result.required_presence.goal) process.exitCode = 1;
}
main().catch((error) => { console.error(error.stack || error.message); process.exitCode = 1; });
