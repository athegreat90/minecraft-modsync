FROM gradle:9.8.2-jdk25-ubi AS build
ARG RELEASE_VERSION=0.4.1-SNAPSHOT
WORKDIR /workspace
COPY . .
RUN gradle --no-daemon -PreleaseVersion=${RELEASE_VERSION} :modsync-server:bootJar && cp modsync-server/build/libs/modsync-server.jar /tmp/server.jar && javac -d /health docker/server/Health.java
FROM eclipse-temurin:25.0.1_8-jre-noble
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
