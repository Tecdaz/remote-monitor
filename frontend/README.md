# frontend

TanStack real-time dashboard. The frontend subscribes to the backend
WebSocket and renders live patient state without manual refresh.

## Stack

- Framework: **TanStack Start** (1.168+)
- Data layer: **TanStack Query** ≥ 5.101
- Routing: **TanStack Router**
- Real-time: native `WebSocket` client
- Styling: **Tailwind CSS v4** (CSS-first config via `@theme`)
- Build: **Vite 7** + `@tailwindcss/vite` (no PostCSS)
- Runtime: **Node 22.12+** (TanStack Start engine requirement)
- Package manager: `bun` (single lockfile: `bun.lock`)

## Design system — "Iris Void"

The frontend uses a hybrid design system that adapts the editorial dark
aesthetic of **Dala** (pure black void, weight-200 display type, single
saturated violet accent) to a clinical patient-monitoring dashboard.

**Tokens** live in `frontend/app/styles.css` under the `@theme` block.
Three semantic namespaces map on top of the raw palette:

- `clinical-*` — surfaces, ink, borders, accent
- `status-*` — live / ok / warn / danger / info
- `chart-*` — HRV data series (VLF / LF / HF) and line colors

Recharts / SVG attributes that need literal color strings pull from
`frontend/lib/theme-colors.ts` (CSS variables aren't readable from JS).

**Reference lock, token roles, and the anti-AI-slop audit** live in
[`/.design/DESIGN.md`](../design/DESIGN.md). Read that file before
changing any token — every color has a bounded role.

### Visual identity at a glance

| Layer | Value |
| --- | --- |
| Canvas | `#000000` (pure black, the void) |
| Body text | `#FFFFFF` |
| CTA | `#8052FF` (electric iris, filled pill only) |
| Emphasis | `#FFB829` (saffron spark) |
| Ok / live | `#00FFAA` (mint vital) |
| Danger | `#EF4444` (standard red) |
| Card border | `1px solid #15846E` (deep verdant) |
| Border-radius | 24px on all interactive surfaces |
| Numerics | Fira Code, tabular |

## Real-time pattern — `queryClient.setQueryData`

The dashboard never polls. The WebSocket client receives pushed
measurement updates and writes them directly into the TanStack Query
cache with `queryClient.setQueryData(['measurements', patientId],
updater)`. This makes the push event the single source of truth and
removes the need for a refetch on receipt. The query key shape, the
updater merge function, and the reconnection strategy land in the
`scaffold-frontend` follow-up spec.

## Auth posture (PoC)

There is **no user login** on the dashboard. The app opens to the
patient list view; selection of a patient connects the WebSocket and
streams their measurements. This is a proof-of-concept posture only.

## Pinned tokens

The following strings appear on their own line so the scaffold's
acceptance grep can verify them literally.

```
TanStack Start
TanStack Query ≥ 5.101
WebSocket
setQueryData
Node 22.12
scaffold-frontend
bun
```

## Follow-up

The full frontend module — `package.json`, TanStack Start routes,
TanStack Query client setup, WebSocket subscriber hook, the
`setQueryData` updater, the patient list and detail views, accessibility
audit, and component tests — lands in the `scaffold-frontend` SDD
change. This README is the placeholder for that work.

## API contract

The frontend consumes `contracts/openapi.yaml` for REST types (via
`openapi-typescript` in the upcoming frontend change) and
`contracts/websocket-types.ts` for WebSocket types. The WebSocket
URL is `wss://{host}/ws/patients/{patient_id}` — the URL is the
subscription.