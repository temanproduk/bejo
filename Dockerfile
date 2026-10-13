## Build stage
# Base images are pinned by digest so a build is reproducible; Dependabot (.github/dependabot.yml)
# proposes updates. The tag is kept for humans — Docker resolves by the digest.
FROM maven:3.9-eclipse-temurin-26-alpine@sha256:4c85d516dd8c7e50d49a403d604e565e0dbb09a790186fe3245725d313ae7cb9 AS builder
WORKDIR /app

# Cache dependencies separately from source: this layer only rebuilds when the POM changes.
COPY pom.xml .
RUN mvn dependency:go-offline -q

COPY src ./src
RUN mvn package -DskipTests -q

# Split the fat jar into layers (dependencies / snapshot-dependencies / application). The
# dependencies change rarely, so on a normal deploy only the small application layer differs: the
# registry push, and a cold instance's image pull, move a few MB instead of the whole ~100 MB jar.
# mkdir: a layer with no files is not created by `extract`, and COPY of a missing directory fails.
RUN java -Djarmode=tools -jar target/bejo.jar extract --layers --destination target/extracted \
 && mkdir -p target/extracted/dependencies target/extracted/snapshot-dependencies \
             target/extracted/spring-boot-loader target/extracted/application

## Runtime stage
FROM eclipse-temurin:25-jre-alpine@sha256:3c0a9084927a221ccd1d007fcaf614465672c0af37aaa834c5184483afe56d61
WORKDIR /app

RUN addgroup -S bejo && adduser -S bejo -G bejo

# Least-changing layer first, so Docker's cache and the registry reuse it.
COPY --from=builder --chown=bejo:bejo /app/target/extracted/dependencies/ ./
COPY --from=builder --chown=bejo:bejo /app/target/extracted/spring-boot-loader/ ./
COPY --from=builder --chown=bejo:bejo /app/target/extracted/snapshot-dependencies/ ./
COPY --from=builder --chown=bejo:bejo /app/target/extracted/application/ ./

USER bejo
EXPOSE 8080

# JAVA_OPTS replaces the default flags when set (it is not appended). The default caps the heap at
# 75% of the container's memory limit; lower it if the platform OOM-kills the container, since
# metaspace, thread stacks and the Firebase/GCS clients live outside the heap.
ENTRYPOINT ["sh", "-c", "exec java ${JAVA_OPTS:--XX:MaxRAMPercentage=75} -Djava.security.egd=file:/dev/./urandom -jar /app/bejo.jar"]
