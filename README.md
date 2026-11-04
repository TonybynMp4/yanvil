<img src="assets/icon.svg" width="64" height="64" alt="">

# yAnvil

Datapack for **Minecraft Java 26.3** that replaces the anvil's prior-work penalty with a **fixed
price per item**, so tools and armor never become "Too Expensive" to repair.

In vanilla, every anvil use doubles an item's hidden repair cost (+1), and the anvil adds it to
every later repair. A few books and repairs later, the item can't be touched anymore. yAnvil keeps
that hidden cost pinned to a price that only depends on what the item is and how many enchantments
it has:

**price = tier base + 1 per enchantment**

The item's hidden cost is half the price, rounded up. Combining two items adds both of their hidden
costs, so the combine pays the price about once instead of twice.

| Tier | Base |
| --- | --- |
| Wood, stone, leather | 1 |
| Copper | 2 |
| Iron, chainmail, gold | 3 |
| Diamond | 5 |
| Netherite | 8 |
| Everything else (bow, crossbow, trident, mace, elytra, shield, fishing rod, shears, flint and steel, brush, carrot/warped fungus on a stick, turtle helmet, …) | 5 |

A fresh diamond sword has a price of 5 (hidden cost 3). A Sharpness V + Unbreaking III + Mending one
has a price of 8 (hidden cost 4), and it stays there however many times you repair it. The anvil
still adds the usual costs on top (material units, enchantments, renaming), and "Too Expensive" at
40 levels is still there, but with these prices no enchanting step or repair gets near it.

Enchantments are also 30% cheaper on the anvil: every enchantment's anvil cost is cut by 30%,
rounded (at least 1). The anvil only uses whole numbers, so the cut varies by enchantment:

| Enchantments | Per level from a book | Per level from an item |
| --- | --- | --- |
| Sharpness, Protection, Efficiency, Power, Piercing | 1 (vanilla 1) | 1 (vanilla 1) |
| Unbreaking, Smite, Feather Falling, Knockback, … | 1 (vanilla 1) | 1 (vanilla 2) |
| Mending, Looting, Fortune, Fire Aspect, … | 1 (vanilla 2) | 3 (vanilla 4) |
| Thorns, Silk Touch, Infinity, Soul Speed, … | 3 (vanilla 4) | 6 (vanilla 8) |


Applies to every item in `#minecraft:enchantable/durability`. Enchanted books and everything else
keep the vanilla rules. Modded and datapack enchantments count like vanilla ones.

## Costs

Cheapest way to apply every compatible enchantment from max-level books, then a full material
repair (4 units), and merging two fully enchanted copies, computed by `scripts/anvil_costs.py` from
the 26.3 enchantment data:

| Items | Hidden cost when fully enchanted | Worst enchanting step | Each full repair | Merge (vanilla) |
| --- | --- | --- | --- | --- |
| Wood/stone tools, leather armor | 3–4 | 6–10 | 7–8 | 28–77 (43–126) |
| Copper | 3–5 | 7–11 | 7–9 | 28–79 (43–126) |
| Iron, chainmail, gold | 4–5 | 7–11 | 8–9 | 30–79 (43–126) |
| Diamond | 5–6 | 8–12 | 9–10 | 32–81 (43–126) |
| Netherite | 6–8 | 12–15 | 10–12 | 34–85 (43–126) |
| Bow, crossbow, trident, mace | 5 | 8–9 | 9 | 25–45 (36–62) |
| Elytra, shield, shears, other tools | 4–5 | 8 | 8–9 | 16–36 (18–50) |
| Turtle helmet | 6 | 12 | 10 | 62 (82) |

The worst enchanting step anywhere is 15 levels (netherite armor and swords). Repairs cost the same
every time, forever. Merging two fully enchanted copies is mostly the enchantments' own cost, so
for many items it's still Too Expensive, as in vanilla. Combining items with a few enchantments
each stays well under 40: two diamond swords with Sharpness V and Unbreaking III cost 18 (vanilla
13 for fresh ones, and it grows with every use).

## How it works

Event-driven, no tick loop. yAnvil re-prices the items in your inventory, armor and offhand when:

- a durability item with no prior-work cost enters your inventory (crafted, looted, picked up,
  traded, or cleaned on a grindstone);
- you open an anvil, grindstone, smithing table or enchanting table, so everything is priced
  before it goes in;
- for a minute after that, any of your durability items changes, which catches anvil results
  (vanilla gives them 2 × hidden cost + 1) and newly enchanted or upgraded items.

Known limit: if you take an anvil result on the cursor and drop it straight back into the anvil
without it touching your inventory, it still carries the vanilla cost for that one step.

The enchantment discount replaces the vanilla enchantment definitions with copies that only change
`anvil_cost`. Another datapack that overrides a vanilla enchantment wins or loses depending on
the pack order, and modded or datapack enchantments keep their own anvil cost.

## Install

Put `yanvil-*.zip` in `<world>/datapacks/` and run `/reload` (or restart). Items already in
players' inventories are re-priced on load and the next time they open an anvil.

## Credits

The event-driven approach (an `inventory_changed` advancement that rewrites the repair cost) comes
from [Repair Cost Fixer](https://modrinth.com/datapack/repair-cost-remover) (MIT), which removes the
penalty entirely. yAnvil keeps a fixed price instead.

## License

MIT, see [LICENSE](LICENSE).
