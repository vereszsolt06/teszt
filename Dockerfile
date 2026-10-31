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
CMD ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/sporolos-konyha.jar"]
