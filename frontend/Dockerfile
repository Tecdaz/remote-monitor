# syntax=docker/dockerfile:1
# Stage 1: Build with bun (fast, aligned with the project's package manager)
FROM oven/bun:1-alpine AS builder
WORKDIR /app

# Build-time defaults. These are overridden at runtime by the entrypoint
# script via Docker env vars + window.__ENV injection.
ARG VITE_API_BASE_URL=http://localhost:8000/api/v1
ARG VITE_WS_BASE_URL=ws://localhost:8000/ws
ENV VITE_API_BASE_URL=$VITE_API_BASE_URL
ENV VITE_WS_BASE_URL=$VITE_WS_BASE_URL

# Install dependencies first so this layer is cached when only source changes.
COPY package.json bun.lock ./
RUN bun install --frozen-lockfile

# Copy source and build (SSR + client bundles).
COPY . .
RUN bun run build

# Stage 2: Production runtime with Node.js.
# TanStack Start's SSR server uses node:async_hooks, so we need Node, not bun.
FROM node:20-alpine
WORKDIR /app

COPY --from=builder /app/package.json ./
COPY --from=builder /app/node_modules ./node_modules
COPY --from=builder /app/dist ./dist
COPY docker-entrypoint.sh start-server.js ./
RUN chmod +x docker-entrypoint.sh

# Run as a non-root user, same convention as the backend Dockerfile.
RUN adduser --disabled-password --home /app frontend \
    && chown -R frontend:frontend /app
USER frontend

EXPOSE 3000

ENTRYPOINT ["./docker-entrypoint.sh"]
