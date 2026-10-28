#!/usr/bin/env sh
# Spórolós Konyha – fordítás és indítás külső függőség nélkül (csak JDK 21+ kell).
#   ./build.sh          fordít + futtatható JAR (target/sporolos-konyha.jar)
#   ./build.sh test     fordít + lefuttatja a teszteket
#   ./build.sh run      fordít + elindítja az appot
set -e
cd "$(dirname "$0")"

rm -rf target/classes target/test-classes
mkdir -p target/classes target/test-classes

find src/main/java -name '*.java' > target/sources.txt
javac -encoding UTF-8 --release 21 -d target/classes @target/sources.txt
cp -r src/main/resources/. target/classes/

printf 'Main-Class: hu.sporolos.App\n' > target/manifest.txt
jar cfm target/sporolos-konyha.jar target/manifest.txt -C target/classes .
echo "Kész: target/sporolos-konyha.jar"

case "$1" in
  test)
    find src/test/java -name '*.java' > target/test-sources.txt
    javac -encoding UTF-8 --release 21 -cp target/classes -d target/test-classes @target/test-sources.txt
    cp -r src/test/resources/. target/test-classes/ 2>/dev/null || true
    java -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -cp target/classes:target/test-classes hu.sporolos.AllTests
    ;;
  run)
    java -jar target/sporolos-konyha.jar
    ;;
esac
