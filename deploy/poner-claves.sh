#!/usr/bin/env bash
# poner-claves.sh: lee líneas CLAVE=valor por la entrada estándar y las deja en el
# .env de la app (sustituye la clave si ya estaba, la añade si no). Los valores
# van entre comillas simples (Docker no interpreta $ ni # dentro) y no se imprimen
# nunca, solo el nombre. Si cambió alguna, recrea el contenedor para que la use.
# Lo llama el workflow de despliegue con los secrets del repositorio; también vale a mano:
#   printf 'GOOGLE_CLIENT_SECRET=...\n' | bash poner-claves.sh
set -euo pipefail
cd "$(dirname "$0")"
[ -f .env ] || { echo "no hay .env todavía (arrancar.sh lo crea)"; exit 1; }

cambios=0
while IFS= read -r linea; do
  [ -n "$linea" ] || continue
  clave=${linea%%=*}
  valor=${linea#*=}
  [[ "$clave" =~ ^[A-Z0-9_]+$ ]] || { echo "nombre de clave no válido: $clave"; exit 1; }
  case "$valor" in
    *$'\n'*|*"'"*) echo "::error::$clave no puede llevar comillas simples ni saltos de línea" >&2; exit 1;;
  esac
  nueva="$clave='$valor'"
  if grep -qxF "$nueva" .env; then
    echo "$clave: sin cambios"
    continue
  fi
  { grep -vE "^$clave=" .env || true; printf '%s\n' "$nueva"; } > .env.nuevo
  chmod 600 .env.nuevo
  mv .env.nuevo .env
  echo "$clave: puesta"
  cambios=$((cambios + 1))
done

if [ "$cambios" -gt 0 ]; then
  # Recrear el contenedor (un docker restart no vuelve a leer el .env), con la
  # misma imagen que tiene ahora.
  imagen=$(docker inspect -f '{{.Config.Image}}' subelo)
  export TAG=${imagen##*:}
  docker compose up -d --force-recreate --no-deps subelo >/dev/null 2>&1
  echo "subelo recreada con $cambios clave(s) nueva(s)"
else
  echo "ninguna clave nueva; la app sigue igual"
fi
