#!/bin/sh
# Write runtime environment variables into config.js so the browser
# can read them via window.__ENV. This runs at container startup,
# NOT at image build time, so you can deploy the same image to
# different environments by changing the env vars.
#
# VITE_API_BASE_URL — REST API base (e.g. https://abc.ngrok.io/api/v1)
# VITE_WS_BASE_URL  — WebSocket base (e.g. wss://abc.ngrok.io/ws)

cat > /app/dist/client/config.js << EOF
window.__ENV = {
  VITE_API_BASE_URL: "${VITE_API_BASE_URL}",
  VITE_WS_BASE_URL: "${VITE_WS_BASE_URL}",
};
EOF

exec node start-server.js
