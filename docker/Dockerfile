FROM clojure:tools-deps as builder

WORKDIR /src

COPY  ./deps.edn ./
COPY ./build/ /src/build/
COPY ./src/ /src/src/

# Download deps
RUN clojure -P

# Download deps for build alias
RUN clojure -P -T:build

# Build uberjar
RUN clj -T:build uber

FROM eclipse-temurin:22_36-jre-alpine as runtime

WORKDIR /app

RUN \
  apk add --no-cache \
    ca-certificates \
    tini \
    tzdata

COPY --from=builder /src/target/doplarr.jar ./
ENTRYPOINT ["/sbin/tini", "--"]
CMD ["java","-jar","/app/doplarr.jar"]

LABEL "maintainer"="Kiran Shila <me@kiranshila.com>"
LABEL "org.opencontainers.image.source"="https://github.com/kiranshila/Doplarr"
