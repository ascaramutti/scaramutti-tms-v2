# ==========================================
# Stage 1: Build (Quarkus JVM)
# ==========================================
FROM maven:3.9-eclipse-temurin-21 AS builder

WORKDIR /app

# Copiar pom.xml primero para cachear dependencias
COPY pom.xml .
RUN mvn dependency:go-offline -q

# Copiar el código fuente y compilar
COPY src ./src
RUN mvn package -DskipTests -q

# ==========================================
# Stage 2: Production (JRE liviano)
# ==========================================
FROM eclipse-temurin:21-jre-alpine AS production

WORKDIR /app

# Crear usuario no-root
RUN addgroup -g 1001 -S quarkus && \
    adduser -S quarkus -u 1001

# Copiar el jar generado por Quarkus
COPY --from=builder /app/target/quarkus-app/lib/ ./lib/
COPY --from=builder /app/target/quarkus-app/*.jar ./
COPY --from=builder /app/target/quarkus-app/app/ ./app/
COPY --from=builder /app/target/quarkus-app/quarkus/ ./quarkus/

RUN chown -R quarkus:quarkus /app
USER quarkus

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=10s --start-period=30s --retries=3 \
    CMD wget --quiet --tries=1 --spider http://localhost:8080/api/v1/q/health || exit 1

CMD ["java", "-jar", "quarkus-run.jar"]
