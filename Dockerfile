# Stage 1a: Build Angular SPA (official Node image, no curl)
FROM --platform=$BUILDPLATFORM node:22-alpine AS angular-builder
WORKDIR /build
COPY web-app/package.json web-app/package-lock.json ./
RUN npm ci
COPY web-app/ ./
RUN npx ng build --base-href="/app/"

# Stage 1b: Build server (glibc-based image for protoc plugin compatibility)
FROM --platform=$BUILDPLATFORM amazoncorretto:25 AS builder
RUN yum install -y findutils && yum clean all
WORKDIR /build
COPY gradlew gradlew.bat ./
COPY gradle/ gradle/
COPY build.gradle.kts settings.gradle.kts gradle.properties ./
COPY gradle/libs.versions.toml gradle/libs.versions.toml
RUN chmod +x gradlew && ./gradlew --no-daemon --version
COPY proto/ proto/
COPY src/ src/
COPY transcode-common/ transcode-common/
COPY transcode-buddy/ transcode-buddy/
COPY logging-common/ logging-common/
RUN ./gradlew --no-daemon --max-workers=2 installDist

# Stage 2: Runtime (Alpine for smaller image)
FROM amazoncorretto:25-alpine
ENV TZ=UTC
RUN apk add --no-cache ffmpeg curl gcompat libstdc++ libgcc
# Download go2rtc binary for camera stream relay (never port-map 1984 externally)
ARG GO2RTC_VERSION=1.9.8
RUN ARCH=$(uname -m | sed 's/x86_64/amd64/;s/aarch64/arm64/') && \
    curl -fsSL "https://github.com/AlexxIT/go2rtc/releases/download/v${GO2RTC_VERSION}/go2rtc_linux_${ARCH}" \
         -o /usr/local/bin/go2rtc && \
    chmod +x /usr/local/bin/go2rtc

# Essentia streaming extractor for ML-grade BPM analysis. The binary
# is glibc-linked so we rely on gcompat (installed above) for its
# dynamic loader to work on Alpine's musl. The upstream build only
# publishes x86_64 and i686 variants — on aarch64 the download is
# skipped and EssentiaAgent self-disables on first startup (it probes
# the binary and logs a warning when absent).
ARG ESSENTIA_URL=https://data.metabrainz.org/pub/musicbrainz/acousticbrainz/extractors/essentia-extractor-v2.1_beta2-linux-x86_64.tar.gz
RUN ARCH=$(uname -m) && \
    if [ "$ARCH" = "x86_64" ]; then \
        curl -fsSL "$ESSENTIA_URL" -o /tmp/essentia.tar.gz && \
        mkdir -p /tmp/essentia && \
        tar -xzf /tmp/essentia.tar.gz -C /tmp/essentia && \
        find /tmp/essentia -name "essentia_streaming_extractor_music" -type f -exec \
            install -m 0755 {} /usr/local/bin/essentia_streaming_extractor_music \; && \
        rm -rf /tmp/essentia /tmp/essentia.tar.gz && \
        echo "Essentia installed: $(ls -l /usr/local/bin/essentia_streaming_extractor_music)"; \
    else \
        echo "Essentia skipped on arch=$ARCH (only x86_64 prebuilt available)"; \
    fi
RUN addgroup -g 100 -S users 2>/dev/null; adduser -u 1046 -G users -S app
WORKDIR /app
COPY --from=builder --chown=app:users /build/build/install/mediaManager/ ./
COPY --from=angular-builder --chown=app:users /build/dist/media-manager/browser/ ./spa/
RUN mkdir -p /cache && chown app:users /cache && ln -s /cache /app/data
USER app
EXPOSE 8081 9090
CMD [ \
    "./bin/mediaManager", \
    "--listen_on_all_interfaces", \
    "--disable_local_transcoding" \
]
