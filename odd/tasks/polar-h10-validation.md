# feat-polar-h10-validation — Banda Polar H10 como referencia contra smartwatch

Objective: capturar BPM+RR de la Polar H10 en la app Wear por GATT estándar (sin SDK, sin ECG) y propagar con `source` end-to-end para validar el Samsung. Branch: `feat-polar-h10-validation`. Delivery: `ask-on-risk`. Runner: `./gradlew :app:testDebugUnitTest` + `uv run pytest backend/tests/test_ingest.py backend/tests/test_ws.py backend/tests/test_migrations.py backend/tests/test_measurements_router.py`.

## Specs

- S1: "Quiero agregar como artefacto de medicion una banda polar h10."
- S2: "Investiga la documentacion de como puedo capturar sus señales con una esta aplicacion."
- S3: "La idea es cruzar informacion para validarla, seria validar lo que obtenemos de la banda con lo que obtenemos del smartwatch"
- S4: "Dale, agregalo. El ECG no es importante por ahora" — scope: sin ECG, solo BPM+RR (HR 1 Hz + RR ms vía HRS 0x180D/0x2A37, GATT crudo, minSdk 30 intacto).
- S5 (derivada, autorizada por S1+S3): cada muestra lleva discriminador `source` (`samsung_watch` | `polar_h10`) en Room → wire → backend → GET/WS → contracts; backend `extra="forbid"` obliga cambio atómico o 422.
- Aceptación: (1) provider GATT parsea flags/BPM uint8-uint16/RR 1/1024s→ms; (2) Room v6→7 agrega `source TEXT NOT NULL DEFAULT 'samsung_watch'` + backfill; (3) backend acepta/persiste/devuelve `source`, rechaza desconocido por ítem; (4) GET/WS incluyen `source`; (5) `main` verde en watch+backend; (6) protocolo de validación documentado (ventana ±1 s, MAE/RMSE, Bland-Altman, RR vs ibis).

## Tasks

| ID | Title | S# | Route (trigger evidence) | Status | Commit |
|----|-------|----|--------------------------|--------|--------|
| T1 | Watch: `PolarHrsProvider` GATT (parse 0x2A37, Flow, timeout/null) + unit tests | S1,S2,S4 | inline (single unit ~120 líneas, sin razón Writer) | done (GREEN: PolarHrsParserTest 5/5) | pending-commit |
| T2 | Watch: `source` en `MeasurementEntity` + migración 6_7 + `SensorOrchestrator` dual + permisos BLE + wiring | S1,S3,S5 | inline (single unit, superficies disjuntas pero secuencial) | pending | — |
| T3 | Backend+contracts: `source` en Pydantic/model/migración/ingest/routers/WS + openapi/data-models/ws-types (+frontend drift `hr_status`) + tests | S1,S3,S5 | inline (single unidad lógica código+tests+docs juntos) | pending | — |
| T4 | Docs: protocolo de validación polar-vs-samsung (sesión, métricas, ejemplo) | S3 | inline (solo docs) | pending | — |
| T5 | Verification: suite watch+backend, diferencial GATT, cierre | S1-S5 | inline (per-action: suite en foreground una vez) | pending | — |

Forecast: ~550 líneas autoradas (provider 120 + entity/migración/orquestador 150 + backend/contratos 200 + docs 80). Running: 0. Slice: un commit por tarea (~100-150 líneas c/u); si T3 supera 400, partir en T3a (modelo+migración) / T3b (ingest+read+WS). Estrategia `ask-on-risk`: preguntar cadena solo si se supera el presupuesto antes del siguiente commit.

## Log

- L1 (2026-10-18): "Quiero agregar como artefacto de medicion una banda polar h10. Investiga la documentacion de como puedo capturar sus señales con una esta aplicacion. La idea es cruzar informacion para validarla, seria validar lo que obtenemos de la banda con lo que obtenemos del smartwatch"
- L2 (2026-10-18): "Dale, agregalo. El ECG no es importante por ahora"
- Investigación (S2): Polar BLE SDK exige minSdk 33 vs watch minSdk 30 → se elige GATT estándar HRS para MVP (BPM+RR, sin ECG, sin dependencia JitPack). H10: HR 1 Hz + RR ms + broadcast + ECG 130 Hz (diferido) + ACC (diferido). Fuentes: polar-ble-sdk README, PolarH10.md, PolarOnlineStreamingApi.
- Mapa (explorer ses_eb053643dffeu87hSW8WMaT1hA): sin `source` en ningún nivel; `extra="forbid"` obliga cambio atómico; manifest sin permisos BLE; Room v6 con precedente migración-por-columna; frontend `types.ts` ya deriva en `hr_status`; WS vía `manager.publish` en ingest.
- Decisión: colector = el propio watch (mismo reloj, mismo `POST /measurements`, misma sesión) para evitar sincronía NTP entre dispositivos.
- Next: T1 PolarHrsProvider GATT + tests en esta rama.
