# feat-polar-ecg-integration — ECG Polar H10 vía SDK oficial (snippets 10s)

Objective: integrar Polar BLE SDK para streaming ECG del H10 en rachas de 10s a 130 Hz como ground truth para validar HRV Samsung, manteniendo RR continuo GATT. Branch: `feat-polar-ecg-integration`. Delivery: `ask-on-risk`. Runner watch: `./gradlew :app:testDebugUnitTest`. Runner backend: `uv run pytest`. Runner frontend: `bun run test --run`.
Branch-from: main. Forecast: ~650 líneas autoradas (watch SDK+EcgProvider 200 + Room migración 80 + backend/contracts 200 + frontend strip 120 + docs 50). Running: 0. Estrategia: `ask-on-risk` (preguntar cadena solo si se supera ~400 antes del siguiente commit). Mirror Engram: pending (sin canal Engram en este runtime; re-sincronizar cuando esté disponible).

## Specs

- S1: "Se puede usar el sdk de polar? sobre esta app?"
- S2: "Integralo por el ECG"
- S3 (decisión alcance 2026-10-18): "Snippets 10s (Recomendado)" — SDK para ECG en rachas de 10s a 130 Hz para validar HRV contra Samsung, más RR continuo. Volumen manejable. Requiere subir minSdk 30 → 33 (Watch 4 es API 36, sigue soportado; se caen API 30-32).
- S4 (derivada, autorizada por S2+S3): cada snippet ECG lleva `source='polar_h10'`, 1300 muestras (~10s @130Hz, µV), timestamp anclado al mismo reloj; backend/contracts/frontend lo propagan como artefacto de validación, no como vital continuo.
- S5 (2026-10-18): "Compila la app, pausa las mediciones de samsung, solo usaremos las mediciones de la banda" — modo band-only: flag `-PpolarOnly=true` (BuildConfig `POLAR_ONLY`) pausa el pipeline Samsung; Polar pasa a ser fuente única y dueña de `healthState`.
- Aceptación: (1) SDK Polar vía JitPack compila con minSdk 33; (2) provider ECG emite snippets 10s parseados (µV, 130 Hz); (3) Room persiste snippet + backfill sin perder pendientes; (4) backend acepta/persiste/devuelve ECG, rechaza desconocido; (5) frontend muestra strip o metadato; (6) suites watch+backend+frontend verdes.

## Tasks

| ID | Title | S# | Route (trigger evidence) | Status | Commit |
|----|-------|----|--------------------------|--------|--------|
| T1 | Watch: Polar BLE SDK (JitPack, minSdk 33, permisos) + `PolarEcgProvider` snippets 10s + unit tests | S2,S3 | inline (single unit, sin razón Writer) | done (GREEN: PolarEcgChunkerTest 4/4 + :app:testDebugUnitTest green, SDK 8.4.0 compila) | — |
| T6 | Watch: modo band-only (`-PpolarOnly`, `samsungEnabled=false`, Polar dueña de health) | S5 | inline (flag + wiring, sin razón Writer) | done (suite green; APK band-only 52 MB compilado e instalado OK en reloj) | — |
| T2 | Watch: Room `ecg_uv`/`ecg_sample_rate_hz` + migración 7_8 + `runEcgLoop` + wiring + tests | S4 | inline (single unidad lógica, sin razón Writer) | done (GREEN: suite watch + schema 8.json + chunker/loop/serialización) | — |
| T3 | Backend+contracts: `ecg_uv`/`ecg_sample_rate_hz` en Pydantic/modelo/migración/ingest/routers/WS + openapi/data-models/ws-types/frontend types + tests | S4 | inline (single unidad lógica, sin razón Writer) | done (GREEN: 125 backend, migración aplicada en dev DB) | — |
| T4 | Frontend: tira ECG (`selectLatestEcgSnippet` + `EcgStripChart` + wiring + tests) | S4 | inline (display-layer, sin razón Writer) | done (GREEN: tsc + 64 vitest) | — |
| T5 | Verification + deploy: containers backend/frontend, APK band-only con ECG, install | S1-S4 | inline (per-action) | done (ECG end-to-end verificado: strips 1300 @130Hz polar_h10 fluyendo a DB, tira visible en dashboard) | — |

## Log

- L1 (2026-10-18): "Se puede usar el sdk de polar? sobre esta app?"
- L2 (2026-10-18): "Integralo por el ECG"
- L3 (2026-10-18): alcance elegido "Snippets 10s (Recomendado)" — minSdk 30 → 33 aceptado (Watch 4 API 36 intacto).
- Investigación (S1): Polar BLE SDK exige minSdk 33 (README Quickstart) vs watch minSdk 30 → bump necesario; H10 ECG 130 Hz online-streaming, RR continuo por HRS; SDK Apache-compatible, JitPack `com.github.polarofficial:polar-ble-sdk`. GATT actual sin SDK se mantiene para RR; SDK solo para ECG. Fuente: github.com/polarofficial/polar-ble-sdk README + PolarH10.md.
- Mapa: `PolarHrsProvider` GATT puro (sin SDK), `SensorOrchestrator` dual Samsung+Polar, `MeasurementEntity` sin `ecg`, backend `ClinicalMeasurement` sin `ecg`, contracts sin `ecg`, `minSdk=30` en `app/build.gradle.kts`.
- L4 (2026-10-18): "Compila la app, pausa las mediciones de samsung, solo usaremos las mediciones de la banda" + "Instalalo" — modo band-only (`-PpolarOnly`) compilado e instalado; app lanzada en el reloj.
- L5 (2026-10-18): "Cablealo" — T2+T3+T4 en verde (watch schema v8, backend 125/125, frontend tsc+64); deploy backend+frontend OK; APK band-only con ECG instalado.
- L6 (2026-10-18, monitoreo logcat): dos hallazgos. (1) `PolarInvalidArgument` en `connectToDevice`: el SDK exige el deviceId exacto, no el filtro de nombre — fix: resolver por `searchForDevice` + fallback al filtro (APK recompilando). (2) HTTP 500 `Wrong key or corrupt data`: mis corridas de `pytest` local truncaron las tablas dev (diseño del repo: tests contra dev DB) y dejaron filas cifradas con la test-key — DB limpiada (DROP + migrate a head `add_ecg_columns`). Re-onboarding requerido en el reloj: elegir cama de nuevo; las ~328 filas en Room se conservan por delete-after-echo y se resuben bajo la nueva sesión.
- L7 (2026-10-18): "Quiero que se vea en real time como en un monitor clinico, el reloj debe enviar al backend lo mas rapido posible" + "Quiero que el ecg tambien llegue cada segundo" — `SYNC_INTERVAL_MS` 5s→1s; ECG a ventana deslizante 10s con stride 1s (`chunkSliding`, ~10× filas, display diezmado ×2). Verificado: 46 strips en 60s (~1/s con 10s de prime) + frontend 200.
