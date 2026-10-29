#!/usr/bin/env bash
# Boots a 26.3 server with the built datapack, prices armor on a mannequin (the per-slot logic
# without a real player), and fails on any load or runtime error. scripts/player-test.sh covers
# the advancements and the anvil with a mock player.
# Usage: scripts/load-test.sh   (needs Java 25 and a built .sandstone/output; WORKDIR sets the scratch
# dir, default /tmp/yanvil-ci, and PACK the datapack folder)
set -euo pipefail
cd "$(dirname "$0")/.."
. scripts/lib/check-log.sh
. scripts/lib/server-jar.sh
PACK=${PACK:-$PWD/.sandstone/output/datapack}
DIR=${WORKDIR:-/tmp/yanvil-ci}

[ -f "$PACK/pack.mcmeta" ] || { echo "no built datapack, run the build first"; exit 1; }
mkdir -p "$DIR"; cd "$DIR"
fetch_server_jar
rm -rf world log.txt
mkdir -p world/datapacks
cp -r "$PACK" world/datapacks/yanvil
echo eula=true > eula.txt
printf '%s\n' level-seed=12345 pause-when-empty-seconds=0 server-port=25598 > server.properties

# Commands go in through a fifo; the server's stdout goes to log.txt. timeout kills a server that
# hangs instead of stopping
rm -f in; mkfifo in
timeout 600 java -Xmx2G -jar server.jar nogui < in > log.txt 2>&1 &
PID=$!
exec 3> in
run() { printf '%s\n' "$@" >&3; }
for _ in $(seq 300); do
  grep -q 'Done (' log.txt && break
  kill -0 $PID 2>/dev/null || break
  sleep 1
done

# Each `execute if ...` without `run` logs "Test passed" when it holds
CHECKS=0
check() { run "$1"; CHECKS=$((CHECKS + 1)); }
if kill -0 $PID 2>/dev/null; then
  run 'datapack list enabled' 'forceload add 0 0'
  for _ in $(seq 60); do
    run 'execute if loaded 8 0 8 run say chunk loaded'
    sleep 1
    grep -q 'chunk loaded' log.txt && break
  done
  # helmet: tier base, boots: base + 2 enchantments, a stale anvil price gets reset, the elytra
  # is "everything else", and the enchanted book in the hand isn't a durability item
  run 'summon minecraft:mannequin 8 200 8 {Tags:["yanvil.test"],NoGravity:1b,Invulnerable:1b,equipment:{head:{id:"minecraft:diamond_helmet"},feet:{id:"minecraft:netherite_boots",components:{"minecraft:enchantments":{"minecraft:protection":4,"minecraft:unbreaking":3}}},legs:{id:"minecraft:leather_leggings",components:{"minecraft:repair_cost":31}},chest:{id:"minecraft:elytra"},mainhand:{id:"minecraft:enchanted_book",components:{"minecraft:stored_enchantments":{"minecraft:mending":1}}},offhand:{id:"minecraft:copper_sword",components:{"minecraft:enchantments":{"minecraft:sharpness":5}}}}}'
  # Entities in a freshly loaded chunk show up a few ticks later
  for _ in $(seq 30); do
    run 'execute if entity @e[tag=yanvil.test] run say mannequin ready'
    sleep 1
    grep -q 'mannequin ready' log.txt && break
  done
  run 'execute as @e[tag=yanvil.test] run function yanvil:fix'
  m='@e[tag=yanvil.test,limit=1]'
  check "execute if items entity $m armor.head minecraft:diamond_helmet[minecraft:repair_cost=5]"
  check "execute if items entity $m armor.feet minecraft:netherite_boots[minecraft:repair_cost=10]"
  check "execute if items entity $m armor.legs minecraft:leather_leggings[minecraft:repair_cost=1]"
  check "execute if items entity $m armor.chest minecraft:elytra[minecraft:repair_cost=5]"
  check "execute if items entity $m weapon.offhand minecraft:copper_sword[minecraft:repair_cost=3]"
  check "execute if items entity $m weapon.mainhand minecraft:enchanted_book[minecraft:repair_cost=0]"
  check 'execute if score #fixed yanvil.tmp matches 5'
  # Already priced: nothing changes the second time
  run 'execute as @e[tag=yanvil.test] run function yanvil:fix'
  check 'execute if score #fixed yanvil.tmp matches 0'
  sleep 1
  run 'reload'
  sleep 3
  run stop
fi
exec 3>&-
wait $PID || true

cat log.txt
echo '----'
fail=0
grep -q 'Done (' log.txt || { echo 'server did not finish starting'; fail=1; }
grep -q 'file/yanvil' log.txt || { echo 'datapack not enabled'; fail=1; }
passed=$(grep -c 'Test passed' log.txt || true)
[ "$passed" = "$CHECKS" ] || { echo "$passed of $CHECKS checks passed"; fail=1; }
if log_problems log.txt; then
  echo 'errors or warnings in the log (above)'; fail=1
fi
[ $fail = 0 ] && echo 'load test passed'
exit $fail
