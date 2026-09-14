package com.generativemascot.app.data

import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val contractJson = Json { prettyPrint = true }

/**
 * A compact, on-device equivalent of the writer -> character designer ->
 * animation director pipeline. The deal is deterministic for one mascot id,
 * so every paid request receives the same identity and motion vocabulary even
 * after process death or a resumed WorkManager job.
 */
@Serializable
internal data class BodyProfile(
    val archetype: String = "creature",
    val subject: String,
    val visibleParts: String,
    val movementContacts: String,
    val front: String,
    val scale: String,
)

@Serializable
internal data class VisualSignature(
    val primarySilhouetteCue: String,
    val secondaryReadableDetail: String,
    val bodyLanguage: String,
)

@Serializable
internal data class BehaviorProfile(
    val motivation: String,
    val worldview: String,
    val movementSignature: String,
    val recurringMannerism: String,
    val idlePerformance: String,
    val joyfulPerformance: String,
    val sleepPerformance: String,
    val dancePerformance: String,
)

@Serializable
internal data class MobileCharacterContract(
    val contract: String = "pet-generator.mobile-character.v5",
    // Nullable keeps contracts saved by older APKs distinguishable from an
    // intentional seed=0 contract. Old heroes then fall back to their legacy
    // mascot-id seed instead of silently changing their motion personality.
    val seed: Int? = null,
    val fingerprint: String,
    val body: BodyProfile,
    val visualSignature: VisualSignature,
    val behavior: BehaviorProfile,
    val palette: String,
    val materialAndTechnique: String,
    val faceSystem: String,
    val plasticity: String,
)

@Serializable
internal data class MobileAnimationContract(
    val contract: String = "pet-generator.mobile-animation.v5",
    val action: String,
    val sourceStrategy: String = "canonical_direct",
    val actionGoal: String,
    val beats: List<String>,
    val tempo: String,
    val gaze: String,
    val secondaryMotion: String,
    val protectedInvariants: List<String>,
    val performanceFreedom: List<String>,
    val closure: String,
    val enterSeconds: Double,
    val loopStartSeconds: Double,
    val loopEndSeconds: Double,
    val exitSeconds: Double,
)

private data class SubjectTemplate(
    val subject: String,
    val visibleParts: String,
    val movementContacts: String,
    val front: String,
    val archetype: String = "creature",
)

private data class BehaviorTemplate(
    val motivation: String,
    val worldview: String,
    val movement: String,
    val mannerism: String,
    val idle: String,
    val joyful: String,
    val sleep: String,
    val dance: String,
)

private val subjects = listOf(
    SubjectTemplate(
        "an unfamiliar fox-dragon creature",
        "one head, one torso, exactly two pointed ears, two small horns, two arms, two legs and one long tail",
        "torso, neck, ears, arms, legs and tail",
        "the face, chest and inner ear shapes define the front",
    ),
    SubjectTemplate(
        "an axolotl-cat spirit",
        "one head, one torso, two eyes, three external gill branches on each side, two forelegs, two hindlegs and one broad tail",
        "torso, gill branches, four legs and tail",
        "the face and paired gills define the front",
    ),
    SubjectTemplate(
        "a cloud-raccoon creature",
        "one head, one torso, two rounded ears, two arms, two legs and one ring-shaped tail",
        "torso, ears, arms, legs and tail",
        "the facial mask and belly define the front",
    ),
    SubjectTemplate(
        "a miniature moon bear",
        "one head, one torso, exactly two round ears, two arms, two legs and one short tail",
        "torso, ears, arms, legs and tail",
        "the muzzle and chest mark define the front",
    ),
    SubjectTemplate(
        "a living cactus-puppy creature",
        "one head, one torso, two soft cactus-pad ears, two forelegs, two hindlegs and one short curved tail; every spine is blunt and integrated into the body surface",
        "torso, ears, four legs and tail",
        "the muzzle and chest plane define the front",
    ),
    SubjectTemplate(
        "a star-otter spirit",
        "one head, one flexible torso, two small ears, two arms, two legs and one thick tapered tail",
        "torso, ears, arms, legs and tail",
        "the face and lighter chest plane define the front",
    ),
    SubjectTemplate(
        "a moth-kitten creature",
        "one head, one torso, two ears, two antennae, exactly one pair of folded wings, two forelegs, two hindlegs and one tail",
        "torso, ears, antennae, wings, four legs and tail",
        "the face, antennae and folded wing overlap define the front",
    ),
    SubjectTemplate(
        "a capybara-like household spirit",
        "one large head-and-torso mass, two small ears, two forelegs, two hindlegs and one tiny tail",
        "torso, ears, four legs and tail",
        "the broad muzzle and chest define the front",
    ),
    SubjectTemplate(
        "a jellybean dinosaur",
        "one head, one torso, two arms, two legs, one continuous row of back plates and one heavy tail",
        "torso, arms, legs, back plates and tail",
        "the face and belly define the front",
    ),
    SubjectTemplate(
        "a tiny tapir-bird chimera",
        "one head, one torso, one short flexible snout, two leaf-shaped ears, one pair of folded wings, two legs and one short tail",
        "torso, snout, ears, folded wings, legs and tail",
        "the snout, eyes and chest define the front",
    ),
    SubjectTemplate(
        "a gentle pebble salamander",
        "one head, one low torso, four short legs, one rounded dorsal ridge and one broad tail",
        "torso, four legs, dorsal ridge and tail",
        "the face and chest edge define the front",
    ),
    SubjectTemplate(
        "a tiny owl-rabbit creature",
        "one head, one torso, two long feathered ears, one pair of folded wings, two feet and one small tail fan",
        "torso, ears, folded wings, feet and tail fan",
        "the facial disc and breast define the front",
    ),
    SubjectTemplate(
        "a small original samurai guardian in historically respectful lamellar-inspired armor, empty-handed and without a weapon or real clan symbol",
        "one head, one torso, exactly two arms, two hands, two legs and two feet; one compact helmet, two short helmet cords and every armor plate form an attached, coherent costume",
        "torso, neck, arms, hands, legs, helmet cords and attached armor plates",
        "the face opening, chest armor overlap and tied waist define the front",
        archetype = "humanoid",
    ),
    SubjectTemplate(
        "a pocket-sized wandering star mage with an original face and a soft integrated hood",
        "one head, one torso, exactly two arms, two hands, two legs and two feet; one hood and one short back mantle remain attached to the costume, with no wand or loose accessory",
        "torso, neck, arms, hands, legs, hood edge and attached mantle",
        "the face opening, chest seam and mantle overlap define the front",
        archetype = "humanoid",
    ),
    SubjectTemplate(
        "a tiny original ceremonial knight with a friendly uncovered face and compact rounded armor",
        "one head, one torso, exactly two arms, two hands, two legs and two feet; one raised visor and every rounded armor plate are attached, with no weapon, shield or loose prop",
        "torso, neck, arms, hands, legs, visor and attached armor plates",
        "the uncovered face, breastplate center and belt line define the front",
        archetype = "humanoid",
    ),
    SubjectTemplate(
        "a miniature deep-sea navigator in a whimsical sealed exploration suit",
        "one helmeted head, one torso, exactly two sleeved arms, two gloved hands, two legs and two weighted boots; the round helmet and compact air unit are integrated into one suit",
        "torso, helmet, arms, hands, legs, boots and attached air unit",
        "the face window, chest panel and boot toes define the front",
        archetype = "humanoid",
    ),
    SubjectTemplate(
        "a tiny original masked festival performer with the mask lifted above a clearly visible face",
        "one head, one torso, exactly two arms, two hands, two legs and two feet; one small lifted mask, a short attached sash and two sleeve panels belong to the costume, with nothing held",
        "torso, neck, arms, hands, legs, sleeves, lifted mask and attached sash",
        "the visible face, crossed collar and sash knot define the front",
        archetype = "humanoid",
    ),
    SubjectTemplate(
        "a friendly retro service robot with a rounded monitor-like head",
        "one head shell with one face display, one torso shell, exactly two segmented arms, two simple hands, two segmented legs and two broad feet; no antenna or cable is detached",
        "torso shell, neck joint, arms, hands, legs and feet",
        "the face display, chest indicator and forward toe plates define the front",
        archetype = "robot",
    ),
    SubjectTemplate(
        "a miniature clockwork astronaut automaton",
        "one domed head, one torso, exactly two articulated arms, two mitten-like hands, two articulated legs and two boots; one small winding key and one compact life-support shell are permanently integrated into the back",
        "torso, dome, arms, hands, legs, boots, attached winding key and back shell",
        "the face plate, chest dial and boot fronts define the front",
        archetype = "robot",
    ),
    SubjectTemplate(
        "a cheerful gardening automaton grown around a single mechanical body",
        "one head, one torso pot-shell, exactly two vine-wrapped mechanical arms, two hands, two legs and two feet; three soft leaves form an attached head crest and no tool or separate plant is present",
        "torso shell, arms, hands, legs, feet and attached leaf crest",
        "the face panel, chest seam and front leaf define the front",
        archetype = "robot",
    ),
    SubjectTemplate(
        "a living pocket arcade cabinet redesigned as a compact expressive character",
        "one cabinet-shaped head-and-torso body, one face screen, exactly two flexible arms, two mitten hands, two short legs and two broad feet; all controls are flush integrated details",
        "cabinet body, face screen, arms, hands, legs and feet",
        "the face screen, two flush buttons and toe direction define the front",
        archetype = "object",
    ),
    SubjectTemplate(
        "a porcelain tea guardian whose vessel shape is its complete living body",
        "one rounded vessel torso with one face, exactly one attached handle, one attached short spout, two arms, two hands, two legs and two feet; the fitted lid is part of the head and never separates",
        "vessel torso, fitted lid, attached handle, attached spout, arms, hands, legs and feet",
        "the face, spout direction and painted chest motif define the front",
        archetype = "object",
    ),
    SubjectTemplate(
        "a living pocket watch character with a warm mechanical face",
        "one circular case forming head and torso, one face within the dial, exactly two arms, two hands, two legs and two feet; one crown and a short loop are permanently attached, with no chain",
        "circular case, crown, attached loop, arms, hands, legs and feet",
        "the dial face, crown offset and forward feet define the front",
        archetype = "object",
    ),
    SubjectTemplate(
        "a walking storybook spirit with expressive page-like eyebrows",
        "one closed book body with one face, one flexible spine, exactly two arms, two hands, two legs and two feet; both covers and all page edges remain one closed coherent body",
        "book body, flexible spine, cover corners, arms, hands, legs and feet",
        "the face on the front cover, spine side and page edge define the front",
        archetype = "object",
    ),
    SubjectTemplate(
        "a gentle compact stone golem assembled as one continuous living sculpture",
        "one head, one torso, exactly two heavy arms, two hands, two legs and two feet; a small shoulder ridge and all stone plates are fused by visible glowing joints with no floating pieces",
        "torso, neck, arms, hands, legs, feet and fused shoulder ridge",
        "the face carving, chest core and forward knuckles define the front",
        archetype = "construct",
    ),
    SubjectTemplate(
        "a tiny candle-flame household spirit with a wax body and a living flame crest",
        "one wax head-and-torso body, one face, exactly two soft arms, two hands, two short legs and two feet; one flame crest and one wax-drip collar stay continuously attached",
        "wax torso, arms, hands, legs, feet, attached flame crest and wax collar",
        "the face, front wax drip and foot direction define the front",
        archetype = "spirit",
    ),
    SubjectTemplate(
        "a compact raincloud keeper made from one coherent soft cloud mass",
        "one cloud head-and-torso body, one face, exactly two misty arms, two hands, two short legs and two feet; one small rainbow-like crest is embedded in the upper silhouette",
        "cloud torso, arms, hands, legs, feet and embedded crest",
        "the face, chest vapor curl and foot direction define the front",
        archetype = "spirit",
    ),
    SubjectTemplate(
        "an original living ink spirit shaped like a small calligrapher without tools",
        "one fluid head, one compact torso, exactly two tapering arms, two readable hands, two legs and two feet; one attached ink-splash crest remains part of the silhouette and no droplets float separately",
        "fluid torso, arms, hands, legs, feet and attached splash crest",
        "the face, chest stroke and heavier forward foot define the front",
        archetype = "spirit",
    ),
    SubjectTemplate(
        "a tiny mushroom librarian spirit with no book or loose accessory",
        "one head beneath one attached mushroom cap, one torso, exactly two arms, two hands, two legs and two feet; two small cap notches and a short collar are integrated into the body",
        "torso, attached cap, arms, hands, legs, feet and collar",
        "the face, cap tilt and collar opening define the front",
        archetype = "spirit",
    ),
    SubjectTemplate(
        "a small constellation ghost with a solid readable body rather than transparent vapor",
        "one rounded head, one torso tapering into exactly two feet, exactly two arms and two hands; three embedded star points form one attached crown-like crest with no orbiting pieces",
        "torso, arms, hands, feet and attached star crest",
        "the face, chest constellation and brighter forward edge define the front",
        archetype = "spirit",
    ),
)

private val behaviors = listOf(
    BehaviorTemplate(
        "It wants to appear self-sufficient but quietly checks whether its effort was noticed.",
        "The world is a sequence of small tests worth rehearsing before committing.",
        "A short preparatory pause releases into one confident elastic phrase, then settles from the silhouette inward.",
        "Before an important action it performs one tiny private readiness ritual.",
        "Slow breathing, one cautious glance, a nearly invisible rehearsal of the next movement, then a quiet return to attention.",
        "It tries to remain dignified, lets delight ripple through the whole body, then regains composure after one silent beat.",
        "It carefully arranges its whole body into a balanced sleeping shape, exhales, keeps slow breathing with one small dream twitch, then wakes by reversing the settling path.",
        "A precise two-step rhythm loosens into one surprising turn and resolves carefully on its original mark.",
    ),
    BehaviorTemplate(
        "It assumes everything nearby might be a game whose rules have not been explained yet.",
        "The world becomes understandable through one tiny experiment followed by serious observation.",
        "Movement begins in investigative pulses and gathers buoyant follow-through without losing balance.",
        "It repeats the smallest fragment of a movement once, as if confirming a discovery.",
        "Gentle breathing, a wandering look, one tiny exploratory lean, then enough stillness to feel attentive rather than frozen.",
        "Attention causes a surprised pause, a delighted whole-body answer and one curious confirming look.",
        "It fights sleep with two progressively smaller attempts to stay alert, settles into deep breathing and wakes after one curious twitch.",
        "A syncopated dance tests one direction, answers it in the other and resolves into a proud balanced finish.",
    ),
    BehaviorTemplate(
        "It believes ordinary moments deserve a little ceremony, although sincere excitement can interrupt the performance.",
        "The world is a small stage, and it is both performer and exacting audience.",
        "Deliberate anticipation leads to a theatrical release and a slightly late, soft secondary follow-through.",
        "It marks completed actions with one restrained self-approving accent.",
        "Measured breathing, one miniature presentation gesture, a composed look and a satisfied reset.",
        "It receives attention formally, loses composure in one warm flourish and neatly restores its home pose.",
        "It arranges a ceremonious bedtime shape, softens into steady breathing, makes one tiny dream-performance and wakes with decorum.",
        "A compact theatrical phrase has a clear opening, playful escalation and elegant recovery without copying a known dance.",
    ),
    BehaviorTemplate(
        "It is methodical until one small imperfection captures all of its attention.",
        "The world can almost, but never quite, be made perfectly orderly.",
        "Motion is economical and balanced, with tiny corrective offsets and soft controlled landings.",
        "After an action seems finished, it makes one unobtrusive symmetry correction.",
        "Even breathing, one small balance correction, a precise glance and an unhurried return to alignment.",
        "It accepts attention, corrects its balance and then permits one measured wave of happiness to break the symmetry.",
        "It adjusts its resting pose twice, finds balance, breathes in a slow even cycle and wakes with one precise stretch.",
        "A geometric side-to-side phrase gradually breaks its symmetry and then resolves exactly at center.",
    ),
    BehaviorTemplate(
        "It prefers comfort to achievement and is delighted whenever a task can become a pleasant pause.",
        "The world is best understood through texture, warmth and the relief of not hurrying.",
        "Motion starts reluctantly, travels with soft weight and ends in a long contented exhale.",
        "It briefly becomes very still whenever it is especially pleased.",
        "Deep easy breathing, a comfortable weight shift, one half-finished stretch and a soft return to rest.",
        "It leans into attention, absorbs the pleasure through the entire body and settles with a visibly warmer expression.",
        "It melts gradually into its favorite compact pose, breathes heavily and continuously, then wakes through one luxurious stretch.",
        "A lazy groove begins almost by accident, grows into two rounded weighted steps and ends in a pleased soft collapse to center.",
    ),
    BehaviorTemplate(
        "It keeps watch for the possibility that something interesting may happen, without demanding that it does.",
        "The world is full of almost-events, and noticing them is satisfying on its own.",
        "The gaze leads first, the body follows a fraction later and secondary parts settle last.",
        "It listens with one side of the silhouette before the rest of the body responds.",
        "Quiet breathing, two differently directed listening poses and a slow return to open attention.",
        "It recognizes attention, listens closely, answers with one bright physical accent and remains curious after settling.",
        "It listens until the head grows heavy, folds into sleep without losing the listening shape and breathes until deliberately awakened.",
        "An alert call-and-response rhythm travels from gaze to torso to feet, then returns through the same chain in reverse.",
    ),
)

private val silhouetteCues = listOf(
    "a compact pear silhouette with a stable broad base",
    "a soft asymmetric bean silhouette with one strong directional sweep",
    "two contrasting rounded masses joined into one unmistakable silhouette",
    "a low wide silhouette that can compress and unfurl without losing identity",
    "a tapered teardrop silhouette with clear negative space around every appendage",
    "a compact athletic silhouette with one memorable off-center rhythm",
    "a rounded triangular silhouette whose widest point sits below the face",
    "a soft upright crescent silhouette balanced by one heavier secondary mass",
)

private val readableDetails = listOf(
    "one small crescent chest marking offset from center",
    "three freckles forming a short diagonal under one eye",
    "one contrasting blunt tip on an existing silhouette feature",
    "a single asymmetric cheek patch with a clean outer edge",
    "one small star-shaped forehead marking",
    "a narrow belly mark shaped like a rounded droplet",
    "one eyebrow-like marking above only the left eye",
    "two tiny aligned dots on the upper chest",
    "one darker ear, crest, hood or helmet tip that belongs to the existing design",
    "a small heart-shaped central face accent used as the only symbolic detail",
)

private val bodyLanguages = listOf(
    "slightly forward attention with weight grounded through the lower body",
    "upright composure interrupted by one relaxed asymmetric shoulder or flank",
    "a low comfortable center of gravity and an observant head angle",
    "quiet readiness with the torso gently compressed before movement",
    "open curiosity led by the eyes while the body remains securely balanced",
    "measured self-confidence with one side settling a fraction later than the other",
    "soft contained energy held in a compact neutral pose",
    "calm alertness with clear space between all movement-capable parts",
)

private val palettes = listOf(
    "deep violet, warm golden yellow and one restrained coral accent",
    "burnt orange, midnight navy and warm ivory",
    "raspberry pink, dark burgundy and muted apricot",
    "cobalt blue, plum and a small lemon-yellow accent",
    "warm caramel, charcoal brown and dusty rose",
    "brick red, deep indigo and cream",
    "inky plum, copper orange and pale gold",
    "dark teal, tangerine and warm off-white; keep every color clearly separated from vivid #00FF00 chroma green",
)

private val techniques = listOf(
    "soft stop-motion felt with controlled fibers, clean sculpted joins and no visible construction seams",
    "hand-shaped animated-film clay with rounded planes and subtle fingerprints",
    "premium matte vinyl with crisp color blocking and restrained tactile grain",
    "layered cut-paper visual language rendered as one coherent dimensional body without detachable layers",
    "bold cel-shaded game art with hand-painted edge variation and simple value groups",
    "gouache-like painted surfaces wrapped around one simple coherent dimensional character",
)

private val faces = listOf(
    "two unequal but stable eye shapes and a restrained mouth system",
    "low-set expressive eyes, a tiny nose and a mouth that remains readable when closed",
    "one clean facial color block containing two clear eyes and minimal movable features",
    "a broad open face whose emotion reads through gaze and whole-body pose rather than a permanent grin",
    "a small centered face contrasted against the larger silhouette",
    "wide-set eyes with simple lids capable of clear blinks and sleep acting",
)

private val plasticities = listOf(
    "soft compression with delayed secondary settling while facial topology remains stable",
    "small precise impulses followed by elastic whole-body recovery",
    "restrained squash and stretch concentrated in the torso with stable facial proportions",
    "slightly viscous follow-through with believable grounded weight",
    "springy anticipation with quiet carefully damped landings",
    "weighted arcs led by the torso, followed naturally by existing appendages",
)

private fun stableIndex(seed: Int, namespace: String, size: Int): Int {
    require(size > 0)
    val digest = MessageDigest.getInstance("SHA-256")
        .digest("$seed:$namespace".toByteArray(Charsets.UTF_8))
    var value = 0L
    repeat(8) { index -> value = (value shl 8) or (digest[index].toLong() and 0xffL) }
    return ((value and Long.MAX_VALUE) % size).toInt()
}

private fun <T> choose(seed: Int, namespace: String, values: List<T>): T =
    values[stableIndex(seed, namespace, values.size)]

internal fun mobileCharacterContract(seed: Int): MobileCharacterContract {
    val subject = choose(seed, "subject", subjects)
    val behavior = choose(seed, "behavior", behaviors)
    val body = BodyProfile(
        archetype = subject.archetype,
        subject = subject.subject,
        visibleParts = subject.visibleParts,
        movementContacts = subject.movementContacts,
        front = subject.front,
        scale = "a compact widget-sized miniature; any body detail is smaller than the torso and no loose prop is present",
    )
    val visualSignature = VisualSignature(
        primarySilhouetteCue = choose(seed, "silhouette", silhouetteCues),
        secondaryReadableDetail = choose(seed, "detail", readableDetails),
        bodyLanguage = choose(seed, "body-language", bodyLanguages),
    )
    val selected = listOf(
        subject.subject,
        behavior.motivation,
        visualSignature.primarySilhouetteCue,
        visualSignature.secondaryReadableDetail,
        choose(seed, "palette", palettes),
        choose(seed, "technique", techniques),
        choose(seed, "face", faces),
        choose(seed, "plasticity", plasticities),
    )
    val fingerprint = MessageDigest.getInstance("SHA-256")
        .digest(selected.joinToString("|").toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
    return MobileCharacterContract(
        seed = seed,
        fingerprint = fingerprint,
        body = body,
        visualSignature = visualSignature,
        behavior = BehaviorProfile(
            motivation = behavior.motivation,
            worldview = behavior.worldview,
            movementSignature = behavior.movement,
            recurringMannerism = behavior.mannerism,
            idlePerformance = behavior.idle,
            joyfulPerformance = behavior.joyful,
            sleepPerformance = behavior.sleep,
            dancePerformance = behavior.dance,
        ),
        palette = selected[4],
        materialAndTechnique = selected[5],
        faceSystem = selected[6],
        plasticity = selected[7],
    )
}

/**
 * Choose from a deterministic pool while penalising traits already visible in
 * the local library. This is the phone-native equivalent of persisted shuffle
 * bags: retries keep the same result, while a run of characters avoids obvious
 * subject, silhouette, material and behaviour repetition.
 */
internal fun selectDiverseCreativeSeed(
    baseSeed: Int,
    existing: List<MobileCharacterContract>,
    candidates: Int = 192,
): Int {
    if (existing.isEmpty()) return baseSeed
    val recent = existing.takeLast(80)
    val fingerprints = existing.mapTo(mutableSetOf()) { it.fingerprint }
    return (0 until candidates)
        .map { index -> baseSeed + index * 104_729 }
        .map(::mobileCharacterContract)
        .filterNot { it.fingerprint in fingerprints }
        .minWithOrNull(
            compareBy<MobileCharacterContract> { candidate ->
                recent.sumOf { used ->
                    var penalty = 0
                    if (candidate.body.archetype == used.body.archetype) penalty += 4
                    if (candidate.body.subject == used.body.subject) penalty += 13
                    if (candidate.visualSignature.primarySilhouetteCue == used.visualSignature.primarySilhouetteCue) penalty += 8
                    if (candidate.visualSignature.secondaryReadableDetail == used.visualSignature.secondaryReadableDetail) penalty += 5
                    if (candidate.materialAndTechnique == used.materialAndTechnique) penalty += 6
                    if (candidate.palette == used.palette) penalty += 4
                    if (candidate.behavior.motivation == used.behavior.motivation) penalty += 5
                    if (candidate.behavior.movementSignature == used.behavior.movementSignature) penalty += 3
                    penalty
                }
            }.thenBy { it.fingerprint },
        )
        ?.seed
        ?: baseSeed
}

internal fun animationContract(
    action: String,
    seed: Int,
    durationSeconds: Int = videoDurationSeconds(action),
): MobileAnimationContract {
    val character = mobileCharacterContract(seed)
    val normalized = HeroLocalStore.normalizeVideoAction(action)
    val direction = when (normalized) {
        "joyful" -> MobileAnimationContract(
            action = normalized,
            actionGoal = "Acknowledge the viewer's touch with one unmistakable personal expression of delight.",
            beats = listOf(
                "Hold the home pose long enough to notice the viewer through the eyes first.",
                "Compress or shift weight in one clear anticipation without changing body topology.",
                character.behavior.joyfulPerformance,
                "Let secondary motion finish and recover through a calm readable path to the exact home pose.",
            ),
            tempo = "one clear emotional rise and one unhurried recovery; never repeat the peak gesture",
            gaze = "notice the viewer, remain emotionally engaged through the peak, then return to the canonical gaze",
            secondaryMotion = "existing ears, tail, wings or gills may overlap the torso action and must settle last",
            protectedInvariants = protectedInvariants(character),
            performanceFreedom = performanceFreedom(character),
            closure = exactClosure(),
            enterSeconds = 0.7,
            loopStartSeconds = 0.7,
            loopEndSeconds = (durationSeconds - 0.8).coerceAtLeast(1.5),
            exitSeconds = 0.8,
        )
        "sleep_loop" -> MobileAnimationContract(
            action = normalized,
            actionGoal = "Remain deeply asleep in one stable pose and complete one genuinely seamless breathing cycle.",
            beats = listOf(
                "Begin already fully asleep in exactly the supplied resting pose and breathing phase.",
                "Breathe in slowly with only subtle whole-torso volume and weight change.",
                "Breathe out slowly while the eyes, head, contacts and silhouette stay stable.",
                "Return organically to the exact supplied pose and breathing phase without waking or starting another action.",
            ),
            tempo = "one slow unbroken breath across the full clip; no separate performance beats",
            gaze = "eyes remain fully closed for every frame",
            secondaryMotion = "only breathing-scale settling; no head lift, roll, sit, stand or wake motion",
            protectedInvariants = protectedInvariants(character),
            performanceFreedom = performanceFreedom(character),
            closure = exactClosure(),
            enterSeconds = 0.0,
            loopStartSeconds = 0.0,
            loopEndSeconds = durationSeconds.toDouble(),
            exitSeconds = 0.0,
        )
        "sleeping" -> MobileAnimationContract(
            action = normalized,
            actionGoal = "Enter sleep, sustain a genuinely living sleep loop until interaction, then wake and return to idle.",
            beats = listOf(
                "Become progressively drowsy from the canonical home pose without an abrupt pose swap.",
                character.behavior.sleepPerformance,
                "Remain unmistakably asleep in one stable resting pose with closed eyes, continuous slow breathing and one very small characteristic sleep mannerism.",
                "Only after the loop section, wake naturally and reverse the weight path back to the exact canonical home pose.",
            ),
            tempo = "slow enter, long quiet breathing plateau, then a soft deliberate exit",
            gaze = "eyes close during enter, remain closed for the entire loop, and reopen only during exit",
            secondaryMotion = "only breathing-scale motion during the loop; no spontaneous waking, sitting or standing",
            protectedInvariants = protectedInvariants(character),
            performanceFreedom = performanceFreedom(character),
            closure = exactClosure(),
            enterSeconds = 3.8,
            loopStartSeconds = 3.8,
            loopEndSeconds = 8.4,
            exitSeconds = (durationSeconds - 8.4).coerceAtLeast(1.0),
        )
        "dancing" -> MobileAnimationContract(
            action = normalized,
            actionGoal = "Perform one original character-specific dance phrase and return cleanly to idle.",
            beats = listOf(
                "Discover the rhythm in the canonical home pose with one small anticipation.",
                character.behavior.dancePerformance,
                "Develop the same rhythm into one related variation using only the documented body parts.",
                "Decelerate through believable weight and follow-through, then restore the exact home pose.",
            ),
            tempo = "a readable rhythmic phrase with related variation, never a sequence of unrelated tricks",
            gaze = "the gaze supports the direction of each weight shift and returns to canonical orientation at the end",
            secondaryMotion = "existing appendages follow the main body rhythm with a small natural delay",
            protectedInvariants = protectedInvariants(character),
            performanceFreedom = performanceFreedom(character),
            closure = exactClosure(),
            enterSeconds = 0.8,
            loopStartSeconds = 0.8,
            loopEndSeconds = (durationSeconds - 1.0).coerceAtLeast(2.0),
            exitSeconds = 1.0,
        )
        else -> MobileAnimationContract(
            action = "idle",
            actionGoal = "Remain quietly alive and recognizable during repeated passive viewing.",
            beats = listOf(
                "Begin in the exact canonical home pose and establish slow continuous breathing.",
                character.behavior.idlePerformance,
                "Express the recurring mannerism once at low intensity, separated from the first accent by stillness.",
                "Let every secondary part settle and return to the same pose and breathing phase.",
            ),
            tempo = "mostly still, with two small non-simultaneous accents and generous readable pauses",
            gaze = "calm environmental attention with no large reaction or direct performance to camera",
            secondaryMotion = "breathing never stops; one existing secondary part may respond subtly after the torso",
            protectedInvariants = protectedInvariants(character),
            performanceFreedom = performanceFreedom(character),
            closure = exactClosure(),
            enterSeconds = 0.0,
            loopStartSeconds = 0.0,
            loopEndSeconds = durationSeconds.toDouble(),
            exitSeconds = 0.0,
        )
    }
    return direction
}

private fun protectedInvariants(character: MobileCharacterContract): List<String> = listOf(
    "Exactly one same character: ${character.body.subject}.",
    "Body topology never changes: ${character.body.visibleParts}.",
    "Identity cues remain unchanged: ${character.visualSignature.primarySilhouetteCue}; ${character.visualSignature.secondaryReadableDetail}.",
    "Palette, face, material, markings, scale, orientation and on-canvas position remain locked to the source image.",
    "At least fifteen percent of perfectly empty matte remains visible on the left, right, top and bottom in every frame; the complete silhouette and anything it carries stay inside the central seventy percent of the image.",
    "Every pixel outside the silhouette remains the same perfectly uniform matte with no additional visible element.",
)

private fun performanceFreedom(character: MobileCharacterContract): List<String> = listOf(
    "Natural movement is allowed only through ${character.body.movementContacts}.",
    "Gaze, eyelids, mouth and restrained torso compression may support the performance without redesigning the face.",
    "Secondary overlap may follow this plasticity: ${character.plasticity}.",
)

private fun exactClosure(): String =
    "The final frame must restore the supplied first frame's pose, expression, breathing phase, orientation, scale, coordinates, silhouette and appendage placement without a cut or crossfade."

internal fun buildCanonicalCharacterPrompt(seed: Int): String {
    val character = mobileCharacterContract(seed)
    return """
        Create one original premium virtual-pet mascot for a mobile tamagotchi app.

        WRITER CONTRACT
        Archetype: ${character.body.archetype}.
        Subject: ${character.body.subject}.
        Motivation: ${character.behavior.motivation}
        Worldview: ${character.behavior.worldview}
        Recurring mannerism: ${character.behavior.recurringMannerism}
        Movement signature: ${character.behavior.movementSignature}

        CHARACTER-DESIGN CONTRACT
        Body profile: ${character.body.visibleParts}. The front is defined by ${character.body.front}.
        Primary silhouette cue: ${character.visualSignature.primarySilhouetteCue}.
        Secondary readable detail: ${character.visualSignature.secondaryReadableDetail}.
        Neutral body language: ${character.visualSignature.bodyLanguage}.
        Palette: ${character.palette}. Material and technique: ${character.materialAndTechnique}.
        Face system: ${character.faceSystem}. Animation plasticity: ${character.plasticity}.
        Treat these decisions as one coherent identity, not a list of optional ingredients.

        CANONICAL ANCHOR
        Render exactly one complete original character in a calm neutral-positive home pose, centered in a direct
        front three-quarter view. This is an identity anchor, not a scene or an action. Show every documented body part
        exactly once, naturally attached, fully visible and structurally coherent. Do not tuck, crop, hide or detach any
        documented limb, costume section or integrated silhouette feature. The full silhouette occupies 52-64 percent of the portrait canvas
        height with at least 12 percent empty margin on every side. Emotion and identity must read at phone-widget size
        through silhouette, face and body language rather than tiny decoration. Use controlled detail and production-
        quality mobile-game finish. Avoid generic permanent open-mouth cuteness and avoid resemblance to any franchise.

        Return a genuine transparent PNG with a clean alpha edge. No floor, contact shadow, cast shadow, reflection,
        scenery, frame, text, logo, watermark, loose prop, second creature, duplicated body part or canvas-edge contact.
    """.trimIndent()
}

internal fun buildCharacterAnimationPrompt(
    action: String,
    seed: Int = 0,
    durationSeconds: Int = videoDurationSeconds(action),
    matteHex: String = "#00FF00",
): String {
    val character = mobileCharacterContract(seed)
    val direction = animationContract(action, seed, durationSeconds)
    val beats = direction.beats.mapIndexed { index, beat -> "${index + 1}. $beat" }.joinToString("\n")
    val protected = direction.protectedInvariants.joinToString(" ")
    val freedom = direction.performanceFreedom.joinToString(" ")
    val phaseTiming = if (direction.action == "sleep_loop") {
        "The entire clip is one closed breathing cycle in the same sleeping pose. There is no entrance, wake-up or exit."
    } else if (direction.action == "sleeping") {
        "Enter during 0.0-${direction.enterSeconds}s. The sleep loop is ${direction.loopStartSeconds}-${direction.loopEndSeconds}s and its endpoints must match in pose and breathing phase. Exit only after ${direction.loopEndSeconds}s."
    } else if (direction.action == "idle") {
        "The entire clip is one quiet closed loop; distribute the beats with unhurried pauses."
    } else {
        "Enter during the first ${direction.enterSeconds}s, sustain the action until ${direction.loopEndSeconds}s, then use the final ${direction.exitSeconds}s for a natural recovery."
    }
    val sourceDescription = if (direction.action == "sleep_loop") {
        "The supplied first and last images are the identical already-sleeping anchor extracted from the entrance clip."
    } else {
        "The supplied first and last images are the identical canonical home anchor."
    }
    return """
        ANIMATION CONTRACT ${direction.contract}
        Create one continuous silent ${durationSeconds}-second premium virtual-pet animation from the supplied image.
        Source strategy: ${direction.sourceStrategy}. Action goal: ${direction.actionGoal}
        $sourceDescription

        ORDERED PERFORMANCE BEATS
        $beats

        PERFORMANCE
        Tempo: ${direction.tempo}. Gaze: ${direction.gaze}. Secondary motion: ${direction.secondaryMotion}.
        $phaseTiming

        PROTECTED INVARIANTS
        $protected

        ALLOWED PERFORMANCE FREEDOM
        $freedom

        LOOP CLOSURE
        ${direction.closure}

        Animate the mascot as one complete indivisible body with believable weight, arcs, anticipation, overlapping
        action, follow-through and natural easing. Never animate it as separate cutout pieces. Keep a locked portrait
        camera, fixed framing and fixed scale. At every moment the complete silhouette, every limb and anything the
        mascot carries must remain inside the central seventy percent of the image. Preserve at least fifteen percent
        of untouched empty matte on the left, right, top and bottom throughout the entire motion. Every pixel outside the character is the perfectly uniform matte $matteHex
        in every frame, reserved only for local GPU removal; do not use that key color on the character. Keep the empty
        matte completely flat, without any other visible geometry, gradient, texture, halo, scenery or lighting change.
        No audio, cut, zoom, camera movement, depth travel, loose prop, text, logo, watermark or extra creature. Avoid morphing, identity drift, flicker,
        jitter, frozen sliding, duplicated, missing or detached anatomy, cropped silhouette and abrupt pose swaps.
    """.trimIndent()
}

internal fun MobileCharacterContract.toPersistedJson(): String =
    contractJson.encodeToString(this)

internal fun MobileAnimationContract.toPersistedJson(): String =
    contractJson.encodeToString(this)
