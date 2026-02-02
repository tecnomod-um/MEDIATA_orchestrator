# Docker Deployment Guide

This guide explains how to deploy the MEDIATA Orchestrator.

## Prerequisites

- Docker Engine 20.10+ (for full Docker deployment)
- Maven 3.6+
- Java 17+
- At least 4GB of RAM available for Docker (if using Docker deployment)

## Deployment Options

### Option 1: Full Docker Stack

Deploy all services including MongoDB in Docker:

> **Network Access Required**: This deployment clones external repositories during the Docker build process (rdf-builder and FHIR API services). It requires network access and may not work in highly restricted environments.

```bash
# 1. Configure environment
cp .env.example .env
nano .env  # Set JWT_SECRET (32+ characters required)

# 2. Build and deploy
./build-and-deploy.sh
```

**What gets deployed:**
- MongoDB 7.0
- Elasticsearch 8.11.1
- Snowstorm (SNOMED CT server)
- RDF Builder (Python service)
- FHIR API (Python service)
- MEDIATA Orchestrator

**Service URLs:**
- Orchestrator API: http://localhost:8088/taniwha
- Orchestrator Health: http://localhost:8088/taniwha/actuator/health
- MongoDB: mongodb://localhost:27017/mediata
- Elasticsearch: http://localhost:9200
- Snowstorm: http://localhost:9100
- RDF Builder: http://localhost:8000/docs
- FHIR API: http://localhost:8001/docs

### Option 2: Local Orchestrator with External/Cloud MongoDB

Run the orchestrator locally (no Docker required for orchestrator). Works with cloud MongoDB (MongoDB Atlas) or local MongoDB:

```bash
# 1. Configure with your MongoDB URI
cp .env.example .env
nano .env
# Set:
#   MONGODB_URI=mongodb+srv://user:pass@cluster.mongodb.net/mediata
#   # Or for local: mongodb://localhost:27017/mediata
#   JWT_SECRET=your-32-character-secret-here

# 2. Clone required service repositories (one-time setup)
git clone https://github.com/tecnomod-um/mediata-rdf-builder.git
git clone https://github.com/tecnomod-um/InteroperabilityFHIRAPI.git

# 3. Run the orchestrator
mvn spring-boot:run
```

**What gets launched automatically:**
- Elasticsearch (in Docker)
- Snowstorm (in Docker)
- RDF Builder (Python service from cloned repo)
- FHIR API (Python service from cloned repo)
- MEDIATA Orchestrator (local Java process)

**Service URLs:**
- Orchestrator API: http://localhost:8088/taniwha
- Orchestrator Health: http://localhost:8088/taniwha/actuator/health
- MongoDB: Your configured URI
- Elasticsearch: http://localhost:9200
- Snowstorm: http://localhost:9100
- RDF Builder: http://localhost:8000/docs
- FHIR API: http://localhost:8001/docs

## Port Mapping

| Service       | Port  | Description             |
|---------------|-------|-------------------------|
| orchestrator  | 8088  | Main application API    |
| orchestrator  | 8089  | Kerberos KDC            |
| mongodb       | 27017 | MongoDB database        |
| elasticsearch | 9200  | Elasticsearch           |
| snowstorm     | 9100  | Snowstorm API           |
| rdf-builder   | 8000  | RDF Builder API         |
| fhir-api      | 8001  | FHIR Clustering API     |

## Useful Commands

### Docker Deployment (Option 1)

```bash
# Start services
docker compose up -d

# Stop services
docker compose down

# View status
docker compose ps

# View logs
docker compose logs -f
docker compose logs -f orchestrator

# Restart a service
docker compose restart orchestrator

# Rebuild after code changes
mvn clean package -DskipTests
docker compose up -d --build orchestrator

# Clean up everything (including data)
docker compose down -v
```

### Local Deployment (Option 2)

```bash
# Start orchestrator
mvn spring-boot:run

# View logs
tail -f logs/app.log

# Stop services (Ctrl+C to stop orchestrator, then manually stop Docker services)
docker ps
docker stop mediata-snowstorm mediata-elasticsearch
```

## Troubleshooting

### Docker Build Fails (Network/SSL Issues)

If the Docker build fails due to network access or SSL certificate issues when cloning repositories:

**Solution**: Use Option 2 (Local Orchestrator) instead, which clones repositories to your local machine first.

### Services not starting

Check service logs:
```bash
# Docker
docker compose logs <service-name>

# Local
tail -f logs/app.log
```

### Port conflicts

If ports are already in use:
1. Edit `.env` to change port mappings
2. Or stop the conflicting service

### Memory issues

For Docker deployment, increase Docker's memory limit in Docker Desktop settings.

For Elasticsearch specifically, edit `docker-compose.yml`:
```yaml
environment:
  - ES_JAVA_OPTS=-Xms512m -Xmx512m
```

### Snowstorm takes time to start

Snowstorm may take 1-2 minutes to fully start. Check its logs:
```bash
# Docker
docker compose logs -f snowstorm

# Local
docker logs mediata-snowstorm
```

### MongoDB connection issues

Test your MongoDB connection:
```bash
# For local Docker MongoDB
mongosh mongodb://localhost:27017/mediata

# For MongoDB Atlas
mongosh "mongodb+srv://username:password@cluster.mongodb.net/mediata"
```

### Python services fail to launch (Local mode)

Ensure repositories are cloned in the project root:
```bash
ls -la mediata-rdf-builder/
ls -la InteroperabilityFHIRAPI/
```

Check Python version (3.11+ required):
```bash
python3 --version
```

## Data Persistence

### Docker Deployment

Data is stored in Docker volumes:
- `mongodb-data` - MongoDB database files
- `elasticsearch-data` - Elasticsearch indices
- `kerby-data` - Kerberos configuration and data

These volumes persist across container restarts. To remove them:
```bash
docker compose down -v
```

### Local Deployment

- MongoDB: Data stored according to your MongoDB configuration
- Elasticsearch: Docker volume `elasticsearch-data`
- Orchestrator: Logs in `logs/` directory

## Network

For Docker deployment, all services communicate through the `mediata-network` bridge network. Services reference each other by their service names (e.g., `mongodb`, `elasticsearch`, `rdf-builder`).
