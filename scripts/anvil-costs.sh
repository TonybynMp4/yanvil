#!/usr/bin/env bash
# Extracts the vanilla data from the pinned 26.3 server jar and runs scripts/anvil_costs.py on it:
# prints the vanilla vs yAnvil cost table and fails if any yAnvil step would be Too Expensive.
# Usage: scripts/anvil-costs.sh [--json]   (WORKDIR sets the scratch dir, default /tmp/yanvil-ci)
set -euo pipefail
cd "$(dirname "$0")/.."
. scripts/lib/server-jar.sh
ROOT=$PWD
DIR=${WORKDIR:-/tmp/yanvil-ci}

mkdir -p "$DIR"; cd "$DIR"
fetch_server_jar
rm -rf vanilla; mkdir vanilla
unzip -qo server.jar META-INF/versions/26.3/server-26.3.jar -d vanilla
unzip -qo vanilla/META-INF/versions/26.3/server-26.3.jar 'data/minecraft/enchantment/*' 'data/minecraft/tags/*' -d vanilla
python3 -I "$ROOT/scripts/anvil_costs.py" "$DIR/vanilla/data/minecraft" "$@"
