#!/usr/bin/env bash
# Extracts the vanilla data from the pinned 26.3 server jar and runs scripts/anvil_costs.py on it:
# prints the vanilla vs yAnvil cost table and fails if any yAnvil step would be Too Expensive.
# Usage: scripts/anvil-costs.sh [workdir] [--json]
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT=$PWD
DIR=${1:-/tmp/yanvil-ci}
JAR_URL=https://piston-data.mojang.com/v1/objects/33680f5f2ac32864d6d7cf5e56a705fdb3e05f4c/server.jar
JAR_SHA1=33680f5f2ac32864d6d7cf5e56a705fdb3e05f4c

mkdir -p "$DIR"; cd "$DIR"
if ! echo "$JAR_SHA1  server.jar" | sha1sum -c --status 2>/dev/null; then
  curl -fsSL -o server.jar "$JAR_URL"
  echo "$JAR_SHA1  server.jar" | sha1sum -c --quiet
fi
rm -rf vanilla; mkdir vanilla
unzip -qo server.jar META-INF/versions/26.3/server-26.3.jar -d vanilla
unzip -qo vanilla/META-INF/versions/26.3/server-26.3.jar 'data/minecraft/enchantment/*' 'data/minecraft/tags/*' -d vanilla
python3 -I "$ROOT/scripts/anvil_costs.py" "$DIR/vanilla/data/minecraft" "${@:2}"
