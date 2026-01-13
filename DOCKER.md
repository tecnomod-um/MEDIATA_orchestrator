# Docker Compose Setup

This directory contains the complete Docker Compose setup for the MEDIATA Orchestrator and all its dependencies.

## Architecture

The stack includes the following services:

1. **orchestrator** - The main Spring Boot application (port 8088, Kerberos port 8089)
2. **mongodb** - MongoDB database (port 27017)
3. **elasticsearch** - Elasticsearch for Snowstorm (port 9200)
4. **snowstorm** - SNOMED CT terminology server (port 9100)
5. **rdf-builder** - Python RDF/YARRRML builder service (port 8000)
6. **fhir-api** - Python FHIR clustering API service (port 8001)

## Prerequisites

- Docker Engine 20.10+
- Docker Compose 2.0+
- At least 4GB of RAM available for Docker

## Quick Start

1. Copy the example environment file:
   ```bash
   cp .env.example .env
   ```

2. Edit `.env` and set your JWT secret:
   ```bash
   JWT_SECRET=your-secret-key-here
   ```

3. Start all services:
   ```bash
   docker-compose up -d
   ```

4. Check service status:
   ```bash
   docker-compose ps
   ```

5. View logs:
   ```bash
   docker-compose logs -f orchestrator
   ```

## Service URLs

Once running, the services are available at:

- Orchestrator API: http://localhost:8088/taniwha
- Orchestrator Health: http://localhost:8088/taniwha/actuator/health
- MongoDB: mongodb://localhost:27017/mediata
- Elasticsearch: http://localhost:9200
- Snowstorm: http://localhost:9100
- RDF Builder: http://localhost:8000/docs
- FHIR API: http://localhost:8001/docs

## Port Mapping

| Service       | Container Port | Host Port | Description                    |
|---------------|----------------|-----------|--------------------------------|
| orchestrator  | 8088           | 8088      | Main application API           |
| orchestrator  | 8089           | 8089      | Kerberos KDC                   |
| mongodb       | 27017          | 27017     | MongoDB database               |
| elasticsearch | 9200           | 9200      | Elasticsearch                  |
| snowstorm     | 8080           | 9100      | Snowstorm API                  |
| rdf-builder   | 8000           | 8000      | RDF Builder API                |
| fhir-api      | 8001           | 8001      | FHIR Clustering API            |

## Useful Commands

### Start services
```bash
docker-compose up -d
```

### Stop services
```bash
docker-compose down
```

### Restart a specific service
```bash
docker-compose restart orchestrator
```

### View logs
```bash
# All services
docker-compose logs -f

# Specific service
docker-compose logs -f orchestrator
```

### Rebuild after code changes
```bash
docker-compose up -d --build orchestrator
```

### Clean up everything (including volumes)
```bash
docker-compose down -v
```

## Development

For local development without Docker Compose:

1. Ensure MongoDB, Elasticsearch, and Snowstorm are running
2. Clone the Python service repositories:
   ```bash
   git clone https://github.com/tecnomod-um/mediata-rdf-builder.git
   git clone https://github.com/tecnomod-um/InteroperabilityFHIRAPI.git
   ```
3. Run the application with the default profile:
   ```bash
   mvn spring-boot:run
   ```

## Troubleshooting

### SSL Certificate Issues During Build

If you encounter SSL certificate errors when building the orchestrator image (e.g., "unable to find valid certification path"), you have two options:

**Option 1: Use the updated Dockerfile with CA certificate updates**
The main `Dockerfile` has been updated to refresh CA certificates before downloading dependencies. Simply rebuild:
```bash
docker-compose build --no-cache orchestrator
```

**Option 2: Build locally and use pre-built JAR**
This is the recommended approach for environments with strict SSL policies:

1. Build the application locally:
   ```bash
   mvn clean package -DskipTests
   ```

2. Edit `docker-compose.yml` and change the orchestrator build configuration:
   ```yaml
   orchestrator:
     build:
       context: .
       dockerfile: Dockerfile.prebuilt  # Use pre-built JAR
   ```

3. Build and start:
   ```bash
   docker-compose up -d --build orchestrator
   ```

**Option 3: Use existing JAR from target directory**
If you already have a built JAR file:
```bash
# Ensure target/taniwha.war exists
ls -lh target/taniwha.war

# Build with pre-built Dockerfile
docker-compose build --no-cache orchestrator
```

### Services not starting
Check service logs:
```bash
docker-compose logs <service-name>
```

### Port conflicts
If ports are already in use, edit `.env` and change the port mappings.

### Memory issues
Increase Docker's memory limit in Docker Desktop settings or adjust Elasticsearch memory in `docker-compose.yml`:
```yaml
environment:
  - ES_JAVA_OPTS=-Xms512m -Xmx512m
```

### Snowstorm not responding
Snowstorm may take 1-2 minutes to fully start. Check its logs:
```bash
docker-compose logs -f snowstorm
```

## Network

All services communicate through the `mediata-network` bridge network. Services can reference each other by their service names (e.g., `mongodb`, `elasticsearch`, `rdf-builder`).

## Volumes

Persistent data is stored in Docker volumes:
- `mongodb-data` - MongoDB database files
- `elasticsearch-data` - Elasticsearch indices
- `kerby-data` - Kerberos configuration and data
