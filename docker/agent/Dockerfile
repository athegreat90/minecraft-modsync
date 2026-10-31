ARG COMPONENT=agent
FROM gradle:9.2.1-jdk25-ubi AS build
ARG COMPONENT
ARG RELEASE_VERSION=0.1.0-SNAPSHOT
WORKDIR /workspace
COPY . .
RUN gradle --no-daemon -PreleaseVersion=${RELEASE_VERSION} :modsync-${COMPONENT}:installDist && cp -r modsync-${COMPONENT}/build/install/modsync-${COMPONENT} /opt/distribution
FROM eclipse-temurin:25.0.1_8-jre-noble
ARG COMPONENT
ARG RELEASE_VERSION=0.1.0-SNAPSHOT
WORKDIR /app
RUN groupadd --gid 10001 modsync && useradd --uid 10001 --gid 10001 --no-create-home modsync
COPY --from=build /opt/distribution /opt/modsync
ENV MODSYNC_STATE_DIR=/app/state
ENV MODSYNC_COMPONENT=${COMPONENT}
COPY docker/agent/entrypoint.sh /app/entrypoint.sh
USER 10001:10001
HEALTHCHECK --interval=60s --timeout=15s --start-period=120s CMD ["/app/entrypoint.sh", "status"]
ENTRYPOINT ["/app/entrypoint.sh"]
CMD ["run"]
