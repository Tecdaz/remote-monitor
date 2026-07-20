# syntax=docker/dockerfile:1

# ----------------------------------------------------------------------------
# Stage 1a: Install ALL dependencies (dev + prod) for the build.
# ----------------------------------------------------------------------------
FROM oven/bun:1-alpine AS deps
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

# ----------------------------------------------------------------------------
# Stage 1b: Build the app (SSR + client bundles) using full deps.
# ----------------------------------------------------------------------------
FROM oven/bun:1-alpine AS builder
WORKDIR /app
COPY --from=deps /app/node_modules ./node_modules
COPY . .
RUN bun run build

# ----------------------------------------------------------------------------
# Stage 2: Install PROD-ONLY dependencies in a fresh layer.
# This is what gets shipped to the runtime image.
# ----------------------------------------------------------------------------
FROM oven/bun:1-alpine AS prod-deps
WORKDIR /app
COPY package.json bun.lock ./
RUN bun install --frozen-lockfile --omit=dev

# ----------------------------------------------------------------------------
# Stage 3: Minimal Node runtime with prod-only node_modules.
# TanStack Start's SSR server requires Node >= 22.12 (per @tanstack/react-start
# engine constraints). Bun is not used here because the runtime needs
# node:async_hooks and native Node APIs that the production server uses.
# ----------------------------------------------------------------------------
FROM node:22-alpine
WORKDIR /app

COPY --from=prod-deps /app/node_modules ./node_modules
COPY --from=builder /app/dist ./dist
COPY docker-entrypoint.sh start-server.js ./
RUN chmod +x docker-entrypoint.sh

# Run as a non-root user, same convention as the backend Dockerfile.
RUN adduser --disabled-password --home /app frontend \
    && chown -R frontend:frontend /app
USER frontend

EXPOSE 3000

ENTRYPOINT ["./docker-entrypoint.sh"]