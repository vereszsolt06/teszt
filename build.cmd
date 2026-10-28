@echo off
rem Spórolós Konyha – fordítás és indítás Windows alatt (csak JDK 21+ kell).
rem   build.cmd          fordít + futtatható JAR (target\sporolos-konyha.jar)
rem   build.cmd test     fordít + lefuttatja a teszteket
rem   build.cmd run      fordít + elindítja az appot
setlocal
chcp 65001 >nul
cd /d "%~dp0"

if exist target\classes rmdir /s /q target\classes
if exist target\test-classes rmdir /s /q target\test-classes
mkdir target\classes target\test-classes 2>nul

dir /s /b src\main\java\*.java > target\sources.txt
javac -encoding UTF-8 --release 21 -d target\classes @target\sources.txt || exit /b 1
xcopy /e /i /q /y src\main\resources target\classes >nul

echo Main-Class: hu.sporolos.App> target\manifest.txt
jar cfm target\sporolos-konyha.jar target\manifest.txt -C target\classes . || exit /b 1
echo Kesz: target\sporolos-konyha.jar

if "%1"=="test" (
  dir /s /b src\test\java\*.java > target\test-sources.txt
  javac -encoding UTF-8 --release 21 -cp target\classes -d target\test-classes @target\test-sources.txt || exit /b 1
  java -Dstdout.encoding=UTF-8 -cp target\classes;target\test-classes hu.sporolos.AllTests
)
if "%1"=="run" (
  java -Dstdout.encoding=UTF-8 -jar target\sporolos-konyha.jar
)
