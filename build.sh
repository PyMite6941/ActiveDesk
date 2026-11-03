#!/usr/bin/env sh
# Builds dist/activedesk.jar with only the JDK (17+). No Maven/Gradle needed.
set -eu
cd "$(dirname "$0")"
rm -rf build dist
mkdir -p build/classes dist
find src/main/java -name '*.java' > build/sources.txt
javac --release 17 -encoding UTF-8 -Xlint:all,-serial -d build/classes @build/sources.txt
cp -R src/main/resources/. build/classes/
printf 'Main-Class: dev.activedesk.ActiveDesk\n' > build/manifest.txt
jar --create --file dist/activedesk.jar --manifest build/manifest.txt -C build/classes .
echo "built dist/activedesk.jar"
