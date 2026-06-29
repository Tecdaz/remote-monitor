# frontend

TanStack real-time dashboard. The frontend subscribes to the backend
WebSocket and renders live patient state without manual refresh.

## Stack

- Framework: TanStack Start
- Data layer: TanStack Query v5.90.3
- Runtime: Node 20.19+
- Package manager: `bun` (preferred) or `npm` as fallback
- Real-time: native `WebSocket` client

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

TanStack Start
TanStack Query v5.90.3
WebSocket
setQueryData
Node 20.19
scaffold-frontend
bun

## Follow-up

The full frontend module — `package.json`, TanStack Start routes,
TanStack Query client setup, WebSocket subscriber hook, the
`setQueryData` updater, the patient list and detail views, accessibility
audit, and component tests — lands in the `scaffold-frontend` SDD
change. This README is the placeholder for that work.
