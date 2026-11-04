#!/usr/bin/env bash
# Extracts the vanilla data from the pinned 26.3 server jar and runs scripts/anvil_costs.py on it:
# prints the vanilla vs yAnvil cost table and fails if any yAnvil step would be Too Expensive.
# Also fails if src/vanilla/enchantment (the definitions the pack discounts) differs from the jar's;
# UPDATE=1 refreshes it instead.
# Usage: [UPDATE=1] scripts/anvil-costs.sh [--json]   (WORKDIR sets the scratch dir, default /tmp/yanvil-ci)
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
if [ "${UPDATE:-}" = 1 ]; then
  rm -rf "$ROOT/src/vanilla/enchantment"; mkdir -p "$ROOT/src/vanilla"
  cp -r vanilla/data/minecraft/enchantment "$ROOT/src/vanilla/"
elif ! diff -r vanilla/data/minecraft/enchantment "$ROOT/src/vanilla/enchantment" >&2; then
  echo "src/vanilla/enchantment differs from the 26.3 jar: rerun with UPDATE=1" >&2
  exit 1
fi
python3 -I "$ROOT/scripts/anvil_costs.py" "$DIR/vanilla/data/minecraft" "$@"
