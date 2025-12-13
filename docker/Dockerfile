FROM clojure:tools-deps AS builder

WORKDIR /src

# Copy dependency files first for better caching
COPY deps.edn .
COPY build ./build

# Copy source code
COPY src ./src

# Build uberjar
RUN clj -T:build uber

FROM eclipse-temurin:22_36-jre-alpine AS runtime

WORKDIR /app

RUN apk add --no-cache \
    ca-certificates \
    tini \
    tzdata

COPY --from=builder /src/target/doplarr.jar .

ENTRYPOINT ["/sbin/tini", "--"]
CMD ["java", "-jar", "/app/doplarr.jar"]

LABEL maintainer="Kiran Shila <me@kiranshila.com>"
LABEL org.opencontainers.image.source="https://github.com/kiranshila/Doplarr"
