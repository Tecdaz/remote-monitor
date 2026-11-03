# Datos de la banda Polar H10: cadencia, propiedades y lectura

Este documento describe **qué envía la banda, con qué cadencia, qué forma
tienen los datos y cómo leerlos** en cada capa del sistema. La fuente de
verdad del contrato es `contracts/openapi.yaml`; los detalles de
implementación viven en `watch/app/src/main/java/com/remotemonitor/watch/sensor/`.

La banda es el único dispositivo de medición. La app Android (reloj,
teléfono o tablet) actúa como puente BLE y sube los datos al backend.

---

## 1. Cadencia

| Señal | Qué emite la banda | Cada cuánto | Qué genera en la app |
|---|---|---|---|
| **BPM + RR (IBI)** | Notificación del Heart Rate Service (GATT `0x2A37`) | ~1 notificación/s en el H10. La app **no fuerza** la frecuencia: depende de la banda. | Una fila de Room por notificación. |
| **ECG** | Stream Polar SDK a 130 Hz (µV) | 130 muestras/s continuas. | Una tira de 1300 muestras **cada 1 s** una vez cebado el buffer de 10 s. |
| **Subida** | Lote HTTP de hasta 1000 filas | El loop del servicio corre cada **1 s**; solo hace `POST` si hay filas pendientes. | Hasta 1 POST/s, siguiendo la cadencia de la banda. |
| **WebSocket** | `measurement.created` por fila aceptada | Inmediato después del commit del lote. | El dashboard actualiza la cache de TanStack Query. |

### 1.1 BPM + RR

- La banda notifica aproximadamente **una vez por segundo**.
- Cada notificación puede traer **0, 1 o varios intervalos RR** (latidos
  acumulados desde la notificación anterior).
- La app guarda una fila por notificación con `timestamp` = momento de
  recepción en el anfitrión y `ibis_ms` = lista de RR de esa notificación.
- Si la banda no entrega muestra (pérdida de enlace, sin contacto), el
  proveedor emite `null` y la app guarda una fila con
  `heart_rate_bpm = null` e `ibis_ms = null`. Es una fila de “sin dato”,
  no un BPM inventado.
- Un BPM `<= 0` se descarta: no genera fila.

### 1.2 ECG

- El SDK Polar entrega muestras a **130 Hz** (una cada 7,692 ms), en µV.
- La app acumula muestras y recién publica cuando tiene **1300 muestras
  (10 s)**.
- A partir de ahí publica **una tira por segundo**, con ventana deslizante
  de 10 s y **solape de 9 s** (1170 muestras se repiten entre tiras
  consecutivas).
- La primera tira aparece ~10 s después de arrancar el stream; después,
  una por segundo.
- El `timestamp` de la tira es el momento de **emisión** (extremo más
  nuevo de la ventana), no el de la primera muestra. La primera muestra
  está ~9,99 s antes.

### 1.3 Subida y borrado

`SyncForegroundService` ejecuta `BatchUploadWorker.runOnce()` cada
`1_000 ms`:

1. Lee hasta 1000 filas pendientes de Room.
2. Si no hay filas, no hay `POST`.
3. Si hay filas, hace `POST /api/v1/patients/{patient_id}/measurements`.
4. Con 2xx, borra **solo** los `local_id` que vuelven en `accepted_ids`
   (`delete-after-echo`).
5. Con error HTTP o de red, no borra nada y reintenta.

### 1.4 ¿Por qué hay varias filas en el mismo segundo?

Ver varias filas con el mismo segundo **no significa que todas sean
duplicados**. Hay cuatro causas distintas:

| Causa | Cómo se ve en los datos | ¿Es duplicado? |
|---|---|---|
| **Dos señales distintas** | Una fila con `ecg_uv` no nulo y `heart_rate_bpm = null`; otra con `ibis_ms`/BPM. | No. Son ECG y BPM/RR. |
| **Varios RR en una notificación** | Una sola fila con `ibis_ms = [833, 820]`. | No. Son dos latidos en la misma notificación. |
| **La banda repite la última ventana RR** | Varias filas con el mismo `heart_rate_bpm` y el mismo `ibis_ms`, pero `timestamp` distinto. | Sí, es repetición de transporte. |
| **Re-emisión de la UI** | La pantalla actualiza el BPM o la hora varias veces sin una fila nueva. | No. Room invalida la consulta en cada insert. |

#### Dos señales distintas

Cada segundo se genera, como mínimo, una fila de BPM/RR y una tira de
ECG. Por eso `clinical.measurements` puede tener **2 filas por segundo**
sin que nada esté repetido. La tira ECG tiene `heart_rate_bpm = null` y
`ecg_uv` no nulo.

#### Varios latidos dentro de una fila

Una notificación HRS puede traer varios intervalos RR. La app guarda una
**sola fila** por notificación, con la lista completa en `ibis_ms`. El
tacograma expande esa lista en varios puntos, uno por latido. A 72 lpm hay
1,2 latidos por segundo; a 120 lpm, 2. Ver varios puntos por segundo en el
gráfico es fisiológicamente normal.

#### La banda repite la última ventana RR

El H10 puede repetir en la notificación siguiente un RR que ya había
enviado. El código ya lo contempla:

- `PolarHrsProvider` emite una lectura por **cada** notificación, sin
  throttle ni deduplicación.
- `SensorOrchestrator` inserta una fila por emisión con un `local_id`
  nuevo y el `timestamp` de recepción.
- El backend es idempotente por `local_id`, no por contenido: dos filas con
  el mismo BPM/RR quedan persistidas.
- El frontend lo colapsa para graficar: `selectIBISeries` descarta IBIs
  consecutivos idénticos y `dedupeConsecutivePolarReadings` descarta filas
  con mismo `timestamp` + BPM + IBI.
- `HomeViewModel` puede re-emitir el mismo BPM o la misma hora porque Room
  invalida `lastHeartRate`/`lastTimestamp` en **cualquier** insert,
  incluidas las tiras ECG.

#### Diagnóstico

Filas recientes con la señal identificada:

```sql
SELECT timestamp,
       heart_rate_bpm,
       ibis_ms,
       ecg_uv IS NOT NULL AS es_ecg
FROM clinical.measurements
WHERE patient_id = '<uuid>'
ORDER BY timestamp DESC
LIMIT 20;
```

Agrupación por segundo:

```sql
SELECT date_trunc('second', timestamp) AS segundo,
       count(*) AS filas,
       count(*) FILTER (WHERE ecg_uv IS NOT NULL) AS ecg,
       count(*) FILTER (WHERE ibis_ms IS NOT NULL) AS rr,
       count(*) FILTER (WHERE heart_rate_bpm IS NOT NULL) AS bpm,
       coalesce(sum(cardinality(ibis_ms)), 0) AS latidos
FROM clinical.measurements
WHERE patient_id = '<uuid>'
GROUP BY 1
ORDER BY 1 DESC
LIMIT 20;
```

Lectura esperada:

- `ecg = 1`, `rr = 1`, `bpm = 1` → normal: una fila de cada señal.
- `rr > 1` → la banda notificó más de una vez en ese segundo.
- `latidos > bpm` → una notificación trajo varios RR.
- `filas` altas con `ibis_ms` idénticos → repetición de la ventana RR.
- `ecg > 1` → revisar el chunker; en régimen debería ser una tira/s.

Repeticiones exactas de contenido:

```sql
SELECT heart_rate_bpm,
       ibis_ms,
       count(*) AS veces,
       min(timestamp) AS primera,
       max(timestamp) AS ultima
FROM clinical.measurements
WHERE patient_id = '<uuid>'
  AND ibis_ms IS NOT NULL
GROUP BY heart_rate_bpm, ibis_ms
HAVING count(*) > 1
ORDER BY veces DESC;
```

Si `ibis_ms` y `heart_rate_bpm` se repiten con timestamps distintos, es la
banda repitiendo la ventana. Si además el `timestamp` es idéntico, es una
notificación duplicada.

#### Qué hacer

- **No borres las filas crudas:** son la trazabilidad de lo que mandó la
  banda.
- Deduplicá al leer: RR por IBIs consecutivos idénticos (como ya hace
  `selectIBISeries`); ECG sin sumar tiras consecutivas como si fueran
  independientes (se solapan 9 s).
- Si necesitás una vista ya deduplicada para análisis, creá una `VIEW` o
  una consulta con `DISTINCT ON` por ventana; no cambies la ingesta.

---

## 2. Propiedades de los datos

### 2.1 Fila de medición (REST, WebSocket, Room)

| Campo | Tipo | Unidad / rango | Nulo | Origen |
|---|---|---|---|---|
| `local_id` | string UUID v4 | — | No | Generado por la app; clave de idempotencia. |
| `timestamp` | string ISO 8601 UTC | `2026-11-02T23:36:42.465Z` | No | Reloj del anfitrión al recibir/emitir. |
| `heart_rate_bpm` | integer | 1..299 | Sí | BPM del H10. Nulo en filas de ECG o sin muestra. |
| `ibis_ms` | array de integer | cada valor 1..5000 ms | Sí | Intervalos RR de la notificación HRS. |
| `ecg_uv` | array de integer | 1..1300 muestras; cada una -100000..100000 µV | Sí | Tira ECG del Polar SDK. |
| `ecg_sample_rate_hz` | integer | 130 | Sí | Debe venir junto con `ecg_uv` (ambas-o-ninguna). |
| `id` | string UUID | — | No | Asignado por el backend. |
| `patient_id` | string UUID | — | No | UUID opaco del paciente. |
| `received_at` | string ISO 8601 UTC | — | No | Momento de ingesta en el backend. |

**Ya no existen** `spo2_percent`, `ibis_status`, `hr_status` ni `source`.
Un cliente viejo que los envíe recibe un rechazo por ítem (`extra="forbid"`).

### 2.2 Significado exacto de `ibis_ms`

- Son intervalos **entre latidos** (RR), no muestras equiespaciadas.
- El parser BLE recibe cada RR como entero de 16 bits en unidades de
  **1/1024 s** y lo convierte a milisegundos con división entera:
  `rr_ms = raw * 1000 / 1024`.
- El orden de la lista es el de la notificación: el **último** valor es el
  latido más reciente y está anclado al `timestamp` de la fila.
- Para reconstruir el tiempo de cada latido, se camina hacia atrás desde
  el `timestamp` restando los RR:

```text
T = timestamp de la fila
para i = último..primero:
    tiempo[i] = T - offset
    offset += ibis_ms[i]
```

Ejemplo: `timestamp = 10:00:00.000`, `ibis_ms = [800, 820]`:

| Latido | Tiempo | IBI |
|---|---|---|
| 1 | 09:59:59.180 | 800 ms |
| 2 | 10:00:00.000 | 820 ms |

### 2.3 Significado exacto de `ecg_uv`

- Son **voltajes** en microvoltios, muestreados uniformemente a 130 Hz.
- Una tira de 1300 muestras cubre 10 s.
- La tira es la **ventana más reciente** al momento de emitir; la primera
  muestra está ~9,99 s antes del `timestamp`.
- Las tiras consecutivas se solapan 9 s. No las sumes como si fueran
  segmentos independientes: para análisis, usa una tira por segundo o
  recorta el solape.
- `ecg_sample_rate_hz` siempre vale `130`. Si falta uno de los dos campos,
  el backend rechaza el ítem.

### 2.4 Filas que produce cada evento

| Evento de la banda | `heart_rate_bpm` | `ibis_ms` | `ecg_uv` / `ecg_sample_rate_hz` |
|---|---|---|---|
| Notificación HRS con BPM > 0 | BPM | Lista de RR o `null` | `null` / `null` |
| Notificación sin muestra | `null` | `null` | `null` / `null` |
| Tira ECG | `null` | `null` | 1300 muestras / 130 |
| BPM = 0 | No se guarda | — | — |

### 2.5 Reloj, latencia y orden

- La banda **no entrega un timestamp de hardware** en el flujo GATT. El
  `timestamp` de la fila lo pone el reloj del anfitrión
  (`System.currentTimeMillis()`) al recibir la notificación o al emitir la
  tira ECG.
- `received_at` lo pone el backend al persistir. La diferencia
  `received_at - timestamp` aproxima la latencia de transporte **solo si
  el reloj del anfitrión está sincronizado**.
- Para ordenar filas de forma estable usa `(timestamp, id)`, que es la
  misma clave de la paginación keyset del backend.
- Un reloj de anfitrión atrasado o adelantado desplaza la serie completa;
  no cambia la forma de los RR ni del ECG.

---

## 3. Cómo leer los datos

### 3.1 BLE crudo (GATT Heart Rate Service)

- Servicio: `0x180D`; característica de medición: `0x2A37`.
- La app se suscribe a notificaciones y parsea con `PolarHrsParser`.

Layout de la notificación (Bluetooth SIG):

| Byte | Campo |
|---|---|
| 0 | Flags |
| 1..2 | BPM (`uint8`, o `uint16` LE si `flags & 0x01`) |
| opcional | Energía (`uint16` LE si `flags & 0x08`) |
| opcional | RR (`uint16` LE cada uno, en 1/1024 s, si `flags & 0x10`) |

Ejemplo: `10 48 55 03 48 03`

- `0x10` = RR presente, BPM de 8 bits.
- `0x48` = 72 BPM.
- `0x0355` = 853 → `853 * 1000 / 1024` = **833 ms**.
- `0x0348` = 840 → **820 ms**.
- Resultado: `heart_rate_bpm = 72`, `ibis_ms = [833, 820]`.

### 3.2 ECG crudo (Polar SDK)

La app usa `PolarBleApi.startEcgStreaming(...)` con la configuración máxima
del H10. Cada `EcgSample.voltage` es un entero en µV. La app acumula y
trocea con `PolarEcgChunker.chunkSliding(...)`; no necesitás hablar con el
SDK para consumir los datos: ya llegan por REST/WS.

### 3.3 Room (almacenamiento local)

La tabla `measurements` guarda las filas pendientes de subida:

```sql
-- Últimas 10 filas
SELECT local_id, timestamp, heart_rate_bpm, ibis_ms, ecg_uv, ecg_sample_rate_hz
FROM measurements
ORDER BY timestamp DESC
LIMIT 10;

-- Filas con RR
SELECT timestamp, ibis_ms FROM measurements
WHERE ibis_ms IS NOT NULL ORDER BY timestamp DESC LIMIT 20;

-- Tiras ECG (la columna es JSON TEXT; el conteo se hace en código)
SELECT timestamp, ecg_uv, ecg_sample_rate_hz
FROM measurements WHERE ecg_uv IS NOT NULL
ORDER BY timestamp DESC LIMIT 10;
```

`ibis_ms` y `ecg_uv` se guardan como texto JSON en Room
(`IbiListConverter`, `EcgListConverter`). En la API viajan como arrays
JSON reales.

### 3.4 REST

Listar mediciones de un paciente (paginación keyset):

```bash
curl "http://localhost:8000/api/v1/patients/<patient_id>/measurements?limit=100"
```

Parámetros:

| Parámetro | Uso |
|---|---|
| `from`, `to` | Ventana ISO 8601 inclusiva. |
| `limit` | 1..1000, por defecto 100. |
| `cursor` | `next_cursor` de la página anterior. |

Obtener una fila por ID:

```bash
curl "http://localhost:8000/api/v1/measurements/<measurement_id>"
```

Respuesta (recortada):

```json
{
  "items": [
    {
      "id": "3d0f...",
      "patient_id": "2cfb...",
      "local_id": "8a1e...",
      "timestamp": "2026-11-02T23:36:42.465Z",
      "received_at": "2026-11-02T23:36:43.120Z",
      "heart_rate_bpm": 72,
      "ibis_ms": [833, 820],
      "ecg_uv": null,
      "ecg_sample_rate_hz": null
    }
  ],
  "next_cursor": null
}
```

### 3.5 WebSocket

El backend empuja cada fila aceptada:

```json
{
  "type": "measurement.created",
  "data": {
    "id": "3d0f...",
    "patient_id": "2cfb...",
    "local_id": "8a1e...",
    "timestamp": "2026-11-02T23:36:42.465Z",
    "received_at": "2026-11-02T23:36:43.120Z",
    "heart_rate_bpm": 72,
    "ibis_ms": [833, 820],
    "ecg_uv": null,
    "ecg_sample_rate_hz": null
  }
}
```

Cliente mínimo en JavaScript:

```js
const ws = new WebSocket(`wss://<host>/ws/patients/${patientId}`);

ws.onmessage = (event) => {
  const message = JSON.parse(event.data);
  if (message.type === "measurement.created") {
    const m = message.data;
    console.log(m.timestamp, m.heart_rate_bpm, m.ibis_ms);
  }
  if (message.type === "ping") {
    ws.send(JSON.stringify({ type: "pong", ts: message.ts }));
  }
};
```

El frontend ya hace esto en `frontend/hooks/use-patient-websocket.ts` y
escribe en la cache de TanStack Query.

### 3.6 Consultas SQL directas

```sql
-- Serie de RR de una sesión
SELECT timestamp, unnest(ibis_ms) AS rr_ms
FROM clinical.measurements
WHERE patient_id = '<uuid>' AND ibis_ms IS NOT NULL
ORDER BY timestamp;

-- Tiras ECG y su tasa
SELECT timestamp, cardinality(ecg_uv) AS muestras, ecg_sample_rate_hz
FROM clinical.measurements
WHERE patient_id = '<uuid>' AND ecg_uv IS NOT NULL
ORDER BY timestamp DESC
LIMIT 20;

-- Huecos de BPM mayores a 5 s
SELECT timestamp,
       timestamp - lag(timestamp) OVER (ORDER BY timestamp) AS gap
FROM clinical.measurements
WHERE patient_id = '<uuid>' AND heart_rate_bpm IS NOT NULL
ORDER BY timestamp;
```

### 3.7 Frontend

- `frontend/lib/types.ts` define `Measurement` con los campos de banda.
- `selectIBISeries(measurements)` aplana y ordena los RR; reconstruye los
  tiempos de cada latido como en §2.2.
- `selectBeatsForChart(measurements)` agrega la clasificación de anomalías
  (`out-of-range`, `ectopic-beat`) para el tacograma.
- `selectLatestEcgSnippet(measurements)` toma la tira ECG más reciente.
- `dedupeConsecutivePolarReadings` colapsa notificaciones duplicadas
  (mismo timestamp, BPM e IBI) antes de graficar.

---

## 4. Ejemplo completo: una notificación HRS

Supongamos que el H10 envía a las `10:00:00.000` una notificación con
BPM 72 y dos RR (833 ms y 820 ms).

Fila en Room (antes de subir):

```json
{
  "local_id": "8a1e5b0e-6f2b-4c1e-9a3e-2d4f6a7b8c9d",
  "timestamp": "2026-11-02T13:00:00.000Z",
  "heart_rate_bpm": 72,
  "ibis_ms": [833, 820],
  "ecg_uv": null,
  "ecg_sample_rate_hz": null
}
```

Fila en REST/WS (después del backend):

```json
{
  "id": "3d0f4c2a-1b2c-4d5e-8f90-123456789abc",
  "patient_id": "2cfb118d-2dbc-4d67-8fe6-d767be9405f8",
  "local_id": "8a1e5b0e-6f2b-4c1e-9a3e-2d4f6a7b8c9d",
  "timestamp": "2026-11-02T13:00:00.000Z",
  "received_at": "2026-11-02T13:00:00.450Z",
  "heart_rate_bpm": 72,
  "ibis_ms": [833, 820],
  "ecg_uv": null,
  "ecg_sample_rate_hz": null
}
```

---

## 5. Validación y límites

- `POST /measurements` acepta **1..1000** ítems; más de 1000 devuelve 413.
- Cada ítem se valida por separado. Un ítem inválido va a `rejected` con
  un `reason`; el resto del lote se acepta.
- `ibis_ms` debe estar en `[1, 5000]` por elemento. Un `0` (centinela de
  “sin dato”) se rechaza.
- `heart_rate_bpm` debe estar en `[1, 299]`.
- `ecg_uv` y `ecg_sample_rate_hz` son **ambas-o-ninguna**; la tasa debe ser
  `130` y la tira tener entre 1 y 1300 muestras.
- Un cliente viejo que envíe `source`, `spo2_percent`, `ibis_status` o
  `hr_status` recibe `validation error: Extra inputs are not permitted`.
- `local_id` es idempotente: reenviar el mismo lote no duplica filas.
- La app solo borra de Room los `local_id` confirmados en `accepted_ids`.

---

## 6. Verificación rápida

```bash
# ¿Llegan filas de banda? (esperado: ~1/s)
docker exec remote-monitor-postgres-1 psql -U postgres -d remote_monitor \
  -c "SELECT count(*), max(timestamp) FROM clinical.measurements;"

# ¿Hay RR?
docker exec remote-monitor-postgres-1 psql -U postgres -d remote_monitor \
  -c "SELECT count(*) FROM clinical.measurements WHERE ibis_ms IS NOT NULL;"

# ¿Hay tiras ECG de 1300 muestras a 130 Hz?
docker exec remote-monitor-postgres-1 psql -U postgres -d remote_monitor \
  -c "SELECT cardinality(ecg_uv) AS muestras, ecg_sample_rate_hz, count(*) FROM clinical.measurements WHERE ecg_uv IS NOT NULL GROUP BY 1,2;"

# Cadencia de POSTs en el dispositivo
adb shell 'logcat -d' | grep -c 'POST.*/measurements'
```

Si el dashboard muestra una fila pero `ibis_ms` es `null`, la banda no
envió RR en esa notificación. Si `heart_rate_bpm` es `null` y `ecg_uv`
también, fue una notificación sin muestra (enlace caído o sin contacto).
