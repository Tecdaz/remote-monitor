# Protocolo de validación de la banda Polar H10

La banda es el único dispositivo de medición desde noviembre 2026. El
protocolo histórico de comparación banda vs. reloj Samsung fue retirado
junto con el pipeline Samsung; este documento valida la banda contra el
backend y el dashboard, no contra otro sensor.

## Preparación

1. Compilar el APK con el filtro de banda (por defecto ya es
   `"Polar H10"`):
   ```bash
   cd watch
   ./gradlew :app:assembleDebug -PpolarDeviceId=H10_XXXX
   ```
2. Emparejar la cama 1..5 desde la app (reloj, teléfono o tablet).
3. Verificar que la banda esté puesta y con batería.

## Sesión

1. 5–10 min de reposo.
2. 2–3 min de movimiento leve (caminar, mover los brazos).
3. 1–2 min con la banda fuera del cuerpo (para ver el estado off-wrist).
4. Volver al reposo 2 min.

Toda la sesión sube al mismo `POST /api/v1/patients/{id}/measurements`;
el backend sella `received_at` y el frontend recibe el WebSocket en vivo.

## Métricas a observar

- **Cadencia de subida:** ~1 POST/s por dispositivo con datos.
- **BPM + RR:** filas con `heart_rate_bpm` no nulo y `ibis_ms` no nulo a
  ~1 Hz. Huecos > 5 s indican caída de enlace o pausa del operador.
- **ECG:** una tira de 1300 muestras cada segundo (ventana de 10 s a
  130 Hz) en régimen (`ecg_uv` no nulo, `ecg_sample_rate_hz = 130`).
- **Off-wrist:** al retirar la banda, llegan lecturas nulas/ausentes y la
  UI muestra `Banda sin contacto`; al recolocarla, se recupera sin
  reiniciar la app.
- **Latencia:** banda → dashboard en ~2–3 s.
- **Batería:** registrar el consumo del anfitrión durante la sesión.

## Consultas (psql)

```sql
-- Cadencia y cobertura de la sesión
SELECT date_trunc('minute', timestamp) AS minute,
       count(*) FILTER (WHERE heart_rate_bpm IS NOT NULL) AS bpm_rows,
       count(*) FILTER (WHERE ibis_ms IS NOT NULL) AS rr_rows,
       count(*) FILTER (WHERE ecg_uv IS NOT NULL) AS ecg_rows
FROM clinical.measurements
WHERE patient_id = '<uuid>'
GROUP BY 1
ORDER BY 1;

-- Huecos mayores a 5 s entre lecturas de BPM
SELECT timestamp,
       timestamp - lag(timestamp) OVER (ORDER BY timestamp) AS gap
FROM clinical.measurements
WHERE patient_id = '<uuid>' AND heart_rate_bpm IS NOT NULL
ORDER BY timestamp;

-- Integridad de las tiras ECG
SELECT count(*) AS strips,
       min(cardinality(ecg_uv)) AS min_samples,
       max(cardinality(ecg_uv)) AS max_samples,
       min(ecg_sample_rate_hz) AS min_hz,
       max(ecg_sample_rate_hz) AS max_hz
FROM clinical.measurements
WHERE patient_id = '<uuid>' AND ecg_uv IS NOT NULL;
```

## Criterio de aceptación operativo

- **Cobertura BPM/RR:** ≥ 90 % de las muestras esperadas a 1 Hz en una
  ventana de 5 min de reposo, sin huecos > 5 s.
- **ECG:** ≥ 0,9 tiras/s en la misma ventana, cada una con 1300 muestras
  y `ecg_sample_rate_hz = 130`.
- **Latencia:** p95 ≤ 5 s entre `timestamp` de la medición y su render en
  el dashboard.
- **Sin pérdida:** con el backend caído 2 min, Room conserva las filas y
  las sube al recuperarse (`delete-after-echo`).
- **BLE en segundo plano:** el anfitrión no pierde el enlace al apagar la
  pantalla durante 5 min; `SyncForegroundService` mantiene el proceso.
- **Dashboard en vivo:** ~1 Hz sin recargar la página.
