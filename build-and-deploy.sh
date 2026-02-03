#!/bin/bash
# Build script for MEDIATA Orchestrator Docker deployment

set -e

echo "================================================"
echo "MEDIATA Orchestrator - Docker Build Script"
echo "================================================"
echo ""

echo "Checking Python service repositories..."
if [ ! -d "mediata-rdf-builder" ]; then
    echo ""
    echo "ERROR: mediata-rdf-builder directory not found!"
    echo "Please clone it first:"
    echo "  git clone https://github.com/tecnomod-um/mediata-rdf-builder.git"
    echo ""
    exit 1
fi

if [ ! -d "InteroperabilityFHIRAPI" ]; then
    echo ""
    echo "ERROR: InteroperabilityFHIRAPI directory not found!"
    echo "Please clone it first:"
    echo "  git clone https://github.com/alvumu/InteroperabilityFHIRAPI.git"
    echo ""
    exit 1
fi

echo "✓ RDF Builder repository exists"
echo "✓ FHIR API repository exists"
echo ""

if [ ! -f ".env" ]; then
    if [ -f ".env.example" ]; then
        echo "No .env found. Creating one from .env.example..."
        cp ".env.example" ".env"
        echo "✓ Created .env from .env.example"
        echo "IMPORTANT: Review .env and set secure values (e.g., JWT_SECRET) before using in production."
        echo ""
    else
        echo "WARNING: No .env and no .env.example found. Docker Compose may fail if variables are required."
        echo ""
    fi
fi

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
[ -n "$ORCH_PORT" ] && echo "  - Orchestrator: http://localhost:${ORCH_PORT}/taniwha"
[ -n "$MONGO_PORT" ] && echo "  - MongoDB: mongodb://localhost:${MONGO_PORT}/mediata"
[ -n "$ES_PORT" ] && echo "  - Elasticsearch: http://localhost:${ES_PORT}"
[ -n "$SNOW_PORT" ] && echo "  - Snowstorm: http://localhost:${SNOW_PORT}"
[ -n "$RDF_PORT" ] && echo "  - RDF Builder: http://localhost:${RDF_PORT}"
[ -n "$FHIR_PORT" ] && echo "  - FHIR API: http://localhost:${FHIR_PORT}"

echo ""
echo "Check status: docker compose ps"
echo "View logs: docker compose logs -f orchestrator"
echo ""
