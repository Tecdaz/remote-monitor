/**
 * Runtime configuration for API and WebSocket URLs.
 *
 * Development (local): Vite replaces import.meta.env.VITE_* with values
 * from .env.local at build time. window.__ENV doesn't exist, so those
 * values are used.
 *
 * Production (Docker): The entrypoint script writes /config.js at
 * container startup with values from Docker env vars. window.__ENV
 * takes priority over the build-time defaults baked into the bundle.
 */

// Build-time defaults. Vite replaces these literals at build time.
const BUILT_IN_API = import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8000/api/v1'
const BUILT_IN_WS = import.meta.env.VITE_WS_BASE_URL ?? 'ws://localhost:8000/ws'

declare global {
  interface Window {
    __ENV?: Record<string, string>
  }
}

function runtimeEnv(key: string, fallback: string): string {
  if (typeof window !== 'undefined' && window.__ENV?.[key]) {
    return window.__ENV[key]
  }
  return fallback
}

export const API_BASE_URL = runtimeEnv('VITE_API_BASE_URL', BUILT_IN_API)
export const WS_BASE_URL = runtimeEnv('VITE_WS_BASE_URL', BUILT_IN_WS)
