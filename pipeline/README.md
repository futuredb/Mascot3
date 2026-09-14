# Pet Generation v2

Pipeline for creating a character from dictionary-driven traits, rendering its canonical anchors, and producing image-to-video animation assets.

## Project status

- Character design, lore composition, canonical anchor rendering, and animation generation are implemented.
- Seedance 2.0 is the production video model. It receives one canonical image in both `first_frame` and `last_frame` slots.
- Animation uses a reviewed, versioned prompt-template catalog. No animation art director or per-run text model call is used.
- Generated characters, videos, and run logs are intentionally excluded from this repository. They are local outputs under `characters/`, `runs/`, and `_state/`.

## Core pipeline

`run_character_pipeline.js` is the main entry point:

1. `lib/deal.js` selects dictionary traits.
2. `lib/composer.js` calls the text model to compose lore.
3. `extensions/01_bridges/nanobanana/bridge.js` renders canonical anchors.
4. `lib/production_animation.js` resolves a reviewed prompt template and supplies the canonical first/last frame anchors.
5. `extensions/01_bridges/veo/bridge.js` submits and polls OpenRouter video jobs.
6. `lib/media.js` removes chroma, builds WebP assets, and measures drift.

## Model calls

All provider configuration is read from the local environment files; no API key belongs in this repository.

| Stage | Bridge | Default / tested model |
| --- | --- | --- |
| Lore | OpenRouter text bridge | `PET_V2_TEXT_MODEL` |
| Canonical/action images | Nano Banana bridge | `IMAGE_RENDER_MODEL` or `google/gemini-3.1-flash-image-preview` |
| Video | VEO/OpenRouter bridge | `bytedance/seedance-2.0` |

The video request always passes `generate_audio: false`. For loops, the canonical anchor is both the first and last video frame. The completed provider response records `usage.cost`, which the pipeline writes to `cost.json` and `result.json`.

## Commands

Use the agent-facing CLI for canonical-only character creation. It never accepts animation options and only sends paid lore/image requests with `--submit=yes`.

```powershell
node .\scripts\character_cli.js create `
  --subject="turtle" `
  --brief="A patient keeper of tiny mechanical rhythms." `
  --submit=yes
```

Add `--direct-openrouter=yes` to send the lore, design, and canonical-review text requests directly to OpenRouter using `OPENROUTER_API_KEY`, bypassing the internal OpenRouter proxy. Image and video providers continue to use their dedicated bridges.

For a curated series, pass a JSON spec with contract `pet_generation_v2.character_batch.v1` and a `characters` array. `batch` runs sequentially and writes its manifest under `runs/character_batches/`.

```powershell
node .\scripts\character_cli.js batch `
  --spec="characters.json" `
  --series `
  --submit=yes
```

Plan or submit selected catalog animations for an approved character. Choose an explicit comma-separated list or `--all-enabled`; `--batch-dir` keeps this run's files together and lets the same command resume saved jobs.

```powershell
node .\scripts\character_cli.js animate `
  --character="characters\<character>" `
  --animations=idle,sleep,work `
  --batch-dir="selected-v43"

node .\scripts\character_cli.js animate `
  --character="characters\<character>" `
  --animations=idle,sleep,work `
  --batch-dir="selected-v43" `
  --submit=yes
```

Create a character through the canonical-anchor stage:

```powershell
node .\run_character_pipeline.js `
  --subject="turtle" `
  --subject-kind=living `
  --through=canonical
```

Submit one reviewed catalog state for an existing character:

```powershell
node .\scripts\verify_animation_prompt.js `
  --character="characters\<character>" `
  --animation=playful `
  --submit-confirmed=yes
```

Run unit and contract tests:

```powershell
npm run test:pet-v2
```

The batch tools under `scripts/` submit, poll, and assemble catalog states. Manual probes and one-off media repair tools are isolated in `scripts/tests/`; they are not part of the production path.

## Repository layout

```text
dict/       Trait and animation catalogs
experiments/ Reference experiments
lib/        Deal, composition, animation, media, registry code
prompts/    Text-model contracts
refs/       Style-reference pack
schema/     JSON schemas
scripts/    Production catalog operations
scripts/tests/  Manual rendering and media probes
```
