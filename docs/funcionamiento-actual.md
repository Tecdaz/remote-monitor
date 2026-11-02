# Cómo funciona la app hoy

Estado real del pipeline punta a punta (noviembre 2026). Lo que sigue
describe comportamiento verificado en el stack local, no la intención
original.

## Objeto de medición: banda Polar H10

La banda es el **único dispositivo de medición**. La app Android lee la
banda por BLE y sube los lotes al backend; ya no usa los sensores del
reloj. El mismo APK corre en un reloj Wear OS, un teléfono o una tablet
Android: el dispositivo anfitrión solo aporta BLE, red y pantalla.

| Señal | Fuente | Cadencia |
|---|---|---|
| BPM + RR (IBI) | GATT Heart Rate Service (`PolarHrsProvider`, sin SDK) | ~1 Hz |
| ECG 130 Hz en µV | Polar BLE SDK 8.4.0 (`PolarEcgProvider`), ventana deslizante de 10 s con paso de 1 s (solape 9 s) | 1 tira/s en régimen |

`POLAR_DEVICE_ID` tiene como valor por defecto `"Polar H10"`, así que la
app funciona sin flags de compilación. Para apuntar a otra banda:

```bash
cd watch
./gradlew :app:assembleDebug -PpolarDeviceId=H10_ABC123
```

Detalles del ECG: el SDK exige el deviceId exacto, así que el provider lo
resuelve por `searchForDevice` y espera `FEATURE_POLAR_ONLINE_STREAMING`
antes de pedir settings. Los snippets se trocean con `PolarEcgChunker`
(1300 muestras) y cada tira es **una fila Room propia** con
`heartRateBpm = null`, `ecg_uv` + `ecg_sample_rate_hz` (Room v9).

## Modelo de datos: solo banda

`clinical.measurements` guarda únicamente datos de la banda:

| Campo | Significado |
|---|---|
| `heart_rate_bpm` | BPM del H10 (1..299) |
| `ibis_ms` | Intervalos RR crudos en milisegundos |
| `ecg_uv` | Tira ECG en µV (1..1300 muestras) |
| `ecg_sample_rate_hz` | Frecuencia de la tira (130) |

Los campos del reloj (`spo2_percent`, `ibis_status`, `hr_status`,
`source`) fueron eliminados del modelo, los contratos, la API y el
dashboard. La migración `remove_watch_only_fields` borra las filas
históricas con `source='samsung_watch'` y elimina las columnas. Room
migra de v8 a v9 preservando las filas de banda pendientes de subida.

## Subida: cada 1 segundo

`SyncForegroundService` corre `BatchUploadWorker.runOnce()` cada
`SYNC_INTERVAL_MS = 1_000 ms`. Lotes de hasta 1000 filas con
`delete-after-echo`: el dispositivo solo borra los `local_id` del
`accepted_ids` del 200; lo demás se reintenta. Costo: más radio/batería,
a cambio de ~2–3 s de la banda a la pantalla (1 s sensor + 1 s subida +
POST ngrok ~0,5 s + WS inmediato).

El servicio usa el tipo `connectedDevice` (Android 14+), porque mantiene
el enlace BLE con la banda. En Wear OS además publica una
`OngoingActivity`; en teléfonos y tablets queda solo la notificación
persistente.

## Backend: acepta, valida, retransmite

- `MeasurementBatch` acepta `ecg_uv` (1–1300 enteros en ±100000 µV) +
  `ecg_sample_rate_hz` (= 130), ambas-o-ninguna.
- `ibis_ms` se valida en `[1, 5000]`.
- Ingest persiste la fila tal cual y la incluye en el `measurement.created`
  del WebSocket.
- Contratos al día: `openapi.yaml`, `data-models.md`,
  `websocket-types.ts`, `asyncapi.yaml`.

## App Android: reloj, teléfono y tablet

- **Reloj Wear OS**: pantallas Compose for Wear OS (carrusel de camas,
  home con BPM grande).
- **Teléfono/tablet**: pantallas Material 3 con grilla adaptativa
  (2 columnas en teléfono, 3–4 en tablet) y tarjetas de vitales.
- **Manifiesto**: `android.hardware.type.watch` es opcional; BLE es
  obligatorio; no se piden permisos de salud del reloj
  (`BODY_SENSORS`, `health.READ_*`).
- **Permisos en runtime**: `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT` y
  `POST_NOTIFICATIONS`.

## Frontend: tiempo real con costo acotado

El WS escribe en la cache de TanStack Query (`setQueryData` +
`mergeMeasurement`, tope 300 filas) y cada tick de 1 Hz re-renderiza.
Para que no se arrastre, el trabajo pesado está acotado:

| Gráfico | Estrategia |
|---|---|
| Tacograma (~60 pts) | Recompute completo por tick; barato |
| IBI anómalos | `out-of-range` (fuera de 300–2000 ms) y `ectopic-beat` (salto > 20 % vs. previo, sin comparar a través de huecos > 3 s). Punto rojo + motivo en tooltip + contador |
| Espectro HRV | PSD recalculado cada 5 s (las bandas se mueven en minutos) |
| Poincaré (600 pts, tope) | Scatter memoizado; solo re-renderiza si hay puntos nuevos |
| Tira ECG | Ventana de 10 s que avanza 1 s por tira; la vista grafica 1 de cada 2 muestras (650 pts, la DB guarda 130 Hz completos) y anima **solo el segundo nuevo** |

El dashboard solo consume la banda: no hay filtro de dispositivo ni
modo crudo/filtrado, porque ya no existe el flag de calidad del sensor
Samsung.

## Latencia punta a punta (medida)

Banda ~1 s → subida ~1 s → POST ngrok ~0,5 s → WS inmediato → render
1 Hz. Total **~2–3 s**. El ECG llega en bloques de 10 s solapados, no
muestra a muestra: la fluidez visual la da la animación, no más datos.

## Verificarlo

```bash
# Cadencia de POSTs del dispositivo (esperado: ~1/s)
adb shell 'logcat -d' | grep -c 'POST.*/measurements'

# Tiras ECG en DB (esperado: ~1/s en régimen)
docker exec remote-monitor-postgres-1 psql -U postgres -d remote_monitor \
  -c "SELECT count(*) FROM clinical.measurements WHERE ecg_uv IS NOT NULL;"

# Columnas eliminadas (esperado: 0 filas)
docker exec remote-monitor-postgres-1 psql -U postgres -d remote_monitor \
  -c "SELECT column_name FROM information_schema.columns WHERE table_schema='clinical' AND table_name='measurements' AND column_name IN ('spo2_percent','ibis_status','hr_status','source');"

# Backend + frontend vivos
curl -o /dev/null -w "%{http_code}\n" http://localhost:8000/api/v1/readyz
curl -o /dev/null -w "%{http_code}\n" http://localhost:3000/
```

## Límites conocidos

- `pytest` local corre **contra la DB de desarrollo** y sus fixtures la
  truncan: correr el backend-suite borra la sesión clínica real (los
  pendientes sobreviven en Room y se resuben). Re-onboarding en el
  dispositivo después de cada corrida.
- ECG a 1 tira/s ≈ 10× filas vs. bloques de 10 s (~420 KB/min). OK para
  sesiones PoC; revisar antes de uso prolongado.
- `minSdk 33` es el piso del Polar BLE SDK 8.4.0. Teléfonos o tablets
  con Android 12 o anterior no pueden instalar el APK.
- El ECG del H10 requiere el SDK oficial; el BPM/RR usa GATT estándar.
