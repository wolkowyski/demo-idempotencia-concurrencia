#!/usr/bin/env bash
# =============================================================================
# Demo TP de Investigación - Grupo 2 - Temas 9 y 10
# Corre los 5 casos contra la API y verifica el resultado en PostgreSQL.
#
# Uso:   ./demo.sh
# Requiere: bash, curl y la demo levantada con `docker compose up --build -d`.
#
# Variables opcionales:
#   API   URL de la API              (por defecto http://localhost:8080)
#   PSQL  comando psql para verificar (por defecto, el psql del contenedor db)
# =============================================================================
set -uo pipefail
cd "$(dirname "$0")"

API="${API:-http://localhost:8080}"
PSQL="${PSQL:-docker compose exec -T db psql -U demo -d demo -tA -q}"
FECHA="2026-11-12T18:00:00"
RUN="$(date +%s)"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

if [ -t 1 ]; then
  B=$'\e[1m'; VERDE=$'\e[32m'; ROJO=$'\e[31m'; AMARILLO=$'\e[33m'; CIAN=$'\e[36m'; N=$'\e[0m'
else
  B=""; VERDE=""; ROJO=""; AMARILLO=""; CIAN=""; N=""
fi

OK=0
FALLAS=0

# ---- utilidades --------------------------------------------------------------

sql() {
  $PSQL -c "$1" | tr -d '[:space:]'
}

titulo() {
  printf '\n%s━━ %s ━━%s\n' "$B$CIAN" "$1" "$N"
}

nota() {
  printf '  %s%s%s\n' "$AMARILLO" "$1" "$N"
}

# post RUTA CLAVE BODY [ARCHIVO_RESPUESTA]
# Imprime el status HTTP y "(replay)" si la API devolvió Idempotent-Replayed: true.
post() {
  local ruta=$1 clave=$2 body=$3 salida=${4:-/dev/null}
  local args=(-s -o "$salida" -D - -X POST "$API$ruta" -H 'Content-Type: application/json' -d "$body")
  if [ -n "$clave" ]; then
    args+=(-H "Idempotency-Key: \"$clave\"")
  fi
  local cabeceras codigo replay
  cabeceras=$(curl "${args[@]}" | tr -d '\r')
  codigo=$(printf '%s\n' "$cabeceras" | awk 'NR==1 {print $2}')
  replay=$(printf '%s\n' "$cabeceras" | awk -F': ' 'tolower($1)=="idempotent-replayed" {print $2}')
  if [ "$replay" = "true" ]; then
    echo "$codigo (replay)"
  else
    echo "${codigo:-sin respuesta}"
  fi
}

# titulo del problem+json guardado en un archivo
titulo_problema() {
  sed -n 's/.*"title":"\([^"]*\)".*/\1/p' "$1"
}

verificar() {
  local descripcion=$1 esperado=$2 obtenido=$3
  if [ "$esperado" = "$obtenido" ]; then
    printf '  %s✔ %s: %s%s\n' "$VERDE" "$descripcion" "$obtenido" "$N"
    OK=$((OK + 1))
  else
    printf '  %s✘ %s: esperado %s, obtenido %s%s\n' "$ROJO" "$descripcion" "$esperado" "$obtenido" "$N"
    FALLAS=$((FALLAS + 1))
  fi
}

# ---- preparación -------------------------------------------------------------

printf '%sDemo · Idempotencia y control de concurrencia · Grupo 2%s\n' "$B" "$N"
printf 'API: %s\n' "$API"

if ! command -v curl >/dev/null 2>&1; then
  echo "Falta curl. Instalalo o corré el script desde Git Bash / WSL." >&2
  exit 1
fi

printf 'Esperando la API '
listo=""
for _ in $(seq 1 60); do
  codigo=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$API/reservas" -H 'Content-Type: application/json' -d '{}')
  if [ "$codigo" != "000" ]; then listo="si"; break; fi
  printf '.'
  sleep 2
done
echo
if [ -z "$listo" ]; then
  echo "La API no responde en $API. ¿Corriste 'docker compose up --build -d'?" >&2
  exit 1
fi

if ! $PSQL -c 'SELECT 1' >/dev/null 2>&1; then
  echo "No puedo consultar la base con: $PSQL" >&2
  exit 1
fi

sql "TRUNCATE reservas, reservas_ingenuas, idempotency_records RESTART IDENTITY" >/dev/null
echo "Base limpia: reservas, reservas_ingenuas e idempotency_records vacías."

# ---- Caso 1 ------------------------------------------------------------------

titulo "Caso 1 · Reintento secuencial: 10 peticiones idénticas, misma clave"
K1="caso1-$RUN"
BODY1="{\"clienteId\":42,\"conductorId\":1,\"fechaHora\":\"$FECHA\"}"
printf '  POST /reservas  Idempotency-Key: "%s"\n' "$K1"
for i in $(seq 1 10); do
  printf '  #%-2s → %s\n' "$i" "$(post /reservas "$K1" "$BODY1" "$TMP/c1.$i")"
done
verificar "reservas creadas para el conductor 1" 1 "$(sql "SELECT count(*) FROM reservas WHERE conductor_id = 1")"
if cmp -s "$TMP/c1.1" "$TMP/c1.10"; then iguales="idéntico"; else iguales="distinto"; fi
verificar "body del reintento #10 frente al #1" "idéntico" "$iguales"

# ---- Caso 2 ------------------------------------------------------------------

titulo "Caso 2 · Reintento paralelo: 10 peticiones idénticas al mismo tiempo"
K2="caso2-$RUN"
BODY2="{\"clienteId\":42,\"conductorId\":2,\"fechaHora\":\"$FECHA\"}"
printf '  POST /reservas  Idempotency-Key: "%s"  ×10 en paralelo\n' "$K2"
for i in $(seq 1 10); do
  ( post /reservas "$K2" "$BODY2" > "$TMP/c2.$i" ) &
done
wait
for i in $(seq 1 10); do
  printf '  #%-2s → %s\n' "$i" "$(cat "$TMP/c2.$i")"
done
nuevas=$(cat "$TMP"/c2.* | grep -cx '201')
verificar "respuestas 201 que crearon algo" 1 "$nuevas"
verificar "reservas creadas para el conductor 2" 1 "$(sql "SELECT count(*) FROM reservas WHERE conductor_id = 2")"
nota "Las otras 9 esperaron el COMMIT de la primera en el UNIQUE de idempotency_records."

# ---- Caso 3 ------------------------------------------------------------------

titulo "Caso 3 · Reuso de la clave con un body distinto"
K3="caso3-$RUN"
BODY3="{\"clienteId\":42,\"conductorId\":3,\"fechaHora\":\"$FECHA\"}"
BODY3B="{\"clienteId\":42,\"conductorId\":6,\"fechaHora\":\"$FECHA\"}"
printf '  Original  conductorId=3 → %s\n' "$(post /reservas "$K3" "$BODY3")"
r3=$(post /reservas "$K3" "$BODY3B" "$TMP/c3")
printf '  Reuso     conductorId=6 → %s  "%s"\n' "$r3" "$(titulo_problema "$TMP/c3")"
verificar "status del reuso" 422 "$r3"
verificar "reservas creadas para el conductor 6" 0 "$(sql "SELECT count(*) FROM reservas WHERE conductor_id = 6")"

# ---- Caso 4 ------------------------------------------------------------------

titulo "Caso 4 · Versión ingenua: 2 clientes reservan el mismo conductor a la vez"
for cliente in 101 102; do
  ( post /ingenuo/reservas "" "{\"clienteId\":$cliente,\"conductorId\":4,\"fechaHora\":\"$FECHA\"}" > "$TMP/c4.$cliente" ) &
done
wait
printf '  Cliente 101 → %s\n' "$(cat "$TMP/c4.101")"
printf '  Cliente 102 → %s\n' "$(cat "$TMP/c4.102")"
verificar "reservas del conductor 4 (bug de doble asignación reproducido)" 2 \
  "$(sql "SELECT count(*) FROM reservas_ingenuas WHERE conductor_id = 4")"
nota "Los dos leyeron \"libre\" antes de que cualquiera insertara (check-then-act)."

# ---- Caso 5 ------------------------------------------------------------------

titulo "Caso 5 · Versión corregida: la misma carrera con FOR UPDATE + UNIQUE"
for cliente in 101 102; do
  ( post /reservas "caso5-$cliente-$RUN" "{\"clienteId\":$cliente,\"conductorId\":5,\"fechaHora\":\"$FECHA\"}" \
      "$TMP/c5.body.$cliente" > "$TMP/c5.$cliente" ) &
done
wait
for cliente in 101 102; do
  r=$(cat "$TMP/c5.$cliente")
  if [ "$r" = "409" ]; then
    printf '  Cliente %s → %s  "%s"\n' "$cliente" "$r" "$(titulo_problema "$TMP/c5.body.$cliente")"
  else
    printf '  Cliente %s → %s\n' "$cliente" "$r"
  fi
done
codigos=$(cat "$TMP/c5.101" "$TMP/c5.102" | sort | tr '\n' ' ' | sed 's/ $//')
verificar "respuestas" "201 409" "$codigos"
verificar "reservas creadas para el conductor 5" 1 "$(sql "SELECT count(*) FROM reservas WHERE conductor_id = 5")"

# ---- Resumen -----------------------------------------------------------------

titulo "Resumen"
if [ "$FALLAS" -eq 0 ]; then
  printf '  %s%d verificaciones OK, 0 fallas%s\n\n' "$VERDE$B" "$OK" "$N"
  exit 0
else
  printf '  %s%d OK, %d fallas%s\n\n' "$ROJO$B" "$OK" "$FALLAS" "$N"
  exit 1
fi
