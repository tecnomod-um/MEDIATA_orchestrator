# Running the Orchestrator Locally

This guide explains how to run the MEDIATA Orchestrator locally with different configuration options.

## Overview

The orchestrator can be run in two modes:
1. **Standalone Mode** (default) - Launches all dependent services programmatically
2. **Docker Compose Mode** - Uses containerized services

## Prerequisites

- Java 17 or higher
- Maven 3.6+
- Docker (for Snowstorm/Elasticsearch or full stack)

## Option 1: Standalone Mode with Local MongoDB

This mode runs the orchestrator locally and uses your own MongoDB instance (cloud or local).

### Setup

1. **Clone external repositories** (required for RDF and FHIR services):
   ```bash
   git clone https://github.com/tecnomod-um/mediata-rdf-builder.git
   git clone https://github.com/tecnomod-um/InteroperabilityFHIRAPI.git
   ```

2. **Create a `.env` file** in the project root:
   ```bash
   # MongoDB Configuration (use your MongoDB Atlas URI or local instance)
   MONGODB_URI=mongodb+srv://username:password@cluster.mongodb.net/mediata
   # Or for local MongoDB:
   # MONGODB_URI=mongodb://localhost:27017/mediata
   
   # JWT Secret
   JWT_SECRET=your-secret-key-here
   
   # Optional: Customize ports if needed
   PORT=8088
   KERBEROS_PORT=8089
   ```

3. **Build the project**:
   ```bash
   mvn clean package -DskipTests
   ```

4. **Run the orchestrator**:
   ```bash
   mvn spring-boot:run
   ```
   
   Or run the WAR file directly:
   ```bash
   java -jar target/taniwha.war
   ```

### What Happens in Standalone Mode

The orchestrator will automatically:
- ✅ Launch Elasticsearch and Snowstorm in Docker containers
- ✅ Launch Python RDF Builder service (requires local repo clone)
- ✅ Launch Python FHIR API service (requires local repo clone)
- ✅ Connect to your specified MongoDB instance

### Verify Services

- Orchestrator API: http://localhost:8088/taniwha
- MongoDB: Your configured URI
- Elasticsearch: http://localhost:9200
- Snowstorm: http://localhost:9100
- RDF Builder: http://localhost:8000/docs
- FHIR API: http://localhost:8001/docs

## Option 2: Standalone Mode with Docker MongoDB

Same as Option 1, but start MongoDB in Docker first:

```bash
# Start MongoDB in Docker
docker run -d \
  --name mediata-mongodb \
  -p 27017:27017 \
  -v mongodb-data:/data/db \
  mongo:7.0

# Update .env to use local Docker MongoDB
MONGODB_URI=mongodb://localhost:27017/mediata

# Run orchestrator as in Option 1
mvn spring-boot:run
```

## Option 3: Full Docker Compose Stack

Run everything in Docker (recommended for production-like environment):

```bash
# Start all services
docker-compose up -d

# Check status
docker-compose ps

# View logs
docker-compose logs -f orchestrator
```

All services run in containers with proper networking.

## Option 4: Hybrid - Docker Services with Local Orchestrator

Run services in Docker but orchestrator locally (useful for development/debugging):

### Setup

1. **Start only the dependent services**:
   ```bash
   # Start MongoDB, Elasticsearch, Snowstorm, RDF Builder, FHIR API
   docker-compose up -d mongodb elasticsearch snowstorm rdf-builder fhir-api
   ```

2. **Configure orchestrator to connect to Docker services**:
   
   Create/update `.env`:
   ```bash
   # Use Docker MongoDB
   MONGODB_URI=mongodb://localhost:27017/mediata
   
   # JWT Secret
   JWT_SECRET=your-secret-key-here
   
   # Point to Docker services
   PYURL=http://localhost:8000
   FHIRPORT=8001
   SNOWSTORM_HOST_PORT=9100
   SNOWSTORM_ES_HOST_PORT=9200
   
   # Disable auto-launching since services are in Docker
   SPRING_PROFILES_ACTIVE=docker
   ```

3. **Run orchestrator locally**:
   ```bash
   # With Maven
   mvn spring-boot:run -Dspring-boot.run.profiles=docker
   
   # Or with JAR
   java -jar -Dspring.profiles.active=docker target/taniwha.war
   ```

### What This Does

- ✅ MongoDB runs in Docker (exposed on localhost:27017)
- ✅ Elasticsearch/Snowstorm run in Docker
- ✅ RDF Builder runs in Docker (exposed on localhost:8000)
- ✅ FHIR API runs in Docker (exposed on localhost:8001)
- ✅ Orchestrator runs locally in your IDE/terminal
- ✅ No auto-launching of services (disabled by docker profile)

## Option 5: Cloud MongoDB with Docker Services

Use MongoDB Atlas while running other services in Docker:

### Setup

1. **Start services except orchestrator**:
   ```bash
   docker-compose up -d elasticsearch snowstorm rdf-builder fhir-api
   ```

2. **Configure `.env` for cloud MongoDB**:
   ```bash
   # MongoDB Atlas
   MONGODB_URI=mongodb+srv://username:password@cluster.mongodb.net/mediata
   
   JWT_SECRET=your-secret-key-here
   PYURL=http://localhost:8000
   FHIRPORT=8001
   ```

3. **Run orchestrator locally**:
   ```bash
   mvn spring-boot:run -Dspring-boot.run.profiles=docker
   ```

## Configuration Reference

### Key Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `MONGODB_URI` | Required | MongoDB connection string |
| `JWT_SECRET` | Required | Secret for JWT token generation |
| `PORT` | 8088 | Orchestrator HTTP port |
| `KERBEROS_PORT` | 8089 | Kerberos KDC port |
| `PYURL` | http://localhost:8000 | RDF Builder service URL |
| `FHIRPORT` | 8001 | FHIR API service port |
| `SNOWSTORM_HOST_PORT` | 9100 | Snowstorm API port |

### Spring Profiles

- **No profile** (default): Standalone mode with auto-launching
- **docker**: Docker mode with service auto-launching disabled

To activate docker profile:
```bash
# With Maven
mvn spring-boot:run -Dspring-boot.run.profiles=docker

# With JAR
java -jar -Dspring.profiles.active=docker target/taniwha.war

# With environment variable
export SPRING_PROFILES_ACTIVE=docker
mvn spring-boot:run
```

## Troubleshooting

### Port Conflicts

If ports are already in use, customize them in `.env`:
```bash
PORT=8090
KERBEROS_PORT=8091
```

### MongoDB Connection Issues

Test your MongoDB connection:
```bash
# For local MongoDB
mongosh mongodb://localhost:27017/mediata

# For MongoDB Atlas
mongosh "mongodb+srv://username:password@cluster.mongodb.net/mediata"
```

### Service Auto-Launch Issues

If Python services fail to launch in standalone mode:
1. Ensure repositories are cloned in the project root
2. Check Python 3.11+ is installed
3. Check logs: `tail -f logs/app.log`

### Docker Service Issues

If Docker services aren't accessible:
```bash
# Check services are running
docker-compose ps

# Check logs
docker-compose logs mongodb
docker-compose logs rdf-builder
```

## Quick Reference Commands

```bash
# Full Docker stack
docker-compose up -d

# Local orchestrator with Docker services
docker-compose up -d mongodb elasticsearch snowstorm rdf-builder fhir-api
mvn spring-boot:run -Dspring-boot.run.profiles=docker

# Standalone mode (auto-launches services)
mvn spring-boot:run

# Stop Docker services
docker-compose down

# Stop and remove volumes
docker-compose down -v
```

## Summary

| Mode | MongoDB | Services | Orchestrator | Use Case |
|------|---------|----------|--------------|----------|
| **Option 1** | Cloud/Local | Auto-launched | Local JAR | Development with cloud DB |
| **Option 2** | Docker | Auto-launched | Local JAR | Full local development |
| **Option 3** | Docker | Docker | Docker | Production-like testing |
| **Option 4** | Docker | Docker | Local JAR | Debugging orchestrator |
| **Option 5** | Cloud | Docker | Local JAR | Development with cloud DB |

Choose the option that best fits your development workflow!
