FROM node:20.19.5-bookworm-slim AS dependencies

WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --omit=dev --ignore-scripts && npm cache clean --force

FROM node:20.19.5-bookworm-slim AS runtime

ENV NODE_ENV=production \
    MASCOT3_PORT=8788 \
    MASCOT3_DATA_DIR=/data \
    PET_V2_PYTHON=python3

RUN apt-get update \
    && apt-get install -y --no-install-recommends ffmpeg python3 python3-numpy python3-opencv python3-pil \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY --from=dependencies --chown=node:node /app/node_modules ./node_modules
COPY --chown=node:node package.json package-lock.json ./
COPY --chown=node:node extensions ./extensions
COPY --chown=node:node pipeline ./pipeline
COPY --chown=node:node public ./public
COPY --chown=node:node server ./server
COPY --chown=node:node deploy ./deploy
RUN mkdir -p /data && chown node:node /data

USER node
EXPOSE 8788
VOLUME ["/data"]
HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=5 \
  CMD ["node", "-e", "fetch('http://127.0.0.1:8788/health').then(r=>process.exit(r.ok?0:1)).catch(()=>process.exit(1))"]
CMD ["node", "server/server.js"]
