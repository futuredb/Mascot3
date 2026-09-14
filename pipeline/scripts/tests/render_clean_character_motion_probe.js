const fs = require("node:fs");
const path = require("node:path");
const { loadEnvFileDefaults } = require("../../../extensions/02_tools_and_apps/_shared/env_file.js");
const { createVeoBridgeFromEnv } = require("../../../extensions/01_bridges/veo/bridge.js");

const ROOT = path.resolve(__dirname, "..", "..");

function dataUrl(file) {
  const extension = path.extname(file).toLowerCase() === ".jpg" ? "jpeg" : "png";
  return `data:image/${extension};base64,${fs.readFileSync(file).toString("base64")}`;
}

async function main() {
  const characterDir = path.resolve(process.argv[2]);
  const runCycle = process.argv.includes("--run-cycle");
  const minimalRun = process.argv.includes("--minimal-run");
  const minimalFastRun = process.argv.includes("--minimal-fast-run");
  const minimalSpark = process.argv.includes("--minimal-spark");
  const sleepEnter = process.argv.includes("--sleep-enter");
  const sleepLoop = process.argv.includes("--sleep-loop");
  const sleepExit = process.argv.includes("--sleep-exit");
  const minimalAnimation = process.argv.find((argument) => argument.startsWith("--minimal-animation="))?.slice("--minimal-animation=".length);
  const minimalAnimationPrompts = {
    idle: "Front-view turtle calmly studies the three picture cards she holds, tilts her head slightly toward them, and blinks once. Static camera.",
    sleep: "Front-view turtle slowly grows sleepy, closes her eyes, and lowers her head into a comfortable resting pose near the front edge of her shell. Static camera.",
    thinking: "Front-view turtle studies the three picture cards from left to right, then gives one small decisive nod. Static camera.",
    work: "Front-view turtle carefully adjusts one of her three folding picture cards with her free front foot, aligns it with the others, then inspects the sequence. Static camera.",
    spark: "Front-view turtle looks up as a thin golden geometric pulse travels once around the outer rim of her shell and fades. Static camera.",
  };
  if (minimalAnimation && !minimalAnimationPrompts[minimalAnimation]) {
    throw new Error(`Unsupported minimal animation: ${minimalAnimation}`);
  }
  const source = sleepLoop || sleepExit
    ? path.join(characterDir, "animation", "_assets", "sleep_front", "anchor_sleep.jpg")
    : sleepEnter
    ? path.join(characterDir, "animation", "_assets", "clean_front", "anchor_clean.jpg")
    : minimalAnimation
    ? path.join(characterDir, "canonical", "anchor_00.jpg")
    : path.join(characterDir, "animation", "_assets", runCycle || minimalRun || minimalFastRun || minimalSpark ? "clean_side" : "clean_front", "anchor_clean.jpg");
  const lastSource = sleepEnter
    ? path.join(characterDir, "animation", "_assets", "sleep_front", "anchor_sleep.jpg")
    : sleepExit
    ? path.join(characterDir, "animation", "_assets", "clean_front", "anchor_clean.jpg")
    : source;
  const primaryData = JSON.parse(fs.readFileSync(path.join(characterDir, "primary_data.json"), "utf8"));
  const lore = JSON.parse(fs.readFileSync(path.join(characterDir, "lore.json"), "utf8"));
  const movementContact = Array.isArray(lore.body_profile?.movement_contact) ? lore.body_profile.movement_contact : [];
  const sharedPrompt = [
    `ANIMAL TYPE: ${primaryData.selected?.subject_request || lore.identity?.subject || "living creature"}.`,
    "One immutable turtle with one head, one articulated neck, one rigid shell and torso, and exactly four consistently attached legs.",
    "No stretching, merging, duplication, disappearing parts, shell flex, limb intersections, props, or invented objects.",
  ];
  const actionPrompt = runCycle ? [
    "Create a silent seamless 6-second fast running-in-place cycle in strict side profile, using the supplied clean side image as the exact first and last frame.",
    "Animate a physically plausible quick turtle scuttle: low heavy body, short rapid strides, no airborne phase.",
    "Each foreleg reaches forward on a curved arc, plants, and pulls backward while the opposite hind leg pushes; then the other diagonal pair repeats.",
    "Complete three readable stride cycles with overlapping contacts so the body remains continuously supported.",
    "The rigid shell and torso travel together with restrained vertical weight shift; the shell never bounces independently.",
    "The neck reaches slightly forward during acceleration and eases back during recovery, with delayed follow-through.",
    "Use strong push-off, clear foot lift, curved foot paths, changing speed, asymmetry, momentum, and organic overlap.",
    "Keep the character centered as if the camera tracks the run. Do not render the contact plane.",
    "No sliding feet, moonwalk, limb swapping, rubber limbs, simultaneous four-leg motion, mechanical pendulum motion, frozen torso, or robotic stops.",
  ] : [
    `NATURAL MOTION RULES: ${movementContact.join(" ")}.`,
    "Create a silent seamless 6-second dynamic hover-idle loop with the supplied clean front image as the exact first and last frame.",
    "There is no floor. A sideways drift catches Klara off balance.",
    "She reacts with an alert head turn and two strong staggered paddling strokes: front-left with rear-right, then front-right with rear-left.",
    "The rigid shell and torso travel together along a shallow curved path by about 15 percent of shell width, with visible mass and delayed inertia.",
    "The neck counterbalances on an opposing arc. One front foot then briefly braces against the invisible widget glass to brake; do not render the glass.",
    "She decelerates, slightly overshoots, rebounds once, and settles into the starting pose.",
    "Use anticipation, asymmetric timing, curved paths, overlapping action, momentum, follow-through, and non-linear acceleration.",
    "Avoid terrestrial walking, planted stance, sliding, simultaneous limb strokes, mechanical symmetry, evenly paced interpolation, robotic stops, or frozen torso.",
  ];
  const prompt = sleepEnter
    ? "Front-view turtle slowly retracts her head, neck, and all four legs completely into her shell until only the closed shell remains. Static camera."
    : sleepLoop
    ? "Sleeping turtle remains fully retracted inside her shell as the shell drifts gently downward, pauses, then softly rises back to its exact starting position. Static camera."
    : sleepExit
    ? "Front-view turtle wakes and slowly extends her head, neck, and all four legs naturally out of her shell until she reaches the supplied alert resting pose. Static camera."
    : minimalAnimation
    ? minimalAnimationPrompts[minimalAnimation]
    : minimalSpark
    ? "Side-view turtle pauses while a thin golden geometric pulse of light travels once around the outer rim of its shell and fades. The turtle follows the moving light with a small smooth head turn. Static camera."
    : minimalRun || minimalFastRun
    ? minimalFastRun
      ? "Side-view turtle scuttles quickly from left to right with a slight forward lean and short rapid alternating steps. Each foot lifts, plants, and pushes against the ground. The low body carries the rigid shell smoothly. Camera tracks alongside at the same speed."
      : "Side-view turtle scuttles from left to right in short alternating steps. Each foot lifts, plants, and pushes against the ground. The low body moves steadily and the rigid shell follows the torso. Camera tracks alongside at the same speed."
    : [
      ...sharedPrompt,
      ...actionPrompt,
      "Fixed camera, scale, style, lighting, and solid #00FF00 background; no floor, scenery, shadows, text, cuts, or camera motion.",
    ].join(" ");
  if (prompt.length > 2500) throw new Error(`Kling prompt exceeds limit: ${prompt.length}`);

  const outputDir = path.join(characterDir, "animation", sleepEnter ? "sleep_enter_kling_keyframed_probe" : sleepLoop ? "sleep_loop_kling_keyframed_probe" : sleepExit ? "sleep_exit_kling_keyframed_probe" : minimalAnimation ? `${minimalAnimation}_kling_minimal_front_probe` : minimalSpark ? "spark_kling_minimal_prompt_probe" : minimalFastRun ? "run_kling_minimal_fast_prompt_probe" : minimalRun ? "run_kling_minimal_prompt_probe" : runCycle ? "run_kling_clean_side_cycle_probe" : "idle_kling_clean_character_motion_probe");
  fs.mkdirSync(outputDir, { recursive: true });
  const imageReference = {
    type: "image_url",
    image_url: { url: dataUrl(source) },
  };
  const lastImageReference = {
    type: "image_url",
    image_url: { url: dataUrl(lastSource) },
  };
  const env = loadEnvFileDefaults({ env: process.env, workspaceRoot: path.resolve(ROOT, "..") });
  const bridge = createVeoBridgeFromEnv({ env });
  const response = await bridge.submitVideo({
    payload: {
      model: env.KLING_MODEL || "kwaivgi/kling-v3.0-pro",
      prompt,
      frame_images: sleepEnter || sleepLoop || sleepExit
        ? [
          { ...imageReference, frame_type: "first_frame" },
          { ...lastImageReference, frame_type: "last_frame" },
        ]
        : minimalAnimation || minimalRun || minimalFastRun || minimalSpark
        ? [{ ...imageReference, frame_type: "first_frame" }]
        : [
          { ...imageReference, frame_type: "first_frame" },
          { ...imageReference, frame_type: "last_frame" },
        ],
      aspect_ratio: "1:1",
      duration: sleepEnter || sleepLoop || sleepExit || minimalAnimation || minimalRun || minimalFastRun || minimalSpark ? 4 : 6,
      generate_audio: false,
    },
    timeoutMs: 30000,
  });
  fs.writeFileSync(path.join(outputDir, "request.json"), `${JSON.stringify({ source, last_source: lastSource, prompt, prompt_length: prompt.length }, null, 2)}\n`);
  fs.writeFileSync(path.join(outputDir, "veo_job.json"), `${JSON.stringify(response.data, null, 2)}\n`);
  console.log(JSON.stringify({ status: "video-submitted", dir: outputDir, job: response.data.id }, null, 2));
}

main().catch((error) => {
  console.error(error.stack || error.message);
  process.exitCode = 1;
});
