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
RUN apk add --no-cache ffmpeg curl
# Download go2rtc binary for camera stream relay (never port-map 1984 externally)
ARG GO2RTC_VERSION=1.9.8
RUN ARCH=$(uname -m | sed 's/x86_64/amd64/;s/aarch64/arm64/') && \
    curl -fsSL "https://github.com/AlexxIT/go2rtc/releases/download/v${GO2RTC_VERSION}/go2rtc_linux_${ARCH}" \
         -o /usr/local/bin/go2rtc && \
    chmod +x /usr/local/bin/go2rtc
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
