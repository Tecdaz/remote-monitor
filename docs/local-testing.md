# Pruebas locales en hardware real

Playbook punta a punta para correr la app Android de recolección contra el
backend local. La app corre en un reloj Wear OS, un teléfono o una tablet
Android; el dispositivo de medición es siempre la banda Polar H10.

> **Forma canónica de arrancar el entorno E2E:** usá
> [`scripts/start-test-e2e.sh`](../scripts/start-test-e2e.sh).
> Limpia la DB del backend, borra el estado de la app, otorga los permisos
> de BLE y notificaciones, y automatiza el onboarding.
>
> **Solo backend:** [`scripts/start-test-stack.sh`](../scripts/start-test-stack.sh)
> hace el wipe + migrate sin tocar el dispositivo.

## 0. Prerrequisitos

- Docker (Compose v2)
- JDK 21 (build Android)
- Android SDK con API 36 + build-tools 36.1.0 (`ANDROID_HOME` seteado)
- `adb` (USB o wireless debugging)
- `ngrok` v3 (opcional, para probar fuera de la LAN)
- `uv` (backend)
- Una banda Polar H10 cargada
- Un dispositivo Android API 33+ (reloj Wear OS, teléfono o tablet)

## 1. Backend: wipe + up + migrate

```bash
cd /home/santiago/repos/remote-monitor

# Una sola vez:
cp backend/.env.test.example backend/.env.test
ln -sf .env.test backend/.env
# Editá backend/.env.test si vas a usar ngrok.

./scripts/start-test-stack.sh      # interactivo
./scripts/start-test-stack.sh -y   # no interactivo
```

El script verifica la clave de desarrollo, hace `docker compose down -v`,
levanta postgres + backend + frontend, espera `readyz` y aplica
`alembic upgrade head`.

## 2. Túnel ngrok (opcional)

```bash
ngrok config add-authtoken "$(grep '^NGROK_AUTHTOKEN=' backend/.env.test | cut -d= -f2-)"
ngrok http 8000
# Copiá la URL https://<random>.ngrok-free.app/ a backend/.env.test:
# NGROK_PUBLIC_URL=https://<random>.ngrok-free.app/
```

Sin ngrok usá la IP LAN del host en el build:
`-PapiBaseUrl=http://192.168.1.X:8000/`.

## 3. APK con la URL y la banda correctas

`API_BASE_URL` y `POLAR_DEVICE_ID` se inyectan en build time. El filtro
por defecto es `"Polar H10"`, así que solo necesitás pasarlo si tu banda
tiene otro nombre o querés fijar el deviceId exacto.

```bash
cd /home/santiago/repos/remote-monitor
cd watch
./gradlew :app:assembleDebug \
  -PapiBaseUrl="$(grep '^NGROK_PUBLIC_URL=' ../backend/.env.test | cut -d= -f2-)" \
  -PpolarDeviceId="Polar H10"

# LAN directa:
# ./gradlew :app:assembleDebug -PapiBaseUrl="http://192.168.1.X:8000/"

# Verificar la URL embebida:
$ANDROID_HOME/cmdline-tools/latest/bin/apkanalyzer dex code \
  --class com.remotemonitor.watch.BuildConfig \
  app/build/outputs/apk/debug/app-debug.apk | grep API_BASE_URL
```

APK: `watch/app/build/outputs/apk/debug/app-debug.apk`.

## 4. Conectar el dispositivo por adb

### USB (teléfono/tablet)

1. Ajustes → Información del dispositivo → tocar **Número de compilación**
   7 veces.
2. Ajustes → Opciones de desarrollador → **Depuración USB**.
3. `adb devices -l` debe listar el dispositivo.

### Wireless (reloj o dispositivo sin USB)

1. Ajustes → Opciones de desarrollador → **Depuración inalámbrica**.
2. **Vincular dispositivo con código** y anotar IP:puerto + código.
3. En el host:
   ```bash
   adb pair <ip>:<puerto>
   adb connect <ip>:<puerto>
   adb devices
   ```
4. Guardá `watchIP` y `watchPort` en `watch/.env.test` y corré
   `make connect`.

## 5. Instalar, otorgar permisos y abrir

```bash
adb install -r watch/app/build/outputs/apk/debug/app-debug.apk
adb shell pm grant com.remotemonitor.watch android.permission.BLUETOOTH_SCAN
adb shell pm grant com.remotemonitor.watch android.permission.BLUETOOTH_CONNECT
adb shell pm grant com.remotemonitor.watch android.permission.POST_NOTIFICATIONS
adb shell am start -n com.remotemonitor.watch/.ui.MainActivity
```

Al abrir:
- **Onboarding**: grilla/carrusel de camas 1..5. Las ocupadas piden
  confirmación para reemplazar la sesión activa.
- **Home**: BPM grande, última lectura, estado de la banda y subidas
  pendientes.
- **Teléfono/tablet**: misma información con layout Material 3 adaptable.

## 6. Verificar que la banda llega al backend

```bash
docker compose logs -f backend
# Esperado: POST /api/v1/patients/.../measurements 200 a ~1 Hz
```

```bash
# Filas de banda en la DB
docker exec remote-monitor-postgres-1 psql -U postgres -d remote_monitor \
  -c "SELECT count(*), max(timestamp) FROM clinical.measurements;"

# Tiras ECG
docker exec remote-monitor-postgres-1 psql -U postgres -d remote_monitor \
  -c "SELECT count(*) FROM clinical.measurements WHERE ecg_uv IS NOT NULL;"
```

Logs del dispositivo:

```bash
adb logcat -d | grep -iE "PolarHrsProvider|PolarEcgProvider|okhttp|remotemonitor" | tail -40
```

## 7. Troubleshooting

| Síntoma | Causa probable | Fix |
|---|---|---|
| No aparece el diálogo de permisos BLE | APK instalado sin los permisos nuevos | `adb shell pm grant` (ver §5) o reinstalar el APK |
| `PolarHrsProvider` no encuentra la banda | Nombre/deviceId distinto o Location apagado en API ≤ 30 | Pasá `-PpolarDeviceId=...`; en API ≤ 30 activá Location |
| La app no sube nada | `POLAR_DEVICE_ID` vacío o banda sin batería | Rebuild con el nombre correcto; revisá la banda |
| Backend 422 | Cliente viejo enviando campos eliminados (`source`, `spo2_percent`, `ibis_status`, `hr_status`) | Reinstalar el APK actual; el backend usa `extra="forbid"` |
| Backend 500 "Wrong key or corrupt data" | `APP_PII_ENCRYPTION_KEY` no coincide con filas existentes | `docker compose down -v` y re-migrar |
| `adb install` falla con `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | Firma distinta | `adb uninstall com.remotemonitor.watch` y reinstalar |
| No hay ECG | SDK Polar sin `FEATURE_POLAR_ONLINE_STREAMING` o banda sin contacto | Revisar `logcat`; el BPM/RR por GATT puede seguir andando |
| El servicio se detiene en segundo plano | Falta el permiso de notificaciones | `adb shell pm grant ... POST_NOTIFICATIONS` |

## 8. Limpieza

```bash
pkill -f "ngrok http"
cd /home/santiago/repos/remote-monitor
docker compose down          # conserva el volumen
docker compose down -v       # borra la DB
adb uninstall com.remotemonitor.watch
```
