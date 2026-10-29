<img src="assets/icon.svg" width="64" height="64" alt="">

# yAnvil

Datapack for **Minecraft Java 26.3** that replaces the anvil's prior-work penalty with a **fixed
price per item**, so tools and armor never become "Too Expensive" to repair.

In vanilla, every anvil use doubles an item's hidden repair cost (+1), and the anvil adds it to
every later repair. A few books and repairs later, the item can't be touched anymore. yAnvil keeps
that hidden cost pinned to a price that only depends on what the item is and how many enchantments
it has:

**price = tier base + 1 per enchantment**

| Tier | Base |
| --- | --- |
| Wood, stone, leather | 1 |
| Copper | 2 |
| Iron, chainmail, gold | 3 |
| Diamond | 5 |
| Netherite | 8 |
| Everything else (bow, crossbow, trident, mace, elytra, shield, fishing rod, shears, flint and steel, brush, carrot/warped fungus on a stick, turtle helmet, …) | 5 |

A fresh diamond sword costs 5, a Sharpness V + Unbreaking III + Mending one costs 8, and it stays
8 however many times you repair it. The anvil still adds the usual costs on top (material units,
the books' enchantments, renaming), and "Too Expensive" at 40 levels is still there, but with these
prices no step gets near it.

Applies to every item in `#minecraft:enchantable/durability`. Enchanted books and everything else
keep the vanilla rules. Modded and datapack enchantments count like vanilla ones.

## Costs

Cheapest way to apply every compatible enchantment from max-level books, then a full material
repair (4 units), computed by `scripts/anvil_costs.py` from the 26.3 enchantment data:

| Items | Price when fully enchanted | Worst single step | Each full repair |
| --- | --- | --- | --- |
| Wood/stone tools, leather armor | 5–8 | 9–14 | 9–12 |
| Copper | 6–9 | 10–15 | 10–13 |
| Iron, chainmail, gold | 7–10 | 11–19 | 11–14 |
| Diamond | 9–12 | 15–21 | 13–16 |
| Netherite | 12–15 | 18–28 | 16–19 |
| Bow, crossbow, trident, mace | 9–10 | 13–19 | 13–14 |
| Elytra, shield, shears, other tools | 7–9 | 11–16 | 11–13 |
| Turtle helmet | 11 | 20 | 15 |

The worst step anywhere is 28 levels (netherite helmet and boots). Repairs cost the same every
time, forever.

## How it works

Event-driven, no tick loop. yAnvil re-prices the items in your inventory, armor and offhand when:

- a durability item with no prior-work cost enters your inventory (crafted, looted, picked up,
  traded, or cleaned on a grindstone);
- you open an anvil, grindstone, smithing table or enchanting table, so everything is priced
  before it goes in;
- for a minute after that, any of your durability items changes, which catches anvil results
  (vanilla gives them 2 × price + 1) and newly enchanted or upgraded items.

Known limit: if you take an anvil result on the cursor and drop it straight back into the anvil
without it touching your inventory, it still carries the vanilla cost for that one step.

## Install

Put `yanvil-*.zip` in `<world>/datapacks/` and run `/reload` (or restart). Items already in
players' inventories are re-priced on load and the next time they open an anvil.

## Credits

The event-driven approach (an `inventory_changed` advancement that rewrites the repair cost) comes
from [Repair Cost Fixer](https://modrinth.com/datapack/repair-cost-remover) (MIT), which removes the
penalty entirely. yAnvil keeps a fixed price instead.

## License

MIT, see [LICENSE](LICENSE).
