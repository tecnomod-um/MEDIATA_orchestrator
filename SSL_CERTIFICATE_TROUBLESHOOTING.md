# SSL Certificate Troubleshooting Guide

## Problem

When building the Docker image for the orchestrator service, you may encounter SSL certificate errors like:

```
[ERROR] Failed to execute goal on project taniwha: Could not resolve dependencies
sun.security.validator.ValidatorException: PKIX path building failed
sun.security.provider.certpath.SunCertPathBuilderException: unable to find valid certification path to requested target
```

This occurs when Maven tries to download dependencies during the Docker build in environments with strict SSL/TLS policies, sandboxed networks, or certificate issues.

## Recommended Solution: Pre-Built JAR Approach

The **pre-built JAR approach** is the **only reliable method** in restricted environments. This is now the default in `docker-compose.yml`.

### Quick Start (Automated)

Use the provided build script for one-command deployment:

```bash
# 1. Configure environment
cp .env.example .env
nano .env  # Set JWT_SECRET (32+ characters)

# 2. Build and deploy
./build-and-deploy.sh
```

### Manual Steps

If you prefer manual control:

**Step 1: Build JAR locally**
```bash
mvn clean package -DskipTests
```

**Step 2: Verify `docker-compose.yml` uses Dockerfile.prebuilt**

The default configuration should already be:
```yaml
orchestrator:
  build:
    context: .
    dockerfile: Dockerfile.prebuilt  # Pre-built JAR approach (default)
```

**Step 3: Start services**
```bash
docker-compose up -d --build
```

### Why This Works

The pre-built approach:
- ✅ Builds JAR on your host machine (uses your existing Maven cache)
- ✅ Bypasses Docker SSL certificate issues entirely
- ✅ Works in sandboxed, corporate, and restricted network environments
- ✅ Faster subsequent builds (reuses local JAR)
- ✅ Production-ready deployment pattern

## Alternative: Standard Dockerfile

### Status

The standard `Dockerfile` **does not work reliably** in sandboxed or restricted network environments due to SSL certificate validation issues when Maven downloads dependencies.

### When It Might Work

The standard Dockerfile may work in:
- Unrestricted network environments
- Environments with properly configured SSL certificates
- Networks with corporate certificate authorities properly installed

### Attempting Standard Build

If you want to try the standard Dockerfile (not recommended):

**Step 1: Update docker-compose.yml**
```yaml
orchestrator:
  build:
    context: .
    dockerfile: Dockerfile  # Standard build (may fail with SSL issues)
```

**Step 2: Try building**
```bash
docker-compose build --no-cache orchestrator
```

If this fails with SSL errors, **use the pre-built JAR approach** instead.

## Troubleshooting

### Build Script Fails

If `./build-and-deploy.sh` fails:

1. Make it executable:
   ```bash
   chmod +x build-and-deploy.sh
   ```

2. Check Maven is installed:
   ```bash
   mvn --version
   ```

3. Build manually:
   ```bash
   mvn clean package -DskipTests
   docker-compose up -d --build
   ```

### Docker Build Fails

If Docker build fails even with pre-built JAR:

1. Verify WAR file exists:
   ```bash
   ls -lh target/taniwha.war
   ```

2. Check Dockerfile.prebuilt is being used:
   ```bash
   grep dockerfile docker-compose.yml | grep orchestrator -A1
   ```

3. Rebuild with verbose output:
   ```bash
   docker-compose build --no-cache --progress=plain orchestrator
   ```
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
