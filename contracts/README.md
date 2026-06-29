# contracts

Source of truth for the wire contract between the watch, backend, and frontend.

## Files

- `openapi.yaml` — OpenAPI 3.1.0 REST spec. Pydantic (backend) and TypeScript (frontend) are derived from it.
- `asyncapi.yaml` — AsyncAPI 3.0.0 WebSocket protocol documentation. D4: the URL is the subscription.
- `websocket-types.ts` — Hand-written TypeScript types for the WebSocket surface. Mirrors AsyncAPI.
- `data-models.md` — Cross-language reference: JSON Schema, Pydantic v2, TypeScript, and Kotlin `data class` shapes for every model.
- `../.spectral.yaml` — Spectral lint config extending `spectral:oas` and `spectral:asyncapi`. Enforces operation completeness in CI.

## Locked decisions (D1–D5)

- **D1**: OpenAPI 3.1.x for the REST spec.
- **D2**: `openapi.yaml` is the source of truth. Kotlin types in the watch are hand-written from the YAML.
- **D3**: Hand-written TypeScript types for the WebSocket protocol (no Modelina codegen for the PoC).
- **D4**: Per-patient WebSocket URL `wss://{host}/ws/patients/{patient_id}`. The URL IS the subscription.
- **D5**: Client-generated `local_id` (UUID v4) is the idempotency key that drives the `delete-after-echo` invariant.

The upcoming `backend-fastapi`, `watch-app`, and `frontend-tanstack` changes consume this contract.
