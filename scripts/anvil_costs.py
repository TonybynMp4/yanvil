"""Cheapest anvil plans for fully enchanting each durability item, vanilla vs yAnvil.

Reads the vanilla enchantments and tags from an extracted 26.3 server jar
(META-INF/versions/26.3/server-26.3.jar -> data/minecraft) and the tier table from src/tiers.json.
For each item it finds the most valuable compatible enchantment set (no curses), then DP-searches
every order of combining the item with one max-level book per enchantment. Books keep the vanilla
prior-work penalty (2^n - 1); in yAnvil the item side always costs its price.

Usage: python3 -I scripts/anvil_costs.py <data/minecraft dir> [--json]
Prints a table, and exits 1 if any item can't be fully enchanted under yAnvil with every step
below 40 ("Too Expensive"), or if its full repair would cost 40 or more.
"""
import glob
import itertools
import json
import os
import sys

CONFIG_PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'src', 'tiers.json')
TOO_EXPENSIVE = 40
REPAIR_UNITS = 4  # a full material repair takes 4 units, 1 level each
INF = (10**9, 10**9)


def load_json(path):
    with open(path) as f:
        return json.load(f)


def strip(v):
    return v.removeprefix('minecraft:')


def popcount(mask):
    return bin(mask).count('1')


class Vanilla:
    """The enchantments and tags in an extracted data/minecraft directory"""

    def __init__(self, root):
        self.root = root
        self.tags = {}
        self.enchantments = {os.path.basename(f)[:-5]: load_json(f) for f in glob.glob(f'{root}/enchantment/*.json')}
        self.curses = self.tag('enchantment', 'curse')
        self.supported = {e: self.resolve('item', j['supported_items']) for e, j in self.enchantments.items()}

    def resolve(self, registry, ref):
        """Ids a registry reference stands for: an id, a #tag or a list of them"""
        if isinstance(ref, list):
            return set().union(*(self.resolve(registry, r) for r in ref))
        if isinstance(ref, dict):  # tag entry {"id": ..., "required": false}
            ref = ref['id']
        return self.tag(registry, strip(ref[1:])) if ref.startswith('#') else {strip(ref)}

    def tag(self, registry, name):
        if (registry, name) not in self.tags:
            path = f'{self.root}/tags/{registry}/{name}.json'
            self.tags[registry, name] = self.resolve(registry, load_json(path)['values'])
        return self.tags[registry, name]

    def compatible(self, a, b):
        xa, xb = self.enchantments[a].get('exclusive_set'), self.enchantments[b].get('exclusive_set')
        return not ((xa and b in self.resolve('enchantment', xa)) or (xb and a in self.resolve('enchantment', xb)))

    def book_value(self, e):
        """Level cost of applying the max-level book (book sacrifices cost half, at least 1 per level)"""
        return max(1, self.enchantments[e]['anvil_cost'] // 2) * self.enchantments[e]['max_level']

    def best_set(self, item):
        cands = sorted(e for e in self.enchantments if e not in self.curses and item in self.supported[e])
        for r in range(len(cands), 0, -1):
            sets = [c for c in itertools.combinations(cands, r)
                    if all(self.compatible(a, b) for a, b in itertools.combinations(c, 2))]
            if sets:
                return list(max(sets, key=lambda c: (sum(self.book_value(e) for e in c), c)))
        return []


def tier_of(config, item):
    for name, t in config['tiers'].items():
        if any(item == p + k for p in t['prefixes'] for k in config['kinds']):
            return name, t['base']
    return 'other', config['default']


def plan(values, mode, base=0, per=1):
    """(total levels, worst step) of the cheapest order with every step < 40, or None.
    values are the book values of the enchantments. Leaf 0 is the item, leaves 1..n the books;
    the item always stays on the left."""
    n = len(values)
    full = (1 << (n + 1)) - 1
    yanvil = mode == 'yanvil'
    # Level cost of the books in each subset of leaves
    value = [sum(values[i] for i in range(n) if mask >> (i + 1) & 1) for mask in range(full + 1)]
    dp = {1 << i: {0: (0, 0)} for i in range(n + 1)}  # mask -> {prior-work uses: (total, worst)}

    for S in sorted(range(1, full + 1), key=popcount):
        if popcount(S) < 2:
            continue
        best = {}
        sub = (S - 1) & S
        while sub:
            L, R = sub, S ^ sub
            if not R & 1 and L in dp and R in dp:
                for wl, (tl, ml) in dp[L].items():
                    lp = base + per * popcount(L >> 1) if yanvil and L & 1 else 2**wl - 1
                    for wr, (tr, mr) in dp[R].items():
                        step = lp + (2**wr - 1) + value[R]
                        if step >= TOO_EXPENSIVE:
                            continue
                        w = 0 if yanvil and S & 1 else max(wl, wr) + 1
                        cand = (tl + tr + step, max(ml, mr, step))
                        if cand < best.get(w, INF):
                            best[w] = cand
            sub = (sub - 1) & S
        if best:
            dp[S] = best
    if full not in dp:
        return None
    (total, worst), w = min((v, w) for w, v in dp[full].items())
    return {'total': total, 'worst': worst, 'uses': w}


def main(argv):
    args = [a for a in argv if a != '--json']
    if len(args) != 1:
        sys.exit(__doc__)
    vanilla = Vanilla(args[0])
    config = load_json(CONFIG_PATH)
    per = config['perEnchantment']

    rows = []
    cache = {}
    for item in sorted(vanilla.tag('item', 'enchantable/durability')):
        ench = vanilla.best_set(item)
        tier, base = tier_of(config, item)
        key = (tuple(ench), base)
        if key not in cache:
            values = [vanilla.book_value(e) for e in ench]
            van = plan(values, 'vanilla')
            yan = plan(values, 'yanvil', base, per)
            price = base + per * len(ench)
            cache[key] = {
                'vanilla': van,
                # repairing with 4 units of material after the full enchant
                'vanilla_repair': (2**van['uses'] - 1) + REPAIR_UNITS if van else None,
                'yanvil': yan,
                'price': price,
                'yanvil_repair': price + REPAIR_UNITS,
            }
        rows.append({'item': item, 'tier': tier,
                     'enchantments': [f"{e} {vanilla.enchantments[e]['max_level']}" for e in ench], **cache[key]})

    # Every tier prefix must match at least one real item
    for name, t in config['tiers'].items():
        for p in t['prefixes']:
            if not any(r['item'].startswith(p) and r['tier'] == name for r in rows):
                sys.exit(f'tier {name}: prefix {p} matches no durability item')

    if '--json' in argv:
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
    worst = max(rows, key=lambda r: r['yanvil']['worst'] if r['yanvil'] else 99)
    print(f"\nworst yAnvil step: {worst['yanvil']['worst'] if worst['yanvil'] else '-'} ({worst['item']}); "
          f"full-enchant repair: {min(r['yanvil_repair'] for r in rows)}-{max(r['yanvil_repair'] for r in rows)}",
          file=sys.stderr)
    # plan() only keeps steps below 40, so a missing plan means some step would be Too Expensive
    bad = [r['item'] for r in rows if not r['yanvil']]
    bad_repair = [r['item'] for r in rows if r['yanvil_repair'] >= TOO_EXPENSIVE]
    if bad:
        print(f"too expensive to enchant under yAnvil: {', '.join(bad)}", file=sys.stderr)
    if bad_repair:
        print(f"too expensive to repair under yAnvil: {', '.join(bad_repair)}", file=sys.stderr)
    if bad or bad_repair:
        sys.exit(1)


if __name__ == '__main__':
    main(sys.argv[1:])
