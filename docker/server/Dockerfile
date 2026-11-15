# The build output is platform independent (JVM bytecode and scripts): compile once natively, not once per target under QEMU.
FROM --platform=$BUILDPLATFORM node:24.22.3-bookworm-slim AS frontend
WORKDIR /frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY frontend/ ./
RUN npm run build
FROM --platform=$BUILDPLATFORM gradle:9.9.1-jdk25-ubi AS build
ARG RELEASE_VERSION=0.6.0-SNAPSHOT
WORKDIR /workspace
COPY . .
COPY --from=frontend /frontend/dist frontend/dist
RUN gradle --no-daemon -PreleaseVersion=${RELEASE_VERSION} -x :modsync-server:installFrontend -x :modsync-server:buildFrontend :modsync-server:bootJar && cp modsync-server/build/libs/modsync-server.jar /tmp/server.jar && javac -d /health docker/server/Health.java
FROM eclipse-temurin:25.0.5_8-jre-noble
RUN apt-get update && apt-get install -y --only-upgrade --no-install-recommends apt libapt-pkg6.0t64 libbrotli1 libssl3t64 openssl && rm -rf /var/lib/apt/lists/*
WORKDIR /app
RUN groupadd --gid 10001 modsync && useradd --uid 10001 --gid 10001 --no-create-home modsync
COPY --from=build /tmp/server.jar /app/server.jar
COPY --from=build /health /opt/health
COPY docker/server/entrypoint.sh /app/entrypoint.sh
USER 10001:10001
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=10s --start-period=90s CMD ["java","-cp","/opt/health","Health"]
ENTRYPOINT ["/app/entrypoint.sh"]
