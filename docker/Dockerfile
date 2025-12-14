FROM eclipse-temurin:22_36-jre-alpine AS runtime

WORKDIR /app

RUN apk add --no-cache \
    ca-certificates \
    tini \
    tzdata

COPY target/doplarr.jar .

ENTRYPOINT ["/sbin/tini", "--"]
CMD ["java", "-jar", "/app/doplarr.jar"]

LABEL maintainer="Kiran Shila <me@kiranshila.com>"
LABEL org.opencontainers.image.source="https://github.com/kiranshila/Doplarr"
