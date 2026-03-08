# Stage 1: Build
FROM amazoncorretto:25-alpine AS builder
RUN apk add --no-cache nodejs npm
WORKDIR /build
COPY gradlew gradlew.bat ./
COPY gradle/ gradle/
COPY build.gradle.kts settings.gradle.kts ./
COPY gradle/libs.versions.toml gradle/libs.versions.toml
RUN chmod +x gradlew && ./gradlew --no-daemon --version
COPY src/ src/
COPY transcode-common/ transcode-common/
COPY transcode-buddy/ transcode-buddy/
RUN ./gradlew --no-daemon -Pvaadin.productionMode installDist

# Stage 2: Runtime
FROM amazoncorretto:25-alpine
RUN apk add --no-cache ffmpeg curl
RUN addgroup -g 100 -S users 2>/dev/null; adduser -u 1046 -G users -S app
WORKDIR /app
COPY --from=builder --chown=app:users /build/build/install/mediaManager/ ./
RUN mkdir -p /cache && chown app:users /cache && ln -s /cache /app/data
USER app
EXPOSE 8080 8081
CMD [ \
    "./bin/mediaManager", \
    "--listen_on_all_interfaces", \
    "--disable_local_transcoding" \
]
