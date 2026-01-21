# Build stage
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app

# Update CA certificates to fix SSL issues
RUN apt-get update && \
    apt-get install -y ca-certificates && \
    update-ca-certificates && \
    rm -rf /var/lib/apt/lists/*

# Copy pom.xml and download dependencies (for caching)
COPY pom.xml .
RUN mvn dependency:go-offline -B || true

# Copy source code and build
COPY src ./src
RUN mvn clean package -DskipTests -B

# Runtime stage
FROM eclipse-temurin:17-jre-jammy
WORKDIR /app

# Install curl for healthchecks
RUN apt-get update && apt-get install -y curl && rm -rf /var/lib/apt/lists/*

# Create directories for Kerberos
RUN mkdir -p /app/kerby /app/logs

# Copy the built WAR file
COPY --from=build /app/target/taniwha.war /app/taniwha.war

# Copy Kerberos configuration
COPY krb5.conf /etc/krb5.conf

# Expose the application port and Kerberos port
EXPOSE 8088 8089

# Run the application with docker profile
ENTRYPOINT ["java", "-jar", "/app/taniwha.war", "--spring.profiles.active=docker"]
