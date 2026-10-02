# Verso application image (reference 18.1, ADR-0009): a multi-stage build without new build plugins (the reference's
# second option; Jib was not added). Base images are pinned by digest; Renovate/Dependabot updates them.
#
#   docker compose build            # or: docker build -t verso:local .
#
# Tests do not run here: CI runs them before it builds the image. The build stage only compiles and packages.

FROM eclipse-temurin:25.0.4.1_1-jdk-noble@sha256:0d623ea18d7b0fe1e12a2c0a920f7e950cad433387e5a49d3611e1507fa07602 AS build
WORKDIR /src
# unzip: without it mvnw silently downloads the .tar.gz distribution instead of the .zip, whose SHA-256 is the one
# pinned in maven-wrapper.properties, and the checksum validation fails. Build stage only; not in the final image.
RUN apt-get update \
 && apt-get install --yes --no-install-recommends unzip \
 && rm -rf /var/lib/apt/lists/*
# Build inputs only (.dockerignore keeps .git, secrets, docs and target out of the context).
COPY .mvn .mvn
COPY mvnw pom.xml ./
COPY platform platform
COPY services services
COPY verso-app verso-app
RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw -B -ntp -q -pl verso-app -am package -DskipTests \
 && cp verso-app/target/verso-app-*.jar /src/app.jar

FROM eclipse-temurin:25.0.4.1_1-jre-noble@sha256:398f810215757dc1926390014272579fb0e57c41ef1c8aa4f64ae761613a168b AS extract
WORKDIR /app
COPY --from=build /src/app.jar app.jar
RUN java -Djarmode=tools -jar app.jar extract --layers --destination extracted

FROM eclipse-temurin:25.0.4.1_1-jre-noble@sha256:398f810215757dc1926390014272579fb0e57c41ef1c8aa4f64ae761613a168b
RUN useradd --system --uid 10001 --no-create-home --shell /usr/sbin/nologin app
WORKDIR /app
# Layers from least to most frequently changing: a code change rebuilds only the last one.
COPY --from=extract /app/extracted/dependencies/ ./
COPY --from=extract /app/extracted/spring-boot-loader/ ./
COPY --from=extract /app/extracted/snapshot-dependencies/ ./
COPY --from=extract /app/extracted/application/ ./
USER 10001
EXPOSE 8080 8081
# No wget/curl in the JRE image: bash's /dev/tcp asks the readiness probe on the management port (8081).
HEALTHCHECK --interval=15s --timeout=3s --start-period=60s --retries=3 CMD ["bash", "-c", \
  "exec 3<>/dev/tcp/127.0.0.1/8081 && printf 'GET /actuator/health/readiness HTTP/1.0\\r\\nHost: localhost\\r\\n\\r\\n' >&3 && grep -q '\"status\":\"UP\"' <&3"]
# G1 chosen explicitly: below 2 CPUs / ~1792 MB the JVM would pick Serial GC (reference 18.1). Heap from one place.
ENTRYPOINT ["java", "-XX:+UseG1GC", "-XX:MaxRAMPercentage=75", "-XX:+UseCompactObjectHeaders", "-XX:+ExitOnOutOfMemoryError", "-jar", "app.jar"]
