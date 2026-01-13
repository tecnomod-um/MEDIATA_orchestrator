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

## Quick Start

### Single-Command Deployment (Recommended)

Deploy the entire MEDIATA stack with one script:

```bash
# Copy environment file and set your JWT secret (32+ characters)
cp .env.example .env
nano .env  # Edit JWT_SECRET

# Build and deploy everything
./build-and-deploy.sh
```

This script:
1. Builds the application JAR locally
2. Creates Docker images for all services
3. Starts the complete stack (MongoDB, Elasticsearch, Snowstorm, RDF Builder, FHIR API, Orchestrator)

Services will be available at:
- **Orchestrator**: http://localhost:8088/taniwha
- **MongoDB**: mongodb://localhost:27017/mediata
- **Snowstorm**: http://localhost:9100

### Alternative: Manual Docker Compose

```bash
# Copy environment file
cp .env.example .env
nano .env  # Edit JWT_SECRET (must be 32+ characters)

# Build JAR locally
mvn clean package -DskipTests

# Start all services
docker-compose up -d

# Check status
docker-compose ps
```

See [DOCKER.md](DOCKER.md) for detailed setup, troubleshooting, and alternative deployment options.

### Local Development

For local development without Docker, see [LOCAL_DEVELOPMENT.md](LOCAL_DEVELOPMENT.md) for 5 different deployment configurations including cloud MongoDB options.

## License

This project is developed under the [MIT License](LICENSE.md).
