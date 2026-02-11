#!/usr/bin/env bash
set -euo pipefail

echo "================================================"
echo "MEDIATA Orchestrator - Docker Build Script"
echo "================================================"
echo

fix_crlf() {
  local f="$1"
  [[ -f "$f" ]] || return 0
  sed -i 's/\r$//' "$f" 2>/dev/null || true
}

normalize_env_file() {
  local f="$1"
  [[ -f "$f" ]] || return 0
  fix_crlf "$f"
  sed -i 's/^[[:space:]]\+//' "$f" 2>/dev/null || true
  sed -i -E 's/^([A-Za-z_][A-Za-z0-9_]*)[[:space:]]*=[[:space:]]*/\1=/' "$f" 2>/dev/null || true
}

dc() {
  if docker compose version >/dev/null 2>&1; then
    docker compose "$@"
  elif command -v docker-compose >/dev/null 2>&1; then
    docker-compose "$@"
  else
    echo "ERROR: Neither 'docker compose' nor 'docker-compose' found."
    exit 1
  fi
}

mkdir_safe() {
  mkdir -p "$@" 2>/dev/null || true
}

fix_crlf "$0"

if [[ ! -f ".env" ]]; then
  if [[ -f ".env.example" ]]; then
    echo "No .env found. Creating one from .env.example..."
    normalize_env_file ".env.example"
    cp ".env.example" ".env"
    normalize_env_file ".env"
    echo "✓ Created .env from .env.example"
    echo "IMPORTANT: Review .env and set secure values before production use."
    echo
  else
    echo "ERROR: No .env and no .env.example found."
    exit 1
  fi
else
  normalize_env_file ".env"
fi

set -a
source ".env"
set +a

ENABLE_RDF_BUILDER="${ENABLE_RDF_BUILDER:-false}"
ENABLE_FHIR_API="${ENABLE_FHIR_API:-false}"

profiles=()
if [[ "$ENABLE_RDF_BUILDER" == "true" ]]; then
  profiles+=(--profile rdf)
fi
if [[ "$ENABLE_FHIR_API" == "true" ]]; then
  profiles+=(--profile fhir)
fi

echo "Selected mode:"
echo "  ENABLE_RDF_BUILDER=${ENABLE_RDF_BUILDER}"
echo "  ENABLE_FHIR_API=${ENABLE_FHIR_API}"
echo "Compose profiles: ${profiles[*]:-(none)}"
echo

# ---------- pre-create commonly mounted host dirs (idempotent) ----------
mkdir_safe "node-data" "mongo-data" "es-data" "snowstorm-data" 2>/dev/null || true

if [[ "$ENABLE_RDF_BUILDER" == "true" ]]; then
  if [[ ! -d "mediata-rdf-builder" ]]; then
    echo "ERROR: mediata-rdf-builder directory not found!"
    echo "Clone it first:"
    echo "  git clone https://github.com/tecnomod-um/mediata-rdf-builder.git"
    exit 1
  fi
fi

if [[ "$ENABLE_FHIR_API" == "true" ]]; then
  if [[ ! -d "InteroperabilityFHIRAPI" ]]; then
    echo "ERROR: InteroperabilityFHIRAPI directory not found!"
    echo "Clone it first:"
    echo "  git clone https://github.com/alvumu/InteroperabilityFHIRAPI.git"
    exit 1
  fi
fi

echo "Starting Docker Compose..."
dc "${profiles[@]}" up -d --build

echo
echo "================================================"
echo "Deployment complete!"
echo "================================================"
echo

get_port () {
  dc port "$1" "$2" 2>/dev/null | awk -F: '{print $2}'
}

ORCH_PORT="$(get_port orchestrator 8088 || true)"
MONGO_PORT="$(get_port mongodb 27017 || true)"
ES_PORT="$(get_port elasticsearch 9200 || true)"
SNOW_PORT="$(get_port snowstorm 8080 || true)"
RDF_PORT="$(get_port rdf-builder 8000 || true)"
FHIR_PORT="$(get_port fhir-api 8001 || true)"

echo "Services:"
[[ -n "${ORCH_PORT:-}" ]] && echo "  - Orchestrator: http://localhost:${ORCH_PORT}/taniwha"
[[ -n "${MONGO_PORT:-}" ]] && echo "  - MongoDB: mongodb://localhost:${MONGO_PORT}/mediata"
[[ -n "${ES_PORT:-}" ]] && echo "  - Elasticsearch: http://localhost:${ES_PORT}"
[[ -n "${SNOW_PORT:-}" ]] && echo "  - Snowstorm: http://localhost:${SNOW_PORT}"
[[ -n "${RDF_PORT:-}" ]] && echo "  - RDF Builder: http://localhost:${RDF_PORT}"
[[ -n "${FHIR_PORT:-}" ]] && echo "  - FHIR API: http://localhost:${FHIR_PORT}"

echo
echo "Check status: $(command -v docker >/dev/null 2>&1 && echo "docker compose" || echo "docker-compose") ps"
echo "View logs:   $(command -v docker >/dev/null 2>&1 && echo "docker compose" || echo "docker-compose") logs -f orchestrator"
echo
