# Build context: services/fix-gateway. Optional MAVEN_MIRROR build arg, e.g. a Maven Central mirror URL.
FROM maven:3.9-eclipse-temurin-8 AS build
ARG MAVEN_MIRROR=
WORKDIR /src
RUN if [ -n "$MAVEN_MIRROR" ]; then mkdir -p /root/.m2 && printf '<settings><mirrors><mirror><id>mirror</id><url>%s</url><mirrorOf>central</mirrorOf></mirror></mirrors></settings>' "$MAVEN_MIRROR" > /root/.m2/settings.xml; fi
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q -o -DskipTests package dependency:copy-dependencies -DincludeScope=runtime -DoutputDirectory=target/lib

FROM eclipse-temurin:8-jre
WORKDIR /app
COPY --from=build /src/target/lib /app/lib
COPY --from=build /src/target/fix-gateway.jar /app/fix-gateway.jar
ENTRYPOINT ["java", "-cp", "/app/fix-gateway.jar:/app/lib/*", "fixgw.Main"]
