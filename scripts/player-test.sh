#!/usr/bin/env bash
# Plays yAnvil with a mock player: boots the 26.3 server headless with the built pack and runs
# harness/PlayerTest.java, which picks up items, opens an anvil and combines books like a client
# would, checking repair_cost after each step. Prints "PLAYERTEST: PASS/FAIL <step>" lines.
# Usage: scripts/player-test.sh   (needs Java 25 and a built .sandstone/output; WORKDIR sets the scratch dir)
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT=$PWD
PACK=${PACK:-$PWD/.sandstone/output/datapack}
DIR=${WORKDIR:-/tmp/yanvil-player}
JAR_URL=https://piston-data.mojang.com/v1/objects/33680f5f2ac32864d6d7cf5e56a705fdb3e05f4c/server.jar
JAR_SHA1=33680f5f2ac32864d6d7cf5e56a705fdb3e05f4c

[ -f "$PACK/pack.mcmeta" ] || { echo "no built datapack, run the build first"; exit 1; }
mkdir -p "$DIR"; cd "$DIR"
if ! echo "$JAR_SHA1  server.jar" | sha1sum -c --status 2>/dev/null; then
  curl -fsSL -o server.jar "$JAR_URL"
  echo "$JAR_SHA1  server.jar" | sha1sum -c --quiet
fi
# The server jar is a bundle: the game and its libraries are jars inside it
rm -rf bundle classes run; mkdir -p classes run/world/datapacks
unzip -qo server.jar 'META-INF/versions/*' 'META-INF/libraries/*' -d bundle
CP=$(find "$DIR/bundle" -name '*.jar' | sort | paste -sd:)
javac -nowarn -cp "$CP" -d classes "$ROOT/harness/PlayerTest.java"
cp -r "$PACK" run/world/datapacks/yanvil
cd run
echo eula=true > eula.txt
printf '%s\n' level-seed=12345 online-mode=false server-port=25596 > server.properties
# Offline mode: the mock player has no Mojang session. Its startup banner is filtered below
# PlayerTest reaches into the server thread by reflection; it halts the JVM when done
java -Xmx2G --add-opens java.base/java.lang=ALL-UNNAMED -cp "$DIR/classes:$CP" PlayerTest \
  < /dev/null > log.txt 2>&1 || true
grep 'PLAYERTEST' log.txt || { tail -40 log.txt; echo 'player test did not run'; exit 1; }
if grep -Ei '/(WARN|ERROR)\]|exception|<--\[HERE\]' log.txt | grep -v "Can't keep up" \
  | grep -vE 'OFFLINE/INSECURE|authenticate usernames|hackers to connect|"online-mode"'; then
  echo 'errors or warnings in the log (above)'; exit 1
fi
grep -q 'PLAYERTEST: FAIL' log.txt && { echo 'player test failed'; exit 1; }
grep -q 'PLAYERTEST: done' log.txt || { echo 'player test did not finish'; exit 1; }
echo 'player test passed'
