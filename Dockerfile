# syntax=docker/dockerfile:1

# ---- Build stage: compile and package with the Maven wrapper ----
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

# Dependencies first, so this layer is cached until pom.xml changes
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -B -q dependency:go-offline

COPY src/ src/
# Tests need Docker (Testcontainers), so they run outside the image build: ./mvnw test
RUN ./mvnw -B -q package -DskipTests \
    && java -Djarmode=tools -jar target/*.jar extract --layers --launcher --destination target/extracted

# ---- Runtime stage: JRE only, non-root, layered for faster rebuilds ----
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

RUN addgroup -S app && adduser -S app -G app
USER app

# Least to most frequently changing, so code changes only rebuild the last layer
COPY --from=build /workspace/target/extracted/dependencies/ ./
COPY --from=build /workspace/target/extracted/spring-boot-loader/ ./
COPY --from=build /workspace/target/extracted/snapshot-dependencies/ ./
COPY --from=build /workspace/target/extracted/application/ ./

EXPOSE 8080
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75"

HEALTHCHECK --interval=10s --timeout=3s --start-period=30s --retries=5 \
    CMD wget -qO- http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS org.springframework.boot.loader.launch.JarLauncher"]
