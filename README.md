# MEDIATA Central Orchestrator

[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.x-6DB33F?style=flat&logo=spring-boot&logoColor=white)](#)
[![CI](https://github.com/tecnomod-um/MEDIATA_orchestrator/actions/workflows/ci_testing.yml/badge.svg?branch=main)](https://github.com/tecnomod-um/MEDIATA_orchestrator/actions/workflows/ci_testing.yml)
[![Coverage](./badges/coverage.svg)](#)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE.md)

This repository contains the **central backend orchestrator** of the MEDIATA platform.  
For the complete platform (front end, back end, orchestration, and docs), see **[tecnomod-um/MEDIATA_project](https://github.com/tecnomod-um/MEDIATA_project)**.

## Overview

This is the central backend service of the MEDIATA platform. It handles user authentication via Kerberos, manages node registration, and orchestrates harmonization tasks.

## Features

- Kerberos-based user authentication (KDC-hosted)
- Node registration and heartbeat monitoring
- Centralized orchestration of semantic alignment and mapping pipelines
- API endpoints for:
    - Mapping rule generation (YARRRML, RDF)
    - HL7 FHIR clustering and profile generation
    - Node and user management
    - Log collection and monitoring

## Deployment Options

> **Note**: Due to the automated build process for RDF Builder and FHIR API services (which clone external repositories), the full Docker deployment requires network access during build. In restricted environments, you may need to run the orchestrator in standalone mode or manually clone the repositories first.

### Option 1: Full Docker Stack (Recommended)

Deploy everything including MongoDB with one script:

```bash
# 1. Configure environment
cp .env.example .env
nano .env  # Set JWT_SECRET (must be 32+ characters)

# 2. Build and deploy
./build-and-deploy.sh
```

Services will be available at:
- **Orchestrator**: http://localhost:8088/taniwha
- **MongoDB**: mongodb://localhost:27017/mediata
- **Snowstorm**: http://localhost:9100
- **RDF Builder**: http://localhost:8000
- **FHIR API**: http://localhost:8001

### Option 2: Orchestrator with Cloud/Local MongoDB (No Docker)

If you have your own MongoDB instance and want to run the orchestrator locally:

```bash
# 1. Configure environment with your MongoDB URI
cp .env.example .env
nano .env
# Set:
#   MONGODB_URI=mongodb+srv://user:pass@cluster.mongodb.net/mediata
#   # Or local: mongodb://localhost:27017/mediata
#   JWT_SECRET=your-32-character-secret-key

# 2. Clone required service repositories (if not already cloned)
git clone https://github.com/tecnomod-um/mediata-rdf-builder.git
git clone https://github.com/tecnomod-um/InteroperabilityFHIRAPI.git

# 3. Run the orchestrator (auto-launches services)
mvn spring-boot:run
```

The orchestrator will automatically:
- ✅ Launch Elasticsearch and Snowstorm in Docker containers
- ✅ Launch Python RDF Builder service (requires local repo clone)
- ✅ Launch Python FHIR API service (requires local repo clone)
- ✅ Connect to your specified MongoDB instance

Services will be available at:
- **Orchestrator**: http://localhost:8088/taniwha
- **MongoDB**: Your configured URI
- **Snowstorm**: http://localhost:9100
- **RDF Builder**: http://localhost:8000
- **FHIR API**: http://localhost:8001

### Check Status

```bash
# For Docker deployment
docker compose ps
docker compose logs -f orchestrator

# For standalone
# Check logs in logs/app.log
```

See [DOCKER.md](DOCKER.md) for detailed setup and troubleshooting.

## License

This project is developed under the [MIT License](LICENSE.md).
