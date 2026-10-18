# Protocolo de validación Polar H10 vs Galaxy Watch (polar-h10-validation T4)

Compara la banda (ground truth) contra el reloj en la misma ventana temporal.
`source` distingue los streams en `clinical.measurements` (`samsung_watch` | `polar_h10`).

## Sesión

1. En el reloj, compilar con la banda: `./gradlew :app:assembleDebug -PpolarDeviceId=H10_XXXX`
   (el filtro matchea nombre o dirección BLE; vacío = solo Samsung).
2. Misma cama/paciente, 5–10 min reposo + 2–3 min movimiento leve (caminar/mover muñeca).
3. Ambas fuentes suben al mismo `POST /measurements`; nada que sincronizar:
   el reloj pone el mismo `timestamp` (mismo reloj) y el backend sella `received_at`.

## Métricas (por ventana ±1 s alrededor de cada muestra Samsung)

- **BPM:** MAE / RMSE Polar vs Samsung; Bland-Altman (bias ± 1.96σ).
- **RR vs IBI:** comparar `polar.ibis_ms` contra `samsung.ibis_ms` (misma unidad, ms).
- **Cobertura por `hr_status`:** % de muestras Samsung `hr_status=1` dentro de ±3 BPM
  del Polar; distribución de `hr_status` (`-2`/`-3`/`-8`/`-10`) en tramos donde
  el Polar pierde o degrada señal.
- **Off-wrist:** tramos `hr_status=-3` o BPM nulo Samsung vs continuidad del Polar.

## Ejemplo (psql)

```sql
-- pares Samsung<->Polar a menos de 1 s
SELECT s.timestamp, s.heart_rate_bpm AS samsung_bpm, s.hr_status,
       p.heart_rate_bpm AS polar_bpm,
       abs(s.heart_rate_bpm - p.heart_rate_bpm) AS abs_err
FROM clinical.measurements s
JOIN clinical.measurements p
  ON p.patient_id = s.patient_id
 AND p.source = 'polar_h10'
 AND abs(extract(epoch from (p.timestamp - s.timestamp))) <= 1
WHERE s.patient_id = '<uuid>' AND s.source = 'samsung_watch'
ORDER BY s.timestamp;
```

Criterio de aceptación operativo (a fijar con datos reales, no bloquea el merge):
MAE ≤ 3 BPM en reposo con `hr_status=1`; los tramos con `hr_status != 1`
deben explicar los outliers en vez de esconderse en el promedio.
