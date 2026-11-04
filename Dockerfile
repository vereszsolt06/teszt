# Spórolós Konyha – konténer. Fordítás JDK-val, futás JRE-vel; külső Java-függőség nincs.
FROM eclipse-temurin:21-jdk AS build
# a verziószám a JAR-ba kerül (/healthz, /metrics, induló napló): docker build --build-arg VERSION=$(git describe --tags --always) .
ARG VERSION=docker
WORKDIR /src
COPY build.sh ./
COPY src ./src
RUN VERSION="$VERSION" sh build.sh

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/target/sporolos-konyha.jar /app/sporolos-konyha.jar
RUN mkdir -p /app/data && chown 10001 /app/data
# data/: a letöltött Árfigyelő-Excel és horvát archívum, fájlos módban a háztartások adatai és az árelőzmény
VOLUME /app/data
ENV HOST=0.0.0.0 PORT=8080 DATA_DIR=/app/data JAVA_OPTS=""
EXPOSE 8080
USER 10001
# a /healthz 200-at ad, amint a webszerver fut (curl nélkül, a bash /dev/tcp-jével)
HEALTHCHECK --interval=30s --timeout=3s --start-period=20s --retries=3 \
  CMD bash -c 'exec 3<>/dev/tcp/127.0.0.1/${PORT:-8080} && printf "GET /healthz HTTP/1.0\r\n\r\n" >&3 && grep -q "\"ok\":true" <&3'
# memóriahiba esetén kilép (az orchestrátor újraindítja) – ne fusson tovább egy félig működő JVM;
# JAVA_OPTS-szal bővíthető (pl. -Xlog:gc vagy -XX:MaxRAMPercentage=60). Az exec miatt a java kapja a SIGTERM-et.
CMD ["sh", "-c", "exec java -XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError $JAVA_OPTS -jar /app/sporolos-konyha.jar"]
