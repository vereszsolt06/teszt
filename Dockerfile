# Spórolós Konyha – konténer. Fordítás JDK-val, futás JRE-vel; külső Java-függőség nincs.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY build.sh ./
COPY src ./src
RUN sh build.sh

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/target/sporolos-konyha.jar /app/sporolos-konyha.jar
RUN mkdir -p /app/data && chown 10001 /app/data
# data/: a letöltött Árfigyelő-Excel, a fotók gyorsítótára (fájlos módban), saját fotók (data/kepek)
VOLUME /app/data
ENV HOST=0.0.0.0 PORT=8080 DATA_DIR=/app/data
EXPOSE 8080
USER 10001
# a /healthz 200-at ad, amint a webszerver fut (curl nélkül, a bash /dev/tcp-jével)
HEALTHCHECK --interval=30s --timeout=3s --start-period=20s --retries=3 \
  CMD bash -c 'exec 3<>/dev/tcp/127.0.0.1/${PORT:-8080} && printf "GET /healthz HTTP/1.0\r\n\r\n" >&3 && grep -q "\"ok\":true" <&3'
CMD ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/sporolos-konyha.jar"]
