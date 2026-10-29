import type { SandstoneConfig } from 'sandstone'

export default {
  name: 'yAnvil',
  packs: {
    datapack: {
      // CI sets PACK_VERSION (the release tag, or dev-<sha>)
      description: `yAnvil ${process.env.PACK_VERSION ?? 'dev'}`,
      packFormat: 121,
    },
  },
  // Two resources with the same name are a bug in src/index.ts
  onConflict: {
    default: 'throw',
  },
  namespace: 'yanvil',
  packUid: 'yanvil',
  saveOptions: {},
} satisfies SandstoneConfig
