#!/bin/bash
# Build script for MEDIATA Orchestrator Docker deployment
# This script ensures the JAR is built before running docker-compose

set -e

echo "================================================"
echo "MEDIATA Orchestrator - Docker Build Script"
echo "================================================"
echo ""

# Check if target/taniwha.war exists
if [ -f "target/taniwha.war" ]; then
    echo "✓ Found existing taniwha.war"
    read -p "Do you want to rebuild it? (y/N): " rebuild
    if [[ $rebuild =~ ^[Yy]$ ]]; then
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
echo "Services:"
echo "  - Orchestrator: http://localhost:8088/taniwha"
echo "  - MongoDB: mongodb://localhost:27017/mediata"
echo "  - Elasticsearch: http://localhost:9200"
echo "  - Snowstorm: http://localhost:9100"
echo "  - RDF Builder: http://localhost:8000"
echo "  - FHIR API: http://localhost:8001"
echo ""
echo "Check status: docker compose ps"
echo "View logs: docker compose logs -f orchestrator"
echo ""
