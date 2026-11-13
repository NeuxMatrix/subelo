#!/usr/bin/env bash
# comprobar.sh <contenedor> <url dentro del contenedor>
# Pregunta a la app por su salud desde dentro del VPS, sin pasar por el DNS ni
# por Caddy: así el despliegue se valida aunque el dominio todavía apunte a
# otro sitio. Lo lanza el workflow después de arrancar.sh; también vale a mano.
set -u
CONTENEDOR="$1"; URL="$2"
for intento in $(seq 1 12); do
  if docker exec "$CONTENEDOR" sh -c 'command -v node >/dev/null'; then
    docker exec "$CONTENEDOR" node -e '
      fetch(process.argv[1])
        .then(r => r.ok ? r.text() : Promise.reject(new Error("HTTP " + r.status)))
        .then(t => console.log(t.slice(0, 200)))
        .catch(e => { console.error(e.message); process.exit(1); });
    ' "$URL" && exit 0
  else
    docker exec "$CONTENEDOR" wget -qO- "$URL" >/dev/null && { echo "responde"; exit 0; }
  fi
  sleep 5
done
echo "$CONTENEDOR no responde en $URL; últimas líneas de su registro:"
docker logs "$CONTENEDOR" --tail 50
exit 1
