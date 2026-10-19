# Cómo funciona la app hoy

Estado real del pipeline punta a punta (octubre 2026). Lo que sigue describe
comportamiento verificado en el stack local, no la intención original.

## Modo de operación: solo banda

El reloj mide **únicamente la Polar H10** (`-PpolarOnly=true` apaga el
pipeline Samsung; `-PpolarDeviceId="Polar H10"` la localiza por nombre).
Cada fila persiste con `source='polar_h10'`.

| Fuente | Señal | Cadencia |
|---|---|---|
| GATT HRS (`PolarHrsProvider`, sin SDK) | BPM + RR (IBI) | ~1 Hz |
| Polar BLE SDK 8.4.0 (`PolarEcgProvider`) | ECG 130 Hz en µV, ventana deslizante de 10 s con paso de 1 s (solape 9 s) | 1 tira/s en régimen |

Detalles del ECG: el SDK exige el deviceId exacto, así que el provider lo
resuelve por `searchForDevice` y espera `FEATURE_POLAR_ONLINE_STREAMING`
antes de pedir settings. Los snippets se trocean con `PolarEcgChunker`
(1300 muestras) y cada tira es **una fila Room propia** con
`heartRateBpm = null`, `ecg_uv` + `ecg_sample_rate_hz` (Room v8,
migración 7→8). Requiere `minSdk 33` (el Galaxy Watch 4 es API 36).

## Subida: cada 1 segundo

`SyncForegroundService` corre `BatchUploadWorker.runOnce()` cada
`SYNC_INTERVAL_MS = 1_000 ms` (antes 5 s). Lotes de hasta 1000 filas con
`delete-after-echo`: el reloj solo borra los `local_id` del `accepted_ids`
del 200; lo demás se reintenta. Costo: más radio/batería, a cambio de
~2–3 s de la muñeca a la pantalla (1 s sensor + 1 s subida + POST ngrok
~0,5 s + WS inmediato).

## Backend: acepta, valida, retransmite

- `MeasurementBatch` acepta `ecg_uv` (1–1300 enteros en ±100000 µV) +
  `ecg_sample_rate_hz` (= 130), ambas-o-ninguna. Migración en head:
  `add_ecg_columns`.
- Ingest persiste la tira tal cual y la incluye en el `measurement.created`
  del WebSocket, igual que el resto de los campos.
- Contratos al día: `openapi.yaml`, `data-models.md`,
  `websocket-types.ts`.

## Frontend: tiempo real con costo acotado

El WS escribe en la cache de TanStack Query (`setQueryData` +
`mergeMeasurement`, tope 300 filas) y cada tick de 1 Hz re-renderiza.
Para que no se arrastre, el trabajo pesado está acotado:

| Gráfico | Estrategia |
|---|---|
| Tacograma (~60 pts) | Recompute completo por tick; barato |
| IBI anómalos | `sensor-invalid` (flag), `out-of-range` (fuera de 300–2000 ms), `ectopic-beat` (salto > 20 % vs. previo, sin comparar a través de huecos > 3 s). Punto rojo + motivo en tooltip + contador |
| Espectro HRV | PSD recalculado cada 5 s (las bandas se mueven en minutos) |
| Poincaré (600 pts, tope) | Scatter memoizado; solo re-renderiza si hay puntos nuevos |
| Tira ECG | Ventana de 10 s que avanza 1 s por tira; la vista grafica 1 de cada 2 muestras (650 pts, la DB guarda 130 Hz completos) y anima **solo el segundo nuevo** (base estática + cabeza con dash-draw de ~0,9 s, respeta `prefers-reduced-motion`) |

## Latencia punta a punta (medida)

Sensor ~1 s → subida ~1 s → POST ngrok ~0,5 s → WS inmediato → render 1 Hz.
Total **~2–3 s**. El ECG llega en bloques de 10 s solapados, no muestra a
muestra: la fluidez visual la da la animación, no más datos.

## Verificarlo

```bash
# Cadencia de POSTs del reloj (esperado: ~1/s)
adb shell 'logcat -d' | grep -c 'POST.*/measurements'

# Tiras ECG en DB (esperado: ~1/s en régimen)
docker exec remote-monitor-postgres-1 psql -U postgres -d remote_monitor \
  -c "SELECT count(*) FROM clinical.measurements WHERE ecg_uv IS NOT NULL;"

# Backend + frontend vivos
curl -o /dev/null -w "%{http_code}\n" http://localhost:8000/api/v1/readyz
curl -o /dev/null -w "%{http_code}\n" http://localhost:3000/
```

## Límites conocidos

- `pytest` local corre **contra la DB de desarrollo** y sus fixtures la
  truncan: correr el backend-suite borra la sesión clínica real (los
  pendientes sobreviven en Room y se resuben). Re-onboarding en el reloj
  después de cada corrida.
- ECG a 1 tira/s ≈ 10× filas vs. bloques de 10 s (~420 KB/min). OK para
  sesiones PoC; revisar antes de uso prolongado.
- Sin referencia ECG simultánea al inicio no había validez de criterio;
  la banda ahora es ese ground truth (ver `docs/informe-pps.md` §6).
