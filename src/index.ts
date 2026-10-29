import { Advancement, MCFunction, Objective, Tag, raw } from 'sandstone'
import config from './tiers.json'

// yAnvil: pins every durability item's repair_cost to a fixed price, so the anvil's prior-work
// penalty never grows. Price = tier base + perEnchantment x enchantment count (src/tiers.json).

const DURABILITY = '#minecraft:enchantable/durability'
/** Idle ticks after the last fix before the busy flag clears (see on_busy) */
const BUSY_WINDOW = 1200

// Sandstone prefixes objective names with the namespace: 'tmp' -> 'yanvil.tmp'
const tmp = Objective.create('tmp', 'dummy')
const busy = Objective.create('busy', 'dummy')
const until = Objective.create('until', 'dummy')

// One item tag per tier that isn't the default. Prefix x kind lists ids that don't exist
// (wooden_helmet), so the entries are optional
const tiers = Object.entries(config.tiers).filter(([, t]) => t.base !== config.default)
for (const [name, tier] of tiers) {
  Tag('item', `yanvil:tier/${name}`, tier.prefixes.flatMap((p) =>
    config.kinds.map((k) => ({ id: `minecraft:${p}${k}`, required: false }))))
}

Tag('block', 'yanvil:stations', [
  '#minecraft:anvil', 'minecraft:grindstone', 'minecraft:smithing_table', 'minecraft:enchanting_table',
])

// Player slots: item command slot -> NBT path in a copy of the player (Inventory holds 0-35,
// equipment the armor and offhand)
const SLOTS: [string, string][] = [
  ...Array.from({ length: 36 }, (_, i): [string, string] => [`container.${i}`, `Inventory[{Slot:${i}b}]`]),
  ['armor.head', 'equipment.head'],
  ['armor.chest', 'equipment.chest'],
  ['armor.legs', 'equipment.legs'],
  ['armor.feet', 'equipment.feet'],
  ['weapon.offhand', 'equipment.offhand'],
]

const set = MCFunction('set', () => {
  raw('$item modify entity @s $(slot) {type:"minecraft:set_components",components:{"minecraft:repair_cost":$(price)}}')
  raw(`scoreboard players add #fixed ${tmp} 1`)
})

const slotFns = SLOTS.map(([slot, path]) => {
  const at = `storage yanvil:fix p.${path}.components`
  return [slot, MCFunction(`slot/${slot.replace('.', '_')}`, () => {
    raw(`scoreboard players set #price ${tmp} ${config.default}`)
    for (const [name, tier] of tiers) {
      raw(`execute if items entity @s ${slot} #yanvil:tier/${name} run scoreboard players set #price ${tmp} ${tier.base}`)
    }
    // data get on a compound returns its entry count; a missing component fails and stores 0
    raw(`execute store result score #n ${tmp} run data get ${at}."minecraft:enchantments"`)
    if (config.perEnchantment !== 1) raw(`scoreboard players operation #n ${tmp} *= #per ${tmp}`)
    raw(`scoreboard players operation #price ${tmp} += #n ${tmp}`)
    raw(`execute store result score #rc ${tmp} run data get ${at}."minecraft:repair_cost"`)
    raw(`execute if score #rc ${tmp} = #price ${tmp} run return 0`)
    raw(`data modify storage yanvil:fix args.slot set value "${slot}"`)
    raw(`execute store result storage yanvil:fix args.price int 1 run scoreboard players get #price ${tmp}`)
    raw(`function ${set} with storage yanvil:fix args`)
  })] as const
})

/** Sets the price of every durability item @s carries; #fixed counts the ones that changed */
const fix = MCFunction('fix', () => {
  raw(`scoreboard players set #fixed ${tmp} 0`)
  // One copy of the player instead of reading the entity per slot
  raw('data modify storage yanvil:fix p set from entity @s')
  for (const [slot, fn] of slotFns) raw(`execute if items entity @s ${slot} ${DURABILITY} run function ${fn}`)
  raw('data remove storage yanvil:fix p')
})

// Each reward revokes its advancement last: while it's granted, the item modify calls in fix
// can't retrigger it.

// A durability item with no repair_cost entered the inventory: crafted, looted, picked up,
// traded, or fully cleaned on a grindstone
const fresh = Advancement('fresh', {
  criteria: {
    fresh: {
      trigger: 'minecraft:inventory_changed',
      conditions: { items: [{ items: DURABILITY, components: { 'minecraft:repair_cost': 0 } }] },
    },
  },
  rewards: { function: MCFunction('on_fresh', () => {
    raw(`function ${fix}`)
    raw('advancement revoke @s only yanvil:fresh')
  }) },
} as any)

// Opened an anvil, grindstone, smithing table or enchanting table: fix everything before it goes
// in, then watch inventory changes for a while to catch what comes out (anvil results come out at
// 2 x price + 1)
const station = Advancement('station', {
  criteria: {
    open: {
      trigger: 'minecraft:default_block_use',
      conditions: {
        location: { type: 'minecraft:match_block', blocks: '#yanvil:stations' },
      },
    },
  },
  rewards: { function: MCFunction('on_station', () => {
    raw(`function ${fix}`)
    raw(`scoreboard players set @s ${busy} 1`)
    raw(`execute store result score @s ${until} run time query gametime`)
    raw(`scoreboard players add @s ${until} ${BUSY_WINDOW}`)
    raw('advancement revoke @s only yanvil:station')
  }) },
} as any)

// While busy, any change to a durability item refixes. The flag clears on the first change that
// fixes nothing once BUSY_WINDOW ticks have passed since the last fix
const busyAdv = Advancement('busy', {
  criteria: {
    changed: {
      trigger: 'minecraft:inventory_changed',
      conditions: {
        player: { type: 'minecraft:entity_scores', entity: 'this', scores: { [`${busy}`]: { min: 1 } } },
        items: [{ items: DURABILITY }],
      },
    },
  },
  rewards: { function: MCFunction('on_busy', () => {
    raw(`function ${fix}`)
    raw(`execute store result score #now ${tmp} run time query gametime`)
    raw(`execute if score #fixed ${tmp} matches 1.. store result score @s ${until} run scoreboard players add #now ${tmp} ${BUSY_WINDOW}`)
    raw(`execute if score #fixed ${tmp} matches 0 if score #now ${tmp} > @s ${until} run scoreboard players set @s ${busy} 0`)
    raw('advancement revoke @s only yanvil:busy')
  }) },
} as any)

MCFunction('load', () => {
  raw(`scoreboard players set #per ${tmp} ${config.perEnchantment}`)
  for (const adv of [fresh, station, busyAdv]) raw(`advancement revoke @a only ${adv}`)
  raw(`execute as @a run function ${fix}`)
}, { runOnLoad: true })
