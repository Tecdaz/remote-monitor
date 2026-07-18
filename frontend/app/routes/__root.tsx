import { createRootRoute, HeadContent, Outlet, Scripts } from '@tanstack/react-router'
import { QueryClientProvider } from '@tanstack/react-query'
import { queryClient } from '../../lib/query-client'
import '../styles.css'

// Inline SVG favicon served as a data URI so the browser stops requesting
// /favicon.ico (which is not part of the build) without shipping a binary
// asset. The mark mirrors the clinical teal accent used elsewhere.
const FAVICON_HREF =
  "data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 64 64'%3E%3Crect width='64' height='64' rx='12' fill='%230D9488'/%3E%3Cpath d='M26 12h12v14h14v12H38v14H26V38H12V26h14z' fill='white'/%3E%3C/svg%3E"

// /config.js is produced at runtime by docker-entrypoint.sh so Docker
// deployments can override VITE_* URLs without rebuilding. In Vite dev
// the file does not exist and the app falls back to import.meta.env, so
// we only request it in production builds.
const RUNTIME_CONFIG_SCRIPT = import.meta.env.PROD ? [{ src: '/config.js' }] : []

export const Route = createRootRoute({
  head: () => ({
    meta: [
      { charSet: 'utf-8' },
      { name: 'viewport', content: 'width=device-width, initial-scale=1' },
      { name: 'description', content: 'Monitor clínico remoto de pacientes' },
      { name: 'theme-color', content: '#F6F8FB' },
      { title: 'Monitor clínico' },
    ],
    links: [
      { rel: 'icon', type: 'image/svg+xml', href: FAVICON_HREF },
    ],
    scripts: RUNTIME_CONFIG_SCRIPT,
  }),
  component: RootComponent,
})

function RootComponent() {
  return (
    <html lang="es">
      <head>
        <HeadContent />
      </head>
      <body className="min-h-screen bg-clinical-surface text-clinical-ink">
        <a
          href="#main-content"
          className="sr-only focus:not-sr-only focus:fixed focus:left-4 focus:top-4 focus:z-50 focus:rounded-md focus:bg-clinical-accent focus:px-3 focus:py-2 focus:text-sm focus:font-medium focus:text-white focus:shadow-card focus:outline-none focus:ring-2 focus:ring-white focus:ring-offset-2 focus:ring-offset-clinical-surface"
        >
          Saltar al contenido principal
        </a>
        <QueryClientProvider client={queryClient}>
          <main id="main-content" tabIndex={-1} className="min-h-screen outline-none">
            <Outlet />
          </main>
        </QueryClientProvider>
        <Scripts />
      </body>
    </html>
  )
}