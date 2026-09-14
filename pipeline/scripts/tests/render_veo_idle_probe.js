const fs = require("node:fs");
const path = require("node:path");
const { loadEnvFileDefaults } = require("../../../extensions/02_tools_and_apps/_shared/env_file.js");
const { createVeoBridgeFromEnv } = require("../../../extensions/01_bridges/veo/bridge.js");
const { createKieKlingBridgeFromEnv } = require("../../../extensions/01_bridges/kie_kling/bridge.js");

const ROOT = path.resolve(__dirname, "..", "..");
const characterDir = path.resolve(process.argv[2]);
const detailed = process.argv.includes("--detailed");
const characterContext = process.argv.includes("--character-context");
const freeMotion = process.argv.includes("--free-motion");
const choreographed = process.argv.includes("--choreographed");
const story = process.argv.includes("--story");
const shellThinking = process.argv.includes("--shell-thinking");
const constructWork = process.argv.includes("--construct-work");
const shortTurn = process.argv.includes("--short-turn");
const microIdle = process.argv.includes("--micro-idle");
const activeIdle = process.argv.includes("--active-idle");
const dynamicIdle = process.argv.includes("--dynamic-idle");
const driftIdle = process.argv.includes("--drift-idle");
const physicsContract = process.argv.includes("--physics-contract");
const useKie = process.argv.includes("--kie");
const lowResolution = process.argv.includes("--std");
const printPrompt = process.argv.includes("--print-prompt");
const animationId = process.argv.find((argument) => argument.startsWith("--animation="))?.slice("--animation=".length) || "idle";
const dataUrl = (file) => `data:image/${path.extname(file).slice(1) === "jpg" ? "jpeg" : path.extname(file).slice(1)};base64,${fs.readFileSync(file).toString("base64")}`;

const KLING_PHYSICS_CONTRACT = [
  "STRICT CHARACTER AND PHYSICS CONTRACT:",
  "Treat the supplied canonical views as different views of one immutable character, not as parts to blend.",
  "Keep one turtle with one head, one continuous neck, one rigid domed shell, and exactly four legs attached to stable anatomical locations.",
  "The shell may rotate but must never flex, breathe, melt, split, or move independently from the torso.",
  "Joints may articulate naturally; body parts must not stretch, swap sides, merge, duplicate, disappear, or pass through the shell.",
  "Keep exactly three existing picture cards together in the same front foot, with constant size, shape, order, and attachment.",
  "Perform only the requested primary action with continuous motion and physically plausible acceleration and deceleration.",
  "Keep character scale, camera, orthographic-like widget framing, lighting, palette, linework, and solid #00FF00 background constant; allow only bounded in-frame character translation explicitly requested by the action.",
  "No camera motion, cuts, zoom, perspective drift, floor, scenery, cast shadow, particles, text, or newly invented objects.",
].join(" ");

const KLING_COMPACT_PHYSICS_CONTRACT = [
  "PHYSICS LOCK:",
  "One immutable turtle: one head, one articulated neck, one rigid shell and torso, exactly four consistently attached legs.",
  "No stretching, merging, duplication, disappearing parts, shell flex, or limb intersections.",
  "Keep three cards as one unchanged rigid fan in the same front foot.",
  "Fixed camera, scale, style, lighting, and solid #00FF00 background; no floor, scenery, shadows, cuts, or new objects.",
].join(" ");

async function main() {
  const env = loadEnvFileDefaults({ env: process.env, workspaceRoot: path.resolve(ROOT, "..") });
  const animation = JSON.parse(fs.readFileSync(path.join(ROOT, "dict", "animations.json"), "utf8")).entries.find((entry) => entry.id === animationId);
  if (!animation) throw new Error(`Unknown animation: ${animationId}`);
  const canonical = JSON.parse(fs.readFileSync(path.join(characterDir, "canonical", "manifest.json"), "utf8"));
  const primaryData = JSON.parse(fs.readFileSync(path.join(characterDir, "primary_data.json"), "utf8"));
  const lore = JSON.parse(fs.readFileSync(path.join(characterDir, "lore.json"), "utf8"));
  const movementContact = Array.isArray(lore.body_profile?.movement_contact) ? lore.body_profile.movement_contact : [];
  const speciesMotionProfile = [
    `ANIMAL TYPE: ${primaryData.selected?.subject_request || lore.identity?.subject || "unknown living creature"}.`,
    `NATURAL LOCOMOTION AND CONTACT RULES: ${movementContact.join(" ") || "Use locomotion natural to this animal's canonical body."}`,
  ].join(" ");
  const duration = activeIdle || dynamicIdle || driftIdle ? 6 : shortTurn || microIdle ? 4 : 10;
  let prompt = driftIdle && animationId === "idle"
    ? "Silent seamless 6-second hover-idle loop, fixed front camera, same exact first and last frame. Klara floats behind the widget glass; there is no floor. An unexpected sideways drift starts. She reacts with a quick head turn and two staggered paddling strokes, not terrestrial steps. Her rigid shell and torso travel together along a shallow 12%-shell-width arc with visible mass and delayed inertia. Her neck counterbalances in an opposing arc. At the apex the empty front foot briefly braces against the invisible glass to brake; never render the glass. She decelerates, slightly overshoots, softly rebounds once, and naturally settles. The card-holding foot keeps all three cards rigidly grouped and unchanged. Use anticipation, asymmetric timing, curved paths, overlapping action, momentum, follow-through, and non-linear acceleration. No planted stance, sliding, simultaneous strokes, mechanical symmetry, evenly paced interpolation, robotic stops, or frozen torso."
    : dynamicIdle && animationId === "idle"
    ? "Silent 6-second seamless dynamic idle loop in the fixed supplied front view. Use the supplied image as the exact first and last frame, but create energetic organic motion between them. Klara notices a route detail in her cards, makes a quick anticipatory head pullback, then drives her whole body diagonally toward the cards along a shallow curved path by about 12 percent of her shell width. The free front leg reaches first, the shell and torso follow as one heavy rigid mass a fraction later, and the rear legs make coordinated balancing steps. Her articulated neck counterbalances in a smooth opposing arc and her gaze stays on the cards. At the apex she catches herself in a clear asymmetric pose for a brief beat, then pushes back with a small natural rebound and settles into the starting pose. The three opened cards travel only with the holding front foot as one rigid fan; their folds, symbols, order, and spacing never change. Use strong anticipation, curved motion paths, unequal timing, overlapping action, weight, momentum, soft acceleration, follow-through, and a lively settle. Avoid evenly paced pose interpolation, mechanical symmetry, robotic stops, frozen limbs, sliding feet, or simultaneous movement of every body part."
    : activeIdle && animationId === "idle"
    ? "Silent 6-second seamless active-idle loop in the fixed supplied front view. Use the supplied image as both the exact first and exact last frame. Klara performs one clearly visible but controlled inspection action. First she bends and extends her articulated neck toward the three picture cards by about 15 percent of her shell width, turns her head and gaze directly toward them, and pauses. While looking, she lifts the empty front leg slightly in one deliberate attentive gesture; the other three legs remain planted and unchanged. She then lowers that leg, bends her neck naturally back, and settles into the exact initial pose. Her rigid shell and torso do not rotate, squash, or translate. All three opened cards remain completely stationary, continuously held together by the same front foot, and keep their exact symbols and folds. Use smooth ease-in, a readable held apex, and smooth ease-out. No blink-only interpretation, tiny micro-motion, body turn, walking, or secondary action."
    : microIdle && animationId === "idle"
    ? "Silent 4-second seamless micro-idle loop. Use the supplied front view as both the exact first and exact last frame. Klara remains fixed in place: her shell, torso, all four legs, and all three opened picture cards stay completely still and retain identical pixels, topology, and placement. Only her head performs one tiny natural attentive tilt toward the cards while her eyes make one soft blink, then the head returns smoothly to the exact initial pose. No body turn, walking, leg motion, card motion, secondary action, or anticipation."
    : shortTurn && animationId === "thinking"
    ? "Silent 4-second image-to-video clip. The canonical turtle begins in the supplied front-view state and ends exactly in the supplied side-view state. Klara performs one slow deliberate body turn to recalibrate her heading before a manoeuvre: her shell rotates as one rigid body, her neck follows naturally, and all four legs make small coordinated steering movements. Preserve the turtle's exact anatomy, shell, palette, style, prop continuity, fixed camera, miniature scale, and green background. No morphing, extra limbs, missing limbs, new objects, text, shadows, cuts, or camera movement."
    : shellThinking && animationId === "thinking"
    ? "Create a 10-second silent thinking animation from the attached canonical turtle anchors. Tell one clear character story: Klara needs to work out a difficult turn. 0–2s: she carefully stows her three picture cards under the edge of her shell so her hands are free. 2–5s: she retracts her head and all four legs safely inside the shell, pauses in compact thought, then makes one deliberate gentle rock and partial roll on the rounded shell. 5–8s: she rolls back upright, extending all four legs together to stabilise herself. 8–10s: she raises her head with a clear expression of having found the answer and settles in a calm ready pose. Preserve exactly one turtle with one head, one neck, one shell, and exactly four legs throughout: continuous rigid body geometry, no stretching, morphing, extra or missing limbs, duplicate parts, or anatomy changes. The cards are simply stowed beneath the shell and must not float, duplicate, or become the subject. Preserve the canonical anchors' palette, style, miniature scale, centered framing, and solid green background. No superpower, camera movement, cuts, scenery, text, UI, shadows, extra creatures, or new objects."
    : constructWork && animationId === "work"
      ? "Create a 10-second silent work animation from the attached canonical turtle anchors. Tell one clear character story: Klara must achieve a precise new orientation for her navigation exercise. 0–2s: she stows her picture cards beneath the edge of her shell and studies the intended direction. 2–5s: she creates a simple solid hard-light construction — a clean geometric turning frame around the shell, with two visible inner support surfaces. 5–8s: she presses her four legs against the supports and uses the frame to make one controlled quarter-turn, visibly solving the orientation problem. 8–10s: the useful construction dissolves after the turn is complete; Klara holds the new precise heading in a stable, quietly satisfied ready pose. Preserve exactly one turtle with one head, one neck, one shell, and exactly four legs throughout: continuous rigid body geometry, no stretching, morphing, extra or missing limbs, duplicate parts, or anatomy changes. The cards are only stowed beneath the shell and must not float, duplicate, or become the subject. Preserve the canonical anchors' palette, style, miniature scale, centered framing, and solid green background. No camera movement, cuts, scenery, text, UI, shadows, extra creatures, or new objects besides the temporary hard-light construction."
      : story && animationId === "thinking"
    ? "Create a 10-second silent thinking animation from the attached canonical turtle anchors. Tell one clear, self-contained story about Klara finding the correct orientation before a difficult manoeuvre. 0–2s: she pauses, lifts her head, and looks uncertainly to one side while her picture cards remain an incidental held item. 2–5s: she begins her distinctive slow full-body rotation to think; her four legs steer the turn as one coherent turtle body. 5–7s: a brief bright geometric hard-light arc appears around the rim of her shell, visually revealing the precise heading she has worked out. 7–10s: Klara completes the turn, lines up with the arc, the light fades, and she settles into a calm confident ready pose, having found the answer. Preserve exactly one turtle with one head, one neck, one shell, and exactly four legs at every moment: continuous rigid body geometry, no stretching, morphing, extra or missing limbs, duplicate parts, or anatomy changes. Preserve the canonical anchors' palette, style, miniature scale, centered framing, and solid green background. The cards do not become the subject and remain in their canonical placement. No camera movement, cuts, scenery, text, UI, shadows, extra creatures, or new objects."
    : choreographed && animationId === "work"
    ? "Create a 10-second silent work animation from the attached canonical turtle anchors. Tell one clear, connected miniature navigation action. 0–2s: Klara begins stable, holding her three folding picture cards together beside her shell and studying their route. 2–4s: exactly one existing card glides a short straight distance forward; Klara extends her neck and turns her shell toward it, steering with her four legs. 4–6s: a narrow bright geometric hard-light guide grows from her shell, catches the moving card’s path, and curves it back. 6–8s: Klara makes one controlled turn and brake while the guide returns that card beside the other two. 8–10s: the guide fades; all three cards are again aligned in the same front foot, and Klara pauses in a clear stable working pose. Preserve exactly one turtle with one head, one neck, one shell, and exactly four legs throughout: continuous rigid body geometry, no stretching, morphing, extra limbs, missing limbs, duplicated parts, or anatomy changes. Preserve the canonical anchors' palette, style, miniature scale, centered framing, and solid green background. Only the three existing cards and the brief hard-light guide may move; no camera movement, cuts, scenery, text, UI, shadows, extra creatures, or new objects."
    : freeMotion && animationId === "work"
    ? "Create a 10-second silent work animation from the attached canonical turtle anchors. Klara is practising her precise route exercise: one of her three folding picture cards slips forward; she actively follows it with natural full-body movement, turning her shell, extending her neck, and using all four legs to steer and brake. A short bright geometric hard-light guide grows from her shell or front foot, catches the card's path, and helps her bring it back into the sequence. Let the body movement and timing be expressive, connected, and inventive rather than restrained. Finish in a stable, satisfied working pose; the ending does not need to match the starting pose. Preserve one and only one Klara with the exact anatomy, shell, colours, miniature scale, style, and solid green background from the canonical anchors. The three existing cards may move only as part of this action; do not create, remove, duplicate, or transform them. No camera movement, cuts, scenery, text, UI, shadows, or extra creatures."
    : detailed
    ? animationId === "curious"
      ? "Create a 10-second silent seamless curious loop from the attached canonical turtle anchors. Klara remains in place and studies the picture cards she already holds: she slowly extends her neck, tilts her head slightly toward the cards, pauses with focused curiosity, then smoothly returns to the exact initial pose. The three cards remain continuously held together, still and unchanged, in the same front foot beside her shell; they never float, detach, duplicate, open, or change hands. The final frame exactly returns to the initial pose, prop arrangement, position, and orientation. Locked: one turtle, exactly the same anatomy, shell, palette, style, scale, centered framing, and solid green background. No camera movement, cuts, new objects, text, shadows, or changes to the character."
      : animationId === "work"
        ? "Create a 10-second silent seamless work loop from the attached canonical turtle anchors. Klara practices a precise miniature manoeuvre: while staying in place, she extends her neck to inspect the picture cards she holds, then a short bright geometric hard-light guide appears from the edge of her shell and traces one small arc toward the cards. She calmly turns her body a little to follow the guide, lets the guide fade, and smoothly returns to the exact initial pose. The three cards remain continuously held together in the same front foot beside her shell; they never float, detach, duplicate, open, or change hands. The final frame exactly returns to the initial pose, prop arrangement, position, and orientation. Locked: one turtle, exactly the same anatomy, shell, palette, style, scale, centered framing, and solid green background. No camera movement, cuts, new objects, text, shadows, or changes to the character."
      : "Create a 10-second silent seamless idle loop from the attached canonical turtle anchors. Klara remains in place. She makes one calm, small motion: slowly extends her neck to look at the picture cards, while the three cards make a small synchronized fan-open and fan-close. The cards stay continuously held together in the same front foot beside her shell; they never float, detach, duplicate, or change hands. The final frame exactly returns to the initial pose, prop arrangement, position, and orientation. Locked: one turtle, exactly the same anatomy, shell, palette, style, scale, centered framing, and solid green background. No camera movement, cuts, new objects, text, shadows, or changes to the character."
    : `Animate the attached canonical turtle character as a silent seamless idle loop. ${animation.function} ${animation.expected} ${animation.physics} Keep exactly the same character, anatomy, props, style, scale, centered framing, and solid green background shown in the anchors.`;
  if (characterContext) {
    prompt += " Character context, for motion semantics only: Klara is a serious, cheerful, restless teenage turtle who practises precise miniature navigation routes. Her job is to arrange three folding picture cards into a silent route, then complete a sequence of controlled pushes, turns, and braking at an exact orientation. Her signature habit is to send a card slightly ahead and catch it at the right moment. Her power creates a brief bright geometric guide or grab of hard light from her shell or front foot, allowing her to control a moving card and her own turning at once. Treat the canonical anchors as absolute authority for her appearance, anatomy, prop placement, framing, and style; this context must not add scenery, steam, objects, clothing, or body parts.";
  }
  if (physicsContract) {
    prompt = `${driftIdle ? KLING_COMPACT_PHYSICS_CONTRACT : KLING_PHYSICS_CONTRACT} ${prompt}`;
  }
  if (driftIdle) {
    prompt = `${speciesMotionProfile} ${prompt}`;
  }
  if (prompt.length > 2500) {
    throw new Error(`Kling prompt exceeds the 2500-character limit: ${prompt.length}`);
  }
  if (printPrompt) {
    console.log(JSON.stringify({ prompt, length: prompt.length }, null, 2));
    return;
  }
  const contractSuffix = physicsContract ? "_physics_contract" : "";
  const dir = path.join(characterDir, "animation", useKie ? `${animationId}_kie_element_${lowResolution ? "std" : "pro"}${contractSuffix}_probe` : driftIdle ? `${animationId}_kling_drift_idle${contractSuffix}_probe` : dynamicIdle ? `${animationId}_kling_dynamic_idle${contractSuffix}_probe` : activeIdle ? `${animationId}_kling_active_idle${contractSuffix}_probe` : microIdle ? `${animationId}_kling_micro_idle${contractSuffix}_probe` : shortTurn ? `${animationId}_kling_short_turn${contractSuffix}_probe` : shellThinking ? `${animationId}_kling_shell_thinking${contractSuffix}_probe` : constructWork ? `${animationId}_kling_construct_work${contractSuffix}_probe` : story ? `${animationId}_kling_story${contractSuffix}_probe` : choreographed ? `${animationId}_kling_choreographed${contractSuffix}_probe` : freeMotion ? `${animationId}_kling_free_motion${contractSuffix}_probe` : detailed ? `${animationId}_kling_${characterContext ? "character_context" : "directed"}${contractSuffix}_probe` : `${animationId}_kling_minimal${contractSuffix}_probe`);
  fs.mkdirSync(dir, { recursive: true });
  let response;
  if (useKie) {
    const kie = createKieKlingBridgeFromEnv({ env });
    const uploadPath = `pet-generation/${path.basename(characterDir)}/${Date.now()}`;
    const uploadedUrls = [];
    for (const [index, anchor] of canonical.anchors.entries()) {
      const uploaded = await kie.uploadAsset({
        payload: {
          base64Data: dataUrl(anchor),
          uploadPath,
          fileName: `canonical_${String(index).padStart(2, "0")}.jpg`,
        },
        timeoutMs: 120000,
      });
      uploadedUrls.push(uploaded.data.fileUrl);
    }
    const elementName = "klara";
    const motionPrompt = `@${elementName}. ${prompt.replace(/from the attached canonical turtle anchors/gi, "").replace(/The canonical turtle/g, "The character")}`;
    response = await kie.submitVideo({
      payload: {
        prompt: motionPrompt,
        duration,
        aspectRatio: "1:1",
        mode: lowResolution ? "std" : "pro",
        sound: false,
        imageUrls: shortTurn ? [uploadedUrls[0], uploadedUrls[1]] : [uploadedUrls[0]],
        element: {
          name: elementName,
          description: "Klara, a miniature turtle character.",
          imageUrls: uploadedUrls,
        },
      },
      timeoutMs: 30000,
    });
    fs.writeFileSync(path.join(dir, "kie_assets.json"), `${JSON.stringify({ uploadPath, elementName, uploadedUrls }, null, 2)}\n`);
    prompt = motionPrompt;
  } else {
    const veo = createVeoBridgeFromEnv({ env });
  const imageReference = (file, frame_type) => ({ type: "image_url", image_url: { url: dataUrl(file) }, ...(frame_type ? { frame_type } : {}) });
  const frameImages = shortTurn
    ? [imageReference(canonical.anchors[0], "first_frame"), imageReference(canonical.anchors[1], "last_frame")]
    : choreographed || story || shellThinking || constructWork
      ? [imageReference(canonical.anchors[0], "first_frame")]
    : [imageReference(canonical.anchors[0], "first_frame"), imageReference(canonical.anchors[0], "last_frame")];
    const inputReferences = microIdle || activeIdle || dynamicIdle || driftIdle ? [] : canonical.anchors.slice(2).map((file) => imageReference(file));
    response = await veo.submitVideo({ payload: { model: process.env.KLING_MODEL || "kwaivgi/kling-v3.0-pro", prompt, frame_images: frameImages, input_references: inputReferences, aspect_ratio: "1:1", duration, generate_audio: false }, timeoutMs: 30000 });
  }
  fs.writeFileSync(path.join(dir, "request.json"), `${JSON.stringify({ prompt, frame_count: canonical.anchors.length }, null, 2)}\n`);
  fs.writeFileSync(path.join(dir, useKie ? "kie_job.json" : "veo_job.json"), `${JSON.stringify(response.data, null, 2)}\n`);
  console.log(JSON.stringify({ status: "video-submitted", dir, job: response.data.id || response.data }, null, 2));
}
main().catch((error) => { console.error(error); process.exitCode = 1; });
