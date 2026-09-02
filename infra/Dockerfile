# syntax=docker/dockerfile:1

# --- Stage 1: build the React dashboards -------------------------------------
FROM node:22-alpine AS frontend
WORKDIR /app/frontend
COPY frontend/package.json frontend/package-lock.json* ./
RUN npm install
COPY frontend/ ./
# Emits into ../backend/src/main/resources/static (see vite.config.ts)
RUN npm run build

# --- Stage 2: build the Spring Boot jar -------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS backend
WORKDIR /app
COPY backend/pom.xml backend/pom.xml
RUN cd backend && mvn -q -o dependency:go-offline -DskipTests || true
COPY backend/ backend/
COPY --from=frontend /app/backend/src/main/resources/static backend/src/main/resources/static
RUN cd backend && mvn -q -DskipTests package

# --- Stage 3: runtime -------------------------------------------------------
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=backend /app/backend/target/tessera-fleet.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
