FROM node:20.19.5-bookworm-slim AS dependencies

WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --omit=dev --ignore-scripts && npm cache clean --force

FROM node:20.19.5-bookworm-slim AS runtime

ENV NODE_ENV=production \
    MASCOT3_PORT=8788 \
    MASCOT3_DATA_DIR=/data \
    PET_V2_PYTHON=python3 \
    LIGHTING_PYTHON=/opt/lighting/bin/python \
    HF_HOME=/opt/lighting-models

RUN apt-get update \
    && apt-get install -y --no-install-recommends ffmpeg python3 python3-venv python3-numpy python3-opencv python3-pil \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY lighting/requirements.txt /tmp/lighting-requirements.txt
RUN python3 -m venv /opt/lighting \
    && /opt/lighting/bin/pip install --no-cache-dir --upgrade pip==25.2 \
    && /opt/lighting/bin/pip install --no-cache-dir torch==2.5.1+cpu torchvision==0.20.1+cpu --extra-index-url https://download.pytorch.org/whl/cpu \
    && /opt/lighting/bin/pip install --no-cache-dir -r /tmp/lighting-requirements.txt \
    && /opt/lighting/bin/python -c "from huggingface_hub import snapshot_download; snapshot_download('depth-anything/Depth-Anything-V2-Small-hf', revision='5426e4f0f36572d16453bbda7a8389317b1bef99', allow_patterns=['*.json','*.safetensors'])" \
    && chmod -R a+rX /opt/lighting-models
COPY --from=dependencies --chown=node:node /app/node_modules ./node_modules
COPY --chown=node:node package.json package-lock.json ./
COPY --chown=node:node extensions ./extensions
COPY --chown=node:node pipeline ./pipeline
COPY --chown=node:node physics ./physics
COPY --chown=node:node lighting ./lighting
COPY --chown=node:node scripts/prepare-lighting.py scripts/prepare-local-depth.py scripts/prepare-video-lighting.py ./scripts/
COPY --chown=node:node public ./public
COPY --chown=node:node server ./server
COPY --chown=node:node deploy ./deploy
RUN node -e "require('./physics/jobs')" \
    && mkdir -p /data && chown node:node /data

USER node
EXPOSE 8788
VOLUME ["/data"]
HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=5 \
  CMD ["node", "-e", "fetch('http://127.0.0.1:8788/health').then(r=>process.exit(r.ok?0:1)).catch(()=>process.exit(1))"]
CMD ["node", "server/server.js"]
