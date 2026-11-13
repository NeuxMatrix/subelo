#!/usr/bin/env bash
# Lo ejecuta el workflow por SSH como usuario deploy: arrancar.sh <app> <tag> <usuario-ghcr>
# El token de ghcr.io llega por la entrada estándar.
set -euo pipefail
APP="$1"; TAG="$2"; USUARIO="$3"; DIR="/opt/neux/apps/$APP"

TOKEN_GHCR=""
if [ ! -t 0 ]; then TOKEN_GHCR=$(cat || true); fi

# Un solo despliegue a la vez en el VPS: dos pulls simultáneos hacen fallar a Docker.
exec 9>/opt/neux/despliegue.lock
flock -w 600 9

cd "$DIR"

# La primera vez: .env con el secreto generado aquí mismo.
if [ -f env.plantilla ] && [ ! -f .env ]; then
  echo "==> Primera vez: creando el fichero .env"
  SECRETO=$(openssl rand -hex 32)
  sed -e "s|{{SECRETO}}|$SECRETO|g" env.plantilla > .env
  chmod 600 .env
fi

# Lo que falte en un .env existente se añade con el valor de la plantilla; lo que
# ya está no se toca. BASE_URL la manda siempre la plantilla (cambiar de dominio es una PR).
falta() { ! grep -qE "^$1=" .env; }
grep -E '^[A-Z0-9_]+=' env.plantilla | grep -v '{{' | while IFS= read -r linea; do
  clave=${linea%%=*}
  if falta "$clave"; then printf '%s\n' "$linea" >> .env; echo "    $clave: añadida"; fi
done
if falta SESSION_SECRET; then printf 'SESSION_SECRET=%s\n' "$(openssl rand -hex 32)" >> .env; fi
valor=$(grep -E '^BASE_URL=' env.plantilla | head -n1 | cut -d= -f2-)
sed -i "s|^BASE_URL=.*|BASE_URL=$valor|" .env
chmod 600 .env

echo "==> Descargando la imagen $TAG"
if [ -n "$TOKEN_GHCR" ]; then
  printf '%s' "$TOKEN_GHCR" | docker login ghcr.io -u "$USUARIO" --password-stdin
else
  echo "::warning::No llegó el token de ghcr.io por la entrada estándar; se intenta descargar sin iniciar sesión"
fi
export TAG
for intento in 1 2 3; do
  docker compose pull -q && break
  if [ "$intento" -eq 3 ]; then
    echo "No se pudo descargar la imagen tras 3 intentos" >&2
    docker logout ghcr.io >/dev/null
    exit 1
  fi
  echo "    fallo al descargar, reintento $intento en 15 s"
  sleep 15
done
docker logout ghcr.io >/dev/null

echo "==> Arrancando"
docker compose up -d --remove-orphans

echo "==> Actualizando el dominio en Caddy"
cp "$APP.caddy" /opt/neux/caddy/sites/
docker exec caddy caddy reload --config /etc/caddy/Caddyfile

docker image prune -f >/dev/null
docker compose ps
