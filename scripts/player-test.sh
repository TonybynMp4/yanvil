#!/usr/bin/env bash
# Plays yAnvil with a mock player: boots the 26.3 server headless with the built pack and runs
# harness/PlayerTest.java, which picks up items, opens an anvil and combines books like a client
# would, checking repair_cost after each step. Prints "PLAYERTEST: PASS/FAIL <step>" lines.
# Usage: scripts/player-test.sh   (needs Java 25 and a built .sandstone/output; WORKDIR sets the scratch
# dir, default /tmp/yanvil-ci, and PACK the datapack folder)
set -euo pipefail
cd "$(dirname "$0")/.."
. scripts/lib/check-log.sh
. scripts/lib/server-jar.sh
ROOT=$PWD
PACK=${PACK:-$PWD/.sandstone/output/datapack}
DIR=${WORKDIR:-/tmp/yanvil-ci}

[ -f "$PACK/pack.mcmeta" ] || { echo "no built datapack, run the build first"; exit 1; }
mkdir -p "$DIR"; cd "$DIR"
fetch_server_jar
# The server jar is a bundle: the game and its libraries are jars inside it
rm -rf bundle classes run; mkdir -p classes run/world/datapacks
unzip -qo server.jar 'META-INF/versions/*' 'META-INF/libraries/*' -d bundle
CP=$(find "$DIR/bundle" -name '*.jar' | sort | paste -sd:)
javac -nowarn -cp "$CP" -d classes "$ROOT/harness/PlayerTest.java"
cp -r "$PACK" run/world/datapacks/yanvil
cd run
echo eula=true > eula.txt
printf '%s\n' level-seed=12345 online-mode=false server-port=25596 > server.properties
# Offline mode: the mock player has no Mojang session (log_problems skips the warning banner)
# PlayerTest reaches into the server thread by reflection; it halts the JVM when done, and timeout
# stops it if it hangs
timeout 600 java -Xmx2G --add-opens java.base/java.lang=ALL-UNNAMED -cp "$DIR/classes:$CP" PlayerTest \
  < /dev/null > log.txt 2>&1 || true
grep 'PLAYERTEST' log.txt || { tail -40 log.txt; echo 'player test did not run'; exit 1; }
if log_problems log.txt; then
  echo 'errors or warnings in the log (above)'; exit 1
fi
grep -q 'PLAYERTEST: FAIL' log.txt && { echo 'player test failed'; exit 1; }
grep -q 'PLAYERTEST: done' log.txt || { echo 'player test did not finish'; exit 1; }
echo 'player test passed'
