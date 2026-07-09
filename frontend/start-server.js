/**
 * Minimal Node.js HTTP server for TanStack Start production builds.
 *
 * TanStack Start (Rsbuild output) emits:
 *   - dist/client/              static assets (JS, CSS, images)
 *   - dist/server/server.js     fetch-style server entry
 *
 * This server:
 *   1. Serves dist/client/ files as static assets
 *   2. Forwards all other requests to the TanStack Start fetch handler
 */
import { createServer } from 'node:http'
import { readFileSync, existsSync } from 'node:fs'
import { join, extname } from 'node:path'
import server from './dist/server/server.js'

const PORT = parseInt(process.env.PORT || '3000', 10)
const CLIENT_DIR = join(process.cwd(), 'dist', 'client')

const MIME = {
  '.js': 'application/javascript',
  '.mjs': 'application/javascript',
  '.css': 'text/css',
  '.html': 'text/html',
  '.json': 'application/json',
  '.png': 'image/png',
  '.svg': 'image/svg+xml',
  '.ico': 'image/x-icon',
}

function serveStatic(pathname, res) {
  const filePath = join(CLIENT_DIR, pathname === '/' ? 'index.html' : pathname)
  if (!filePath.startsWith(CLIENT_DIR)) return false

  if (existsSync(filePath)) {
    const ext = extname(filePath)
    const contentType = MIME[ext] || 'application/octet-stream'
    const content = readFileSync(filePath)
    res.writeHead(200, { 'Content-Type': contentType, 'Content-Length': String(content.length) })
    res.end(content)
    return true
  }
  return false
}

const httpServer = createServer(async (req, res) => {
  try {
    const url = new URL(req.url || '/', `http://${req.headers.host || 'localhost'}`)

    // Static assets
    if (serveStatic(url.pathname, res)) return

    // Forward to TanStack Start SSR handler
    const headers = {}
    for (const [k, v] of Object.entries(req.headers)) {
      if (v !== undefined) headers[k] = String(v)
    }

    const request = new Request(url, {
      method: req.method || 'GET',
      headers,
    })

    const response = await server.fetch(request)

    const responseHeaders = {}
    response.headers.forEach((value, key) => {
      responseHeaders[key] = value
    })
    res.writeHead(response.status, responseHeaders)

    if (response.body) {
      const reader = response.body.getReader()
      const pump = async () => {
        try {
          const result = await reader.read()
          if (result.done) {
            res.end()
            return
          }
          res.write(result.value)
          await pump()
        } catch (_err) {
          res.end()
        }
      }
      await pump()
    } else {
      res.end()
    }
  } catch (err) {
    console.error('SSR error:', err)
    if (!res.headersSent) {
      res.writeHead(500, { 'Content-Type': 'text/plain' })
      res.end('Internal Server Error')
    }
  }
})

httpServer.listen(PORT, '0.0.0.0', () => {
  console.log(`Frontend listening on http://0.0.0.0:${PORT}`)
})
