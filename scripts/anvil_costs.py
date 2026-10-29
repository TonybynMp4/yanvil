"""Cheapest anvil plans for fully enchanting each durability item, vanilla vs yAnvil.

Reads the vanilla enchantments and tags from an extracted 26.3 server jar
(META-INF/versions/26.3/server-26.3.jar -> data/minecraft) and the tier table from src/tiers.json.
For each item it finds the most valuable compatible enchantment set (no curses), then DP-searches
every order of combining the item with one max-level book per enchantment. Books keep the vanilla
prior-work penalty (2^n - 1); in yAnvil the item side always costs its price.

Usage: python3 -I scripts/anvil_costs.py <data/minecraft dir> [--json]
Prints a table, and exits 1 if any item can't be fully enchanted under yAnvil with every step
below 40 ("Too Expensive").
"""
import glob
import itertools
import json
import os
import sys

D = sys.argv[1]
AS_JSON = '--json' in sys.argv
CONFIG = json.load(open(os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'src', 'tiers.json')))
TOO_EXPENSIVE = 40
REPAIR_UNITS = 4  # a full material repair takes 4 units, 1 level each

E = {os.path.basename(f)[:-5]: json.load(open(f)) for f in glob.glob(f'{D}/enchantment/*.json')}


def strip(v):
    return v.removeprefix('minecraft:')


def tag_items(tag):
    out = set()
    for v in json.load(open(f'{D}/tags/item/{tag}.json'))['values']:
        v = v if isinstance(v, str) else v['id']
        out |= tag_items(strip(v[1:])) if v.startswith('#') else {strip(v)}
    return out


def item_set(ref):
    if isinstance(ref, list):
        return set().union(*(item_set(r) for r in ref))
    return tag_items(strip(ref[1:])) if ref.startswith('#') else {strip(ref)}


def ench_tag(path):
    return {strip(v) for v in json.load(open(path))['values']}


curses = ench_tag(f'{D}/tags/enchantment/curse.json')
exclusive = {f'#minecraft:exclusive_set/{os.path.basename(f)[:-5]}': ench_tag(f)
             for f in glob.glob(f'{D}/tags/enchantment/exclusive_set/*.json')}
supported = {e: item_set(j['supported_items']) for e, j in E.items()}


def compatible(a, b):
    xa, xb = E[a].get('exclusive_set'), E[b].get('exclusive_set')
    return not ((xa and b in exclusive[xa]) or (xb and a in exclusive[xb]))


def book_value(e):
    """Level cost of applying the max-level book (book sacrifices cost half, at least 1 per level)"""
    return max(1, E[e]['anvil_cost'] // 2) * E[e]['max_level']


def best_set(item):
    cands = sorted(e for e in E if e not in curses and item in supported[e])
    for r in range(len(cands), 0, -1):
        sets = [c for c in itertools.combinations(cands, r)
                if all(compatible(a, b) for a, b in itertools.combinations(c, 2))]
        if sets:
            return list(max(sets, key=lambda c: (sum(book_value(e) for e in c), c)))
    return []


def tier_of(item):
    for name, t in CONFIG['tiers'].items():
        if any(item == p + k for p in t['prefixes'] for k in CONFIG['kinds']):
            return name, t['base']
    return 'other', CONFIG['default']


INF = (10**9, 10**9)


def plan(ench, mode, base=0):
    """(total levels, worst step) of the cheapest order with every step < 40, or None.
    Leaf 0 is the item, leaves 1..n the books; the item always stays on the left."""
    n = len(ench)
    full = (1 << (n + 1)) - 1
    per = CONFIG['perEnchantment']
    dp = {(1 << i, 0): (0, 0) for i in range(n + 1)}  # (mask, prior-work uses) -> (total, worst)

    def count(mask):
        return bin(mask >> 1).count('1')

    def value(mask):
        return sum(book_value(ench[i]) for i in range(n) if mask >> (i + 1) & 1)

    for S in sorted(range(1, full + 1), key=lambda m: bin(m).count('1')):
        if bin(S).count('1') < 2:
            continue
        sub = (S - 1) & S
        while sub:
            L, R = sub, S ^ sub
            if not R & 1:
                lefts = [(w, v) for (m, w), v in dp.items() if m == L]
                rights = [(w, v) for (m, w), v in dp.items() if m == R]
                for wl, v1 in lefts:
                    for wr, v2 in rights:
                        lp = base + per * count(L) if mode == 'yanvil' and L & 1 else 2**wl - 1
                        step = lp + (2**wr - 1) + value(R)
                        if step >= TOO_EXPENSIVE:
                            continue
                        w = 0 if mode == 'yanvil' and S & 1 else max(wl, wr) + 1
                        cand = (v1[0] + v2[0] + step, max(v1[1], v2[1], step))
                        if cand < dp.get((S, w), INF):
                            dp[(S, w)] = cand
            sub = (sub - 1) & S
    res = [(v, w) for (m, w), v in dp.items() if m == full]
    if not res:
        return None
    (total, worst), w = min(res)
    return {'total': total, 'worst': worst, 'uses': w}


rows = []
cache = {}
for item in sorted(tag_items('enchantable/durability')):
    ench = best_set(item)
    tier, base = tier_of(item)
    key = (tuple(ench), base)
    if key not in cache:
        van = plan(ench, 'vanilla')
        yan = plan(ench, 'yanvil', base)
        price = base + CONFIG['perEnchantment'] * len(ench)
        cache[key] = {
            'vanilla': van,
            # repairing with 4 units of material after the full enchant
            'vanilla_repair': van and (2**van['uses'] - 1) + REPAIR_UNITS,
            'yanvil': yan,
            'price': price,
            'yanvil_repair': price + REPAIR_UNITS,
        }
    rows.append({'item': item, 'tier': tier, 'enchantments': [f"{e} {E[e]['max_level']}" for e in ench], **cache[key]})

# Every tier prefix must match at least one real item
for name, t in CONFIG['tiers'].items():
    for p in t['prefixes']:
        if not any(r['item'].startswith(p) and r['tier'] == name for r in rows):
            print(f'tier {name}: prefix {p} matches no durability item', file=sys.stderr)
            sys.exit(1)

if AS_JSON:
    json.dump(rows, sys.stdout, indent=1)
else:
    fmt = '{:<28} {:<9} {:>2}  {:>13}  {:>13}  {:>5}  {:>6}'
    print(fmt.format('item', 'tier', 'n', 'vanilla t/max', 'yAnvil t/max', 'price', 'repair'))
    for r in rows:
        v, y = r['vanilla'], r['yanvil']
        print(fmt.format(r['item'], r['tier'], len(r['enchantments']),
                         f"{v['total']}/{v['worst']}" if v else 'too expensive',
                         f"{y['total']}/{y['worst']}" if y else 'too expensive',
                         r['price'], r['yanvil_repair']))
bad = [r['item'] for r in rows if not r['yanvil'] or r['yanvil']['worst'] >= TOO_EXPENSIVE]
worst = max(rows, key=lambda r: r['yanvil']['worst'] if r['yanvil'] else 99)
print(f"\nworst yAnvil step: {worst['yanvil']['worst'] if worst['yanvil'] else '-'} ({worst['item']}); "
      f"full-enchant repair: {min(r['yanvil_repair'] for r in rows)}-{max(r['yanvil_repair'] for r in rows)}",
      file=sys.stderr)
if bad:
    print(f"too expensive under yAnvil: {', '.join(bad)}", file=sys.stderr)
    sys.exit(1)
