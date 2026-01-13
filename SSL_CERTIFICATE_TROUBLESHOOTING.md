# SSL Certificate Troubleshooting Guide

## Problem

When building the Docker image for the orchestrator service, you may encounter SSL certificate errors like:

```
[ERROR] Failed to execute goal on project taniwha: Could not resolve dependencies
sun.security.validator.ValidatorException: PKIX path building failed
sun.security.provider.certpath.SunCertPathBuilderException: unable to find valid certification path to requested target
```

This occurs when Maven tries to download dependencies during the Docker build in environments with strict SSL/TLS policies or certificate issues.

## Solutions

We provide three solutions, in order of recommendation:

### Solution 1: Use Pre-Built JAR (Recommended)

This is the most reliable approach for environments with SSL issues.

**Step 1: Build locally**
```bash
mvn clean package -DskipTests
```

**Step 2: Update docker-compose.yml**

Edit `docker-compose.yml` and change the orchestrator build configuration:

```yaml
orchestrator:
  build:
    context: .
    dockerfile: Dockerfile.prebuilt  # Change from Dockerfile to Dockerfile.prebuilt
```

**Step 3: Build Docker image**
```bash
docker-compose build orchestrator
```

**Step 4: Start services**
```bash
docker-compose up -d
```

### Solution 2: Use Updated Dockerfile with CA Certificates

The main `Dockerfile` has been updated to refresh CA certificates before downloading dependencies.

**Step 1: Clean Docker cache**
```bash
docker-compose build --no-cache orchestrator
```

**Step 2: Start services**
```bash
docker-compose up -d
```

If this still fails, proceed to Solution 1.

### Solution 3: Manual Docker Build with Pre-Built JAR

If you need to build the Docker image manually:

**Step 1: Build the application**
```bash
mvn clean package -DskipTests
```

**Step 2: Build Docker image manually**
```bash
docker build -f Dockerfile.prebuilt -t mediata-orchestrator:latest .
```

**Step 3: Update docker-compose.yml to use the image**
```yaml
orchestrator:
  image: mediata-orchestrator:latest  # Replace build section with this
  # build:
  #   context: .
  #   dockerfile: Dockerfile.prebuilt
```

**Step 4: Start services**
```bash
docker-compose up -d
```

## Understanding the Issue

The SSL certificate issue occurs because:

1. **Maven downloads from HTTPS repositories**: Maven Central requires HTTPS
2. **Docker build environment**: May have outdated or missing CA certificates
3. **Corporate/Sandboxed environments**: May have custom SSL inspection or firewall rules

## Files Modified

1. **Dockerfile** - Updated to refresh CA certificates and handle SSL issues better
2. **Dockerfile.prebuilt** - New file for building with pre-compiled JAR
3. **docker-compose.yml** - Updated with comments about both build options
4. **DOCKER.md** - Added SSL troubleshooting section

## Verification

After building, verify the image exists:

```bash
docker images | grep mediata-orchestrator
```

Test the container:

```bash
# Start just the orchestrator (assuming other services are running)
docker-compose up orchestrator

# Check logs
docker-compose logs -f orchestrator
```

## Production Recommendation

For production deployments:

1. **Build locally** or in a CI/CD pipeline with proper SSL certificates
2. **Push to container registry** (Docker Hub, ECR, GCR, etc.)
3. **Pull pre-built images** in production environments

This avoids build-time issues entirely and follows Docker best practices.

## Additional Resources

- [Docker Multi-Stage Builds](https://docs.docker.com/build/building/multi-stage/)
- [Maven Docker Build Issues](https://maven.apache.org/guides/mini/guide-wagon-providers.html)
- [Spring Boot Docker Documentation](https://spring.io/guides/topicals/spring-boot-docker/)
