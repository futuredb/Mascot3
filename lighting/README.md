# Automatic lighting preparation

Version 0.1.60 separates paid media generation from background map preparation.
The image prompts request broad neutral diffuse illumination and restrained
specular highlights; every animation preserves illumination, exposure, white
balance and roughness, including the loop seam. Existing paid jobs are not
regenerated or silently reinterpreted with a different prompt.

## Data flow

1. Android saves its completed transparent PNG or approved action MP4 first.
   It durably marks the hero for lighting and enqueues unique WorkManager work
   by hero/kind/SHA-256. Startup reconciles saved files if the process died
   between the save and enqueue. Every saved action, including sleep_loop, is
   included; missing actions never trigger generation. Bundled maps are reused.
2. Authenticated `GET /v1/lighting/sources/{image|video}/{sha256}` resumes an
   existing job. `PUT` of raw source bytes creates a missing job idempotently.
   Server-created media are queued directly as each completed asset appears.
3. A single server worker runs the pinned, cached Depth Anything V2 Small model.
   Image output contains the unchanged RGBA, depth and bounded relief mesh.
   Video output contains pose-aligned normals, original frame timestamps and
   optical-flow-assisted temporal smoothing. No RGB generation, inpainting,
   Replicate, OpenRouter or other paid model requests occur in this worker.
4. `GET /v1/lighting/jobs/{id}` returns status and hash-addressed asset metadata.
   Android downloads with byte/hash/dimension checks. Only complete validated
   packs become active. The static renderer watches the active revision; video
   players pick up the downloaded maps at their next preparation/clip change.

Generation completion never waits for maps. Network interruptions retain the
saved hero/video and retry the background work. Inference failures keep ordinary
playback; they do not restart a paid job. Uploads are capped at 20 MiB for PNG
and 80 MiB for MP4. Runtime clips support up to 256 source frames and 4 atlas
pages (16 MiB GPU per clip); unsupported/corrupt input falls back to ordinary
playback. Static images support 64–2048 pixels per side. Originals remain on
the phone/in the generator library; successful server jobs discard only their
temporary uploaded copy and inference cache.

## Deployment

The Dockerfile installs an isolated CPU PyTorch environment and downloads model
revision `5426e4f0f36572d16453bbda7a8389317b1bef99` during the image build.
Runtime uses offline mode and `local_files_only=True`. Python receives only
PATH/HOME/HF_HOME/TMPDIR, never generator credentials. Existing application
volume `/data` contains the durable `server-data/lighting` queue and packages.
No new public port or service is required. Keep the client token configured.

Local processing uses `LIGHTING_PYTHON` pointing to a Python environment with
`lighting/requirements.txt`, PyTorch and the pinned cached model. The existing
`scripts/prepare-local-depth.py` / `prepare-video-lighting.py` bundled workflows
remain supported; the server calls the generic `scripts/prepare-lighting.py`.

Deploy the backend before the APK when possible. An older backend returns 404;
Android keeps its preparation jobs waiting without starting generation. Rollback
uses the previous Coolify image; new queue files are additive and the old app
ignores them. Never delete the existing hero volume during deployment.

## Codex preview validation

`node scripts/lighting-acceptance-server.cjs` opens the local-only acceptance
page at `http://127.0.0.1:8799`. Its buttons run offline Node/Android checks and
process the already saved Akim/Mars/Una PNGs and Una idle clip. It does not load
the production server, resume paid jobs or call a generator. Artifacts and logs
are written under `output/automatic-lighting-0.1.60`.
