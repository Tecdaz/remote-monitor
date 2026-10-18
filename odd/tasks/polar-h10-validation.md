# feat-polar-h10-validation — Banda Polar H10 como referencia contra smartwatch

Objective: capturar BPM+RR de la Polar H10 en la app Wear por GATT estándar (sin SDK, sin ECG) y propagar con `source` end-to-end para validar el Samsung. Branch: `feat-polar-h10-validation`. Delivery: `ask-on-risk`. Runner: `./gradlew :app:testDebugUnitTest` + `uv run pytest backend/tests/test_ingest.py backend/tests/test_ws.py backend/tests/test_migrations.py backend/tests/test_measurements_router.py`.

## Specs

- S1: "Quiero agregar como artefacto de medicion una banda polar h10."
- S2: "Investiga la documentacion de como puedo capturar sus señales con una esta aplicacion."
- S3: "La idea es cruzar informacion para validarla, seria validar lo que obtenemos de la banda con lo que obtenemos del smartwatch"
- S4: "Dale, agregalo. El ECG no es importante por ahora" — scope: sin ECG, solo BPM+RR (HR 1 Hz + RR ms vía HRS 0x180D/0x2A37, GATT crudo, minSdk 30 intacto).
- S6 (2026-10-18): "Quiero data del ACC asi que si sera necesario el uso del SDK" — se agrega ACC vía Polar SDK (online streaming, 25-200 Hz); el ECG sigue diferido.
- S7 (2026-10-18): "pon para que la app muestre el dialogo de permisos en runtime" — diálogo del sistema para BLUETOOTH_SCAN/CONNECT (API 31+) o FINE_LOCATION (API 30), solo si `POLAR_DEVICE_ID` no está vacío; denegar deja Polar dormido, el retry loop lo retoma solo.
- S5 (derivada, autorizada por S1+S3): cada muestra lleva discriminador `source` (`samsung_watch` | `polar_h10`) en Room → wire → backend → GET/WS → contracts; backend `extra="forbid"` obliga cambio atómico o 422.
- Aceptación: (1) provider GATT parsea flags/BPM uint8-uint16/RR 1/1024s→ms; (2) Room v6→7 agrega `source TEXT NOT NULL DEFAULT 'samsung_watch'` + backfill; (3) backend acepta/persiste/devuelve `source`, rechaza desconocido por ítem; (4) GET/WS incluyen `source`; (5) `main` verde en watch+backend; (6) protocolo de validación documentado (ventana ±1 s, MAE/RMSE, Bland-Altman, RR vs ibis).

## Tasks

| ID | Title | S# | Route (trigger evidence) | Status | Commit |
|----|-------|----|--------------------------|--------|--------|
| T1 | Watch: `PolarHrsProvider` GATT (parse 0x2A37, Flow, timeout/null) + unit tests | S1,S2,S4 | inline (single unit ~120 líneas, sin razón Writer) | done (GREEN: PolarHrsParserTest 5/5 + suite :app:testDebugUnitTest green) | 975c1b8 |
| T1b | SDK: spike Polar BLE SDK (HR+ACC streaming, JitPack, permisos) + decisión minSdk | S6 | innecesaria (una sola unidad; se cancela por L4) | cancelled (L4: sin SDK; ACC diferido) | — |
| T10 | Frontend: mínimo/máximo ajustable del eje Y del tacograma | S11 | inline (resolver puro + inputs, sin razón Writer) | done (RED 4 fallos → GREEN 4/4 + suite 52/52) | 2bc3131 |
| T9 | Frontend: colapsar IBIs polares idénticos consecutivos en selectIBISeries (cubre los 3 gráficos) | S10 | inline (selector puro + tests, sin razón Writer) | done (RED 2 fallos → GREEN 4/4 + suite 48/48) | 8361310 |
| T8 | Frontend: filtro por dispositivo (Todos/Samsung/Polar) + dedupe de repetidas consecutivas Polar | S9 | inline (helper puro + hook + toggle, sin razón Writer) | done (RED 7 fallos → GREEN 7/7 + suite 44/44) | 557f76b |
| T7 | Watch: link GATT Polar estable (neverForLocation, LOW_LATENCY+HRS, watchdog 30s, single-connect, logs) — `connectedDevice` revertido (crasheaba el FGS) | S8 | inline (fix + rebuild, sin razón Writer) | done (suite green + vivo: ~1 Hz, BPM+RR reales) | 141269c |
| T6 | Watch: diálogo runtime de permisos BLE solo cuando hay banda configurada | S7 | inline (helper puro + wiring Activity, sin razón Writer) | done (RED unresolved → GREEN 2/2 + suite green; caché incremental limpiada) | pending-commit |
| T2 | Watch: `source` en `MeasurementEntity` + migración 6_7 + `SensorOrchestrator` dual + permisos BLE + wiring | S1,S3,S5 | inline (single unit, superficies disjuntas pero secuencial) | done (RED unresolved refs → GREEN polar test 2/2 + suite green) | 28685ec |
| T3 | Backend+contracts: `source` en Pydantic/model/migración/ingest/routers/WS + openapi/data-models/ws-types (+frontend drift `hr_status`) + tests | S1,S3,S5 | inline (single unidad lógica código+tests+docs juntos) | done (RED extra_forbid → GREEN 7/7 nuevo + 84 vecinas + 120 full backend + vitest 14/14) | b307f63 |
| T4 | Docs: protocolo de validación polar-vs-samsung (sesión, métricas, ejemplo) | S3 | inline (solo docs) | done (`docs/polar-validation-protocol.md`) | 59233f7 |
| T5 | Verification: suite watch+backend, diferencial GATT, cierre | S1-S5 | inline (per-action: suite en foreground una vez) | done (backend 120 passed, watch suite green, openapi parse ok) | — |

Forecast: ~550 líneas autoradas (provider 120 + entity/migración/orquestador 150 + backend/contratos 200 + docs 80). Running: ~880 (T1 ~290, T2 ~285, T3 ~260, T4 41). Forecast superado por tests + contratos; slices se mantuvieron <400 por commit. Slice: un commit por tarea (~100-150 líneas c/u); si T3 supera 400, partir en T3a (modelo+migración) / T3b (ingest+read+WS). Estrategia `ask-on-risk`: preguntar cadena solo si se supera el presupuesto antes del siguiente commit.

## Log

- L1 (2026-10-18): "Quiero agregar como artefacto de medicion una banda polar h10. Investiga la documentacion de como puedo capturar sus señales con una esta aplicacion. La idea es cruzar informacion para validarla, seria validar lo que obtenemos de la banda con lo que obtenemos del smartwatch"
- L2 (2026-10-18): "Dale, agregalo. El ECG no es importante por ahora"
- L3 (2026-10-18): "Quiero data del ACC asi que si sera necesario el uso del SDK"
- L4 (2026-10-18): "No puedo dejar el galaxy watch 4 afuera. Entonces no se hara uso del SDK" — decisión: sin SDK (minSdk 30 intacto); ACC diferido; T1 GATT (BPM+RR) sigue como vía oficial.
- Investigación (S2): Polar BLE SDK exige minSdk 33 vs watch minSdk 30 → se elige GATT estándar HRS para MVP (BPM+RR, sin ECG, sin dependencia JitPack). H10: HR 1 Hz + RR ms + broadcast + ECG 130 Hz (diferido) + ACC (diferido). Fuentes: polar-ble-sdk README, PolarH10.md, PolarOnlineStreamingApi.
- Mapa (explorer ses_eb053643dffeu87hSW8WMaT1hA): sin `source` en ningún nivel; `extra="forbid"` obliga cambio atómico; manifest sin permisos BLE; Room v6 con precedente migración-por-columna; frontend `types.ts` ya deriva en `hr_status`; WS vía `manager.publish` en ingest.
- Decisión: colector = el propio watch (mismo reloj, mismo `POST /measurements`, misma sesión) para evitar sincronía NTP entre dispositivos.
- Cierre (2026-10-18): T1-T5 done en rama `feat-polar-h10-validation` (975c1b8, 28685ec, b307f63, 59233f7). Backend 120 passed, watch suite green, vitest 14/14. Falta: push + PR (decisión del operador) y validación con banda real (`-PpolarDeviceId`).
- L5 (2026-10-18): "pon para que la app muestre el dialogo de permisos en runtime"
- S8 (2026-10-18): "A" — método elegido: GATT directo desde el reloj (investigación previa: Samsung Health no usa la banda pero el OS sí permite BLE; doc oficial exige FGS `connectedDevice` para BLE en background)
- L6 (2026-10-18): T7 en curso en rama `feat-polar-h10-validation`.
- S11 (2026-10-18): "Permite ajustar el minimo y el maximo del eje y del tachograma"
- S10 (2026-10-18): "Me refiero a los ibis, el bpm esta bien que sea estable" — colapsar IBIs polares idénticos consecutivos; BPM estable y Samsung intactos.
- S9 (2026-10-18): "quiero que el grafico permita filtrar enntre dispositivo de medicion y el filtrado de la banda elimine mediciones repetidas consecutivas"
- L8 (2026-10-18): T8 display-layer solo frontend; dedupe = duplicados exactos consecutivos (timestamp+BPM+IBI), BPM estable con timestamps distintos se preserva.
- L7 (2026-10-18): validación VIVA — 47 filas nulas iniciales → causa raíz en cadena: (1) APK sin deviceId, (2) key de cifrado backend, (3) ubicación apagada suprimía scans, (4) FGS `health|connectedDevice` crasheaba (revertido), (5) grants de salud borrados por reinstall (re-otorgados), (6) teléfono ocupaba la banda. Fix: watchdog + single-connect + neverForLocation + LOW_LATENCY. Polar ~1 Hz BPM+RR + Samsung en paralelo.
