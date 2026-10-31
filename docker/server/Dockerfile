FROM gradle:9.2.1-jdk25-ubi AS build
ARG RELEASE_VERSION=0.1.0-SNAPSHOT
WORKDIR /workspace
COPY . .
RUN gradle --no-daemon -PreleaseVersion=${RELEASE_VERSION} :modsync-server:bootJar && cp modsync-server/build/libs/modsync-server.jar /tmp/server.jar && javac -d /health docker/server/Health.java
FROM eclipse-temurin:25.0.1_8-jre-noble
WORKDIR /app
RUN groupadd --gid 10001 modsync && useradd --uid 10001 --gid 10001 --no-create-home modsync
COPY --from=build /tmp/server.jar /app/server.jar
COPY --from=build /health /opt/health
COPY docker/server/entrypoint.sh /app/entrypoint.sh
USER 10001:10001
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=10s --start-period=90s CMD ["java","-cp","/opt/health","Health"]
ENTRYPOINT ["/app/entrypoint.sh"]
