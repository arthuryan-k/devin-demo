# Build context: services/simulator. Optional MAVEN_MIRROR build arg, e.g. a Maven Central mirror URL.
FROM maven:3.9-eclipse-temurin-17 AS build
ARG MAVEN_MIRROR=
WORKDIR /src
RUN if [ -n "$MAVEN_MIRROR" ]; then mkdir -p /root/.m2 && printf '<settings><mirrors><mirror><id>mirror</id><url>%s</url><mirrorOf>central</mirrorOf></mirror></mirrors></settings>' "$MAVEN_MIRROR" > /root/.m2/settings.xml; fi
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q -o -DskipTests package dependency:copy-dependencies -DincludeScope=runtime -DoutputDirectory=target/lib

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /src/target/lib /app/lib
COPY --from=build /src/target/simulator.jar /app/simulator.jar
ENTRYPOINT ["java", "-cp", "/app/simulator.jar:/app/lib/*", "simulator.Main"]
