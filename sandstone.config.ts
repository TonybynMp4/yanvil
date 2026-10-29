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
  onConflict: {
    default: 'warn',
  },
  namespace: 'yanvil',
  packUid: 'yanvil',
  mcmeta: 'latest',
  saveOptions: {},
} as SandstoneConfig
