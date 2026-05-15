#!/bin/bash

# Startup script for non-Docker deployment
# The OpenMed Terminology Service (mediata-openmed/) is launched automatically
# by the Java application on startup (OpenMedLauncherConfig).

set -e

echo "=== Non-Docker Deployment Startup ==="
echo ""

# -----------------------------------------------------------------------
# OpenMed Terminology Service
# -----------------------------------------------------------------------
echo "OpenMed service (mediata-openmed/):"
echo "  → Auto-launched by the Java application on startup."
echo "  → Requires Python 3.11+ to be installed."
echo "  → On first run the application will:"
echo "      1. Create a virtual environment in mediata-openmed/.venv"
echo "      2. Install dependencies (fastapi, uvicorn, transformers, torch)"
echo "      3. Start uvicorn on port 8002"
echo "  → Model downloads (~5GB) happen automatically on first inference call."
echo ""

# Check Python availability so users get an early warning.
if ! command -v python3 &> /dev/null; then
    echo "WARNING: python3 not found on PATH."
    echo "         Install Python 3.11+ before starting the application."
    echo "         Without Python the OpenMed service cannot be launched and"
    echo "         terminology inference will use text-normalisation fallback."
    echo ""
else
    PYVER=$(python3 --version 2>&1)
    echo "✓ Python available: $PYVER"
    echo ""
fi

# -----------------------------------------------------------------------
# Snowstorm (optional)
# -----------------------------------------------------------------------
echo "Checking Snowstorm status (optional)..."
if curl -s http://localhost:9100/ > /dev/null 2>&1; then
    echo "✓ Snowstorm is accessible on http://localhost:9100"
else
    echo "⚠  Snowstorm is not accessible on http://localhost:9100"
    echo "   Terminology codes will use fallback values (CONCEPT_XXXXXXX)"
    echo "   Start Snowstorm if you want real SNOMED CT codes"
fi

echo ""
echo "=== All prerequisites checked ==="
echo ""
echo "Starting application..."
echo "Expected log: [OpenMed] Launched OpenMed service on port 8002"
echo ""

# Start the application
mvn spring-boot:run
