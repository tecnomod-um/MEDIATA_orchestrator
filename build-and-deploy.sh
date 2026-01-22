#!/bin/bash
# Build script for MEDIATA Orchestrator Docker deployment

set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
cd "$HERE"

echo "================================================"
echo "MEDIATA Orchestrator - Docker Build Script"
echo "================================================"
echo ""
echo "Working directory: ${HERE}"
echo ""

ensure_repo () {
    local dir="$1"
    local url="$2"

    if [ -d "$dir" ]; then
        echo "✓ $dir repository exists"
        return 0
    fi

    echo ""
    echo "WARNING (in orchestrator folder: ${HERE}): ${dir} directory not found."
    echo "Attempting to clone:"
    echo "  $url"
    echo ""

    if command -v git >/dev/null 2>&1; then
        git clone "$url" "$dir" || {
            echo ""
            echo "ERROR (in orchestrator folder: ${HERE}): failed to clone ${dir}"
            echo "Tried: git clone $url $dir"
            echo ""
            exit 1
        }
        echo "✓ Cloned $dir"
    else
        echo ""
        echo "ERROR (in orchestrator folder: ${HERE}): git is not installed or not in PATH."
        echo "Please install git and re-run, or clone manually:"
        echo "  git clone $url $dir"
        echo ""
        exit 1
    fi
}

echo "Checking Python service repositories..."

ensure_repo "mediata-rdf-builder" "https://github.com/tecnomod-um/mediata-rdf-builder.git"
ensure_repo "InteroperabilityFHIRAPI" "https://github.com/alvumu/InteroperabilityFHIRAPI.git"

echo ""
echo "✓ All required repositories are present"
echo ""

if [ -f "target/taniwha.war" ]; then
    echo "✓ Found existing taniwha.war"
    read -r -p "Do you want to rebuild it? (y/N): " rebuild
    if [[ "${rebuild:-}" =~ ^[Yy]$ ]]; then
        echo "Rebuilding application..."
        mvn clean package -DskipTests -B
    fi
else
    echo "Building application (this may take a few minutes)..."
    mvn clean package -DskipTests -B
fi

echo ""
echo "✓ Application built successfully"
echo ""

echo "Starting Docker Compose..."
docker compose up -d --build

echo ""
echo "================================================"
echo "Deployment complete!"
echo "================================================"
echo ""

get_port () {
  docker compose port "$1" "$2" 2>/dev/null | awk -F: '{print $2}'
}

ORCH_PORT=$(get_port orchestrator 8088)
MONGO_PORT=$(get_port mongodb 27017)
ES_PORT=$(get_port elasticsearch 9200)
SNOW_PORT=$(get_port snowstorm 8080)
RDF_PORT=$(get_port rdf-builder 8000)
FHIR_PORT=$(get_port fhir-api 8001)

echo "Services:"
[ -n "${ORCH_PORT:-}" ] && echo "  - Orchestrator: http://localhost:${ORCH_PORT}/taniwha"
[ -n "${MONGO_PORT:-}" ] && echo "  - MongoDB: mongodb://localhost:${MONGO_PORT}/mediata"
[ -n "${ES_PORT:-}" ] && echo "  - Elasticsearch: http://localhost:${ES_PORT}"
[ -n "${SNOW_PORT:-}" ] && echo "  - Snowstorm: http://localhost:${SNOW_PORT}"
[ -n "${RDF_PORT:-}" ] && echo "  - RDF Builder: http://localhost:${RDF_PORT}"
[ -n "${FHIR_PORT:-}" ] && echo "  - FHIR API: http://localhost:${FHIR_PORT}"

echo ""
echo "Check status: docker compose ps"
echo "View logs: docker compose logs -f orchestrator"
echo ""
