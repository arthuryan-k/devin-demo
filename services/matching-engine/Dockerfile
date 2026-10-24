# Standalone matching-engine service: consumes commands.<symbol>, publishes events.<symbol> and marketdata.<symbol>.
# Build context: services/matching-engine. Optional MAVEN_MIRROR build arg, e.g. a Maven Central mirror URL.
FROM maven:3.9-eclipse-temurin-17 AS build
ARG MAVEN_MIRROR=
WORKDIR /src
RUN if [ -n "$MAVEN_MIRROR" ]; then mkdir -p /root/.m2 && printf '<settings><mirrors><mirror><id>mirror</id><url>%s</url><mirrorOf>central</mirrorOf></mirror></mirrors></settings>' "$MAVEN_MIRROR" > /root/.m2/settings.xml; fi
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q -o -DskipTests package dependency:copy-dependencies -DincludeScope=runtime -DoutputDirectory=target/lib

FROM eclipse-temurin:25-jre
RUN useradd --system --uid 10001 engine && mkdir -p /data/journal && chown engine /data/journal
WORKDIR /app
COPY --from=build /src/target/lib/ lib/
COPY --from=build /src/target/orderbook-*.jar app.jar
USER engine
ENV KAFKA_BOOTSTRAP_SERVERS=redpanda:9092 \
    JOURNAL_PATH=/data/journal
VOLUME /data/journal
ENTRYPOINT ["java", "-XX:+ExitOnOutOfMemoryError", "-cp", "app.jar:lib/*", "orderbook.bus.EngineService"]
