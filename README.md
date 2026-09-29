# remote-monitor

Monorepo para una prueba de concepto de monitoreo remoto de pacientes. Tres
componentes comparten un mismo repositorio para que el reloj, el backend y el
dashboard evolucionen como hermanos: cambiá uno, aprendé del siguiente.

## ¿Qué hace la aplicación?

El flujo completo es así:

1. **Un operador coloca el reloj** (Galaxy Watch 4 con Wear OS 6) en la muñeca
   del paciente y selecciona una cama (1 a 5) desde el selector de la app
   del reloj. El backend deriva el `patient_number` determinísticamente a
   partir del `bed_number` (cifrado con `pgp_sym_encrypt`); el operador no
   tipea ningún identificador.
2. **El reloj recolecta signos vitales** — frecuencia cardíaca (BPM),
   intervalos entre latidos (IBI), saturación de oxígeno (SpO2 %) y el código
   de lifecycle del sensor Samsung (`hr_status`) — usando los sensores del
   Samsung Health Sensor SDK. Las mediciones se guardan localmente en Room
   (SQLite) y se suben en lotes al backend.
3. **El backend recibe, valida y persiste** las mediciones en PostgreSQL, y
   retransmite las actualizaciones en tiempo real por WebSocket a los
   frontends conectados (un canal por paciente en
   `wss://{host}/ws/patients/{patient_id}`).
4. **El dashboard muestra el estado en vivo** de cada paciente sin necesidad
   de refrescar la página: grilla de ocupación de camas, tacograma (IBI vs.
   tiempo), gráfico de frecuencias de HRV (VLF / LF / HF) y mapa de Poincaré
   (IBI<sub>n</sub> vs. IBI<sub>n+1</sub>).

### Funcionalidades actuales

| Componente | ¿Qué hace hoy? |
|---|---|
| **Reloj (Wear OS)** | Onboarding con selector de cama (1..5), registro atómico de paciente contra el backend, recolección de BPM / IBI / SpO2 / `hr_status` con Health Services y Samsung Health Sensor SDK, almacenamiento local en Room, subida por lotes con `delete-after-echo`, reintentos con backoff, sync en primer plano (`SyncForegroundService` + `OngoingActivity` para el watch-face) |
| **Backend (FastAPI)** | Registro de pacientes por cama (con `replace_active_session` para reasignación atómica), recepción de lotes con idempotencia (`local_id` UUID v4), paginación keyset por cursor, snapshot de ocupación (1..5), health/readiness checks, WebSocket por paciente en `/ws/patients/{patient_id}`, cifrado PII con `pgp_sym_encrypt`, separación de esquemas `pii` / `clinical` / `audit` |
| **Frontend (TanStack)** | Dashboard en tiempo real con `setQueryData` sobre la cache de TanStack Query (sin polling), vista de ocupación de camas, vista por paciente con tacograma + HRV (VLF/LF/HF) + Poincaré, design system "Iris Void" sobre Tailwind v4 (CSS-first), responsive |
| **Contratos** | OpenAPI 3.1 (fuente de verdad REST), AsyncAPI 3.0 (WebSocket), `data-models.md` con JSON Schema / Pydantic v2 / TypeScript / Kotlin, tipos TypeScript hand-written para WebSocket |

## Arquitectura

```
┌─────────────┐     HTTPS (batch)     ┌──────────────┐     WebSocket      ┌──────────────┐
│  Galaxy      │ ──────────────────→  │   FastAPI     │ ←───────────────→ │  TanStack     │
│  Watch 4     │                      │   backend     │                    │  frontend     │
│  (Wear OS 6) │ ←── delete-after-echo│   :8000       │                    │  :3000        │
└─────────────┘                      └──────┬───────┘                    └──────────────┘
                                            │
                                     ┌──────┴───────┐
                                     │  PostgreSQL   │
                                     │  :5432        │
                                     └──────────────┘
```

### Invariante crítico — `delete-after-echo`

El reloj **solo borra** una medición de Room cuando el backend responde con un
2xx que incluye el `local_id` en `accepted_ids`. Si el backend no está
disponible, las mediciones se acumulan y se reintentan con backoff. **Nunca** se
borra una fila de Room de forma optimista — una caída del backend no debe causar
pérdida de datos.

## Stack tecnológico

| Capa | Tecnología | Versión |
|---|---|---|
| Reloj | Kotlin, Jetpack Compose para Wear OS, Room (con KSP) | Wear OS 6 (API 36), AGP 9.2.1, Kotlin 2.3.0, JDK 21, Compose BOM 2024.09.00 |
| Backend | FastAPI + SQLAlchemy 2.0 (async) + Pydantic v2 + asyncpg + Alembic | Python ≥ 3.12, < 3.13 |
| Frontend | React 18 + TanStack Start + TanStack Router + TanStack Query + Recharts | TypeScript 5.5, Vite 7, Tailwind v4 (CSS-first, vía `@tailwindcss/vite`), Node 22+ (runtime de SSR) |
| Package manager (frontend) | bun (lockfile único: `bun.lock`) | bun 1.x |
| Base de datos | PostgreSQL | 18-alpine |
| Mensajería | WebSocket por paciente (`/ws/patients/{patient_id}`) | — |
| Túnel (dev) | ngrok v3 | — |

## Dependencias

### Requisitos del sistema

| Herramienta | Para qué |
|---|---|
| **Docker + Docker Compose v2** | Levantar PostgreSQL, backend y frontend |
| **JDK 21** | Compilar la app del reloj (Android) |
| **Android SDK** (API 36, build-tools 36.1.0) | Compilar e instalar APKs |
| **adb** | Conexión inalámbrica al reloj |
| **ngrok v3** (opcional) | Túnel HTTPS para pruebas con dispositivo real fuera de la red local |
| **uv** (Python) | Gestión de dependencias del backend |
| **bun** | Gestión de dependencias y dev server del frontend |

### Variables de entorno

Dos archivos de configuración controlan el flujo de desarrollo local:

- **`backend/.env.test`** — `NGROK_AUTHTOKEN`, `NGROK_PUBLIC_URL`, `APP_PII_ENCRYPTION_KEY`
- **`watch/.env.test`** — `watchIP`, `watchPort`

Ambos están gitignoreados. Copiá las plantillas para empezar:

```bash
cp backend/.env.test.example backend/.env.test
# Editar backend/.env.test con los valores reales
```

## Cómo montar cada servicio

> **Vía canónica para empezar de cero** (incluye wipe + migrate + smoke):
> [`scripts/start-test-stack.sh`](scripts/start-test-stack.sh) (solo backend) o
> [`scripts/start-test-e2e.sh`](scripts/start-test-e2e.sh) (también limpia
> el reloj y automatiza el onboarding). Ambos leen `backend/.env.test` y
> ejecutan las migraciones de Alembic.

### 1. Backend + PostgreSQL + Frontend

El stack de servicios se levanta con Docker Compose:

```bash
# Levantar todo (postgres + backend + frontend)
make up
```

Si tocaste código del backend o del frontend y la imagen cacheada quedó vieja:

```bash
make rebuild    # docker compose build backend frontend + up -d + alembic upgrade head
```

Esto deja los servicios expuestos en:
- **Backend**: http://localhost:8000 (API en `/api/v1`, health en `/api/v1/health`, readiness en `/api/v1/readyz`)
- **Frontend**: http://localhost:3000
- **PostgreSQL**: `localhost:5432` (usuario `postgres`, contraseña `postgres`, base `remote_monitor`)

Para detener:

```bash
make down
```

Si necesitás limpiar la base de datos y re-aplicar migraciones:

```bash
make backend-db-clean    # DROP SCHEMA pii/clinical/audit + alembic upgrade head
```

### 2. Túnel ngrok (para pruebas con dispositivo real)

Si el reloj no está en la misma red WiFi que tu máquina, necesitás exponer el
backend via ngrok:

```bash
# Requiere NGROK_AUTHTOKEN y NGROK_PUBLIC_URL en backend/.env.test
make ngrok-up      # inicia el túnel en segundo plano
make ngrok-down    # lo detiene
```

La URL pública de ngrok se inyecta en el APK en tiempo de compilación. Si no
usás ngrok, usá la IP local de tu red (`-PapiBaseUrl=http://192.168.1.X:8000/`).

### 3. Reloj (Wear OS)

La app del reloj se compila, instala y ejecuta con los targets del Makefile.

#### 3.1 Emparejar el reloj (solo la primera vez)

```bash
make pair
```

Seguí las instrucciones que imprime:
1. En el reloj: **Ajustes → Opciones de desarrollador → Depuración inalámbrica**
2. Tocá **Vincular dispositivo con código de vinculación**
3. Anotá la IP, el puerto y el código de vinculación
4. En la terminal del host: `adb pair <IP>:<puerto>` e ingresá el código
5. Editá `watch/.env.test` con `watchIP=<IP>` y `watchPort=<puerto>`
6. Ejecutá `make connect` para establecer la conexión adb

#### 3.2 Conectar al reloj (sesiones posteriores)

```bash
make connect    # conecta adb a la IP y puerto definidos en watch/.env.test
make status     # muestra estado general: docker + adb + ngrok + health del backend
make shell      # abre una shell adb en el reloj
```

#### 3.3 Compilar, instalar y ejecutar la app

```bash
make build              # compila el APK debug con la URL de ngrok inyectada
make install            # instala el APK en el reloj conectado
make run                # despierta la pantalla y lanza la app
make build-install-run  # build + install + run en secuencia
```

#### 3.4 Otorgar permisos de sensores

```bash
make watch-grant        # otorga READ_HEART_RATE, READ_OXYGEN_SATURATION, POST_NOTIFICATIONS
```

#### 3.5 Limpiar estado del reloj

```bash
make watch-clean        # pm clear (borra Room, DataStore, preferencias)
make watch-clean-all    # watch-clean + watch-grant
```

### 4. Frontend (desarrollo local sin Docker)

```bash
make frontend-install    # bun install (primera vez o tras cambios en package.json)
make frontend-dev        # bun run dev (servidor local en http://localhost:5173)
make frontend-build      # bun run build (verificación de build)
make frontend-typecheck  # bunx tsc --noEmit

# Si preferís correr el frontend dentro de Docker (separado del backend):
make frontend-up         # docker compose up -d --build frontend (contenedor en :3000)
make frontend-logs       # docker compose logs -f frontend
```

## Flujo completo de demo

Para probar todo de una vez:

```bash
make demo        # up + ngrok-up + build-install-run
```

Para un reset completo y demo limpia:

```bash
make demo-clean  # backend-db-clean + watch-clean-all + build-install-run
```

## Tests

```bash
make test             # backend (pytest) + watch (gradle unit tests)
make test-backend     # solo backend
make test-watch       # solo watch (./gradlew :app:testDebugUnitTest)

# Frontend (vitest, opcional)
cd frontend && bun run test
```

## Contratos

La fuente de verdad del contrato entre componentes está en `contracts/`:

| Archivo | Descripción |
|---|---|
| `openapi.yaml` | Especificación REST OpenAPI 3.1 — fuente de verdad para modelos y endpoints |
| `asyncapi.yaml` | Protocolo WebSocket entre backend y frontend |
| `data-models.md` | Modelos de datos en JSON Schema, Python, TypeScript y Kotlin |
| `websocket-types.ts` | Tipos TypeScript hand-written para el canal WebSocket |

## Postura de cumplimiento (PoC)

Este proyecto adopta una postura **HIPAA-like como declaración de prueba de
concepto**, no como control implementado. La intención es:

- Cifrado en reposo (PostgreSQL `pgcrypto`) y en tránsito (HTTPS / WSS)
- Bitácora de auditoría de eventos de acceso
- Ventana de retención definida
- Separación entre PII y datos clínicos (esquemas `pii`, `clinical`, `audit`)

El cifrado, los certificados, el cableado de auditoría y la aplicación de
retención se materializan en cambios posteriores. No trates este scaffold como si
tuviera implementado ningún control de cumplimiento.

## Autenticación (PoC)

No hay inicio de sesión en ninguna parte del sistema. El operador selecciona
una cama (1..5) desde el `BedGridView` del reloj; el backend deriva el
`patient_number` determinísticamente a partir del `bed_number` y lo cifra con
`pgp_sym_encrypt` antes de persistirlo en `pii.patients`. Para subir
mediciones, el reloj envía un header `X-Patient-Number` que el backend compara
contra el `patient_id` de la URL (403 si no coincide). El dashboard se abre
directamente a la vista en vivo. Esto es aceptable solo para una prueba de
concepto.

## Estructura del repositorio

```
remote-monitor/
├── backend/          # FastAPI — ingesta REST + broadcast WebSocket
│   ├── app/          # Código de la aplicación
│   ├── tests/        # Tests con pytest
│   ├── migrations/   # Migraciones Alembic
│   └── Dockerfile
├── frontend/         # Dashboard TanStack Start + React
│   ├── app/          # Rutas y páginas
│   ├── components/   # Componentes React
│   ├── hooks/        # Hooks personalizados
│   └── lib/          # Utilidades y cliente WebSocket
├── watch/            # App Wear OS (Kotlin + Jetpack Compose)
│   └── app/
│       ├── src/      # Código Kotlin
│       └── libs/     # Samsung Health Sensor SDK AAR
├── contracts/        # OpenAPI, AsyncAPI, modelos de datos
├── docs/             # Runbooks y guías
├── scripts/          # Scripts de inicio y smoke tests
├── docker-compose.yml
└── Makefile          # Fuente de verdad del flujo de desarrollo
```
