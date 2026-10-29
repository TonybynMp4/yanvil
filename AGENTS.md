# yAnvil

Datapack for Minecraft Java 26.3, meant to be published (data pack format 121): replaces the
anvil's exponential prior-work penalty with a fixed price per item, so repairs stay viable forever.
Written in TypeScript with [Sandstone](https://sandstone.dev) 1.2 (which targets 26.3). MIT.

- Source: `src/index.ts`, config in `sandstone.config.ts`.
- Build: `bun run build > /dev/null 2>&1`, output goes to `.sandstone/output/datapack/`
  (log in `.sandstone/`). `bun run watch` rebuilds on change.
- Typecheck: `bun run typecheck`
- How it works: every item in `#minecraft:enchantable/durability` gets its `minecraft:repair_cost`
  pinned to `tier base + perEnchantment x enchantment count`. The tier table is
  `src/tiers.json`, shared with `scripts/anvil_costs.py`; change prices only there. Enchanted books
  and everything else stay vanilla. Three advancements trigger it (no tick loop):
  `fresh` (a durability item with no repair_cost enters the inventory), `station` (opened an anvil,
  grindstone, smithing table or enchanting table: fixes everything and sets `yanvil.busy`) and
  `busy` (any durability item change while busy; clears after 1200 idle ticks). Each reward runs
  `yanvil:fix` (one `slot/<slot>` function per player slot) and revokes its advancement last.
- 26.3 formats: loot conditions and item modifiers use `type` (not `condition`/`function`), and
  advancement `player`/`location` conditions are one object, not a list.
- Load test: `scripts/load-test.sh` boots a real 26.3 server with the built pack and prices armor
  on a mannequin (needs Java 25).
- Player test: `scripts/player-test.sh` runs `harness/PlayerTest.java` in the server's JVM with a
  mock player: pickups, opening an anvil, `/enchant`, two anvil combines, the busy flag clearing.
- Costs: `scripts/anvil-costs.sh` extracts the vanilla enchantments from the server jar and runs
  `scripts/anvil_costs.py`: the cheapest way to fully enchant each item, vanilla vs yAnvil. It fails
  if any yAnvil step would be 40+ ("Too Expensive"). Rerun it after changing `src/tiers.json` and
  update the README tables.
- CI: `.github/workflows/build.yml` typechecks, builds, load-tests, player-tests and checks costs
  on every push/PR, and uploads the pack as an artifact. `release.yml` (run by hand with a
  patch/minor/major bump, or on pushing a semver tag like `1.2.0` or `v1.2.0`) reruns those jobs,
  then publishes `yanvil-<tag>.zip` with `.github/release-notes.md` plus the commits since the last
  version tag.
- Sandstone docs: https://sandstone.dev, and the typed API in `node_modules/sandstone/src`.

## Sources of information

Use these as the references for Minecraft and datapack/resource pack work, and check them
before relying on memory — data-driven mechanics change a lot between versions:

- [datapack.wiki](https://datapack.wiki) — datapack/resource pack guides and concepts
  (commands, functions, recipes, custom items, worldgen, breaking changes between versions).
- [minecraft.wiki](https://minecraft.wiki) — game mechanics and exact file formats
  (e.g. [Data component format](https://minecraft.wiki/w/Data_component_format),
  [Recipe](https://minecraft.wiki/w/Recipe_(Java_Edition)), entity and item pages).

When the two disagree or are vague, prefer minecraft.wiki for exact formats, and confirm
against the game itself (a test server, or the decompiled 26.3 jar) when it matters.
