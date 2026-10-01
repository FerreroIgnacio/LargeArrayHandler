// Block state ids of one Minecraft version to the version-agnostic strings the registry keeps:
// minecraft:chest[facing=north,type=single], properties sorted. Before 1.13 the id:meta pair goes
// through minecraft-data's flattening table, so 1.8.9 and 1.21 name the same block the same way.
const minecraftData = require('minecraft-data')

function sorted (state) {
  const bracket = state.indexOf('[')
  if (bracket < 0) return state
  const props = state.slice(bracket + 1, -1).split(',').sort()
  return state.slice(0, bracket) + '[' + props.join(',') + ']'
}

// Returns { stateName(stateId), stateIdOf(block) } for the registry's version.
module.exports = function states (registry) {
  const Block = require('prismarine-block')(registry)
  const legacy = !registry.supportFeature('theFlattening')
  const legacyTable = minecraftData.legacy.pc.blocks
  const cache = new Map()

  function stateName (stateId) {
    let name = cache.get(stateId)
    if (name !== undefined) return name

    if (legacy) {
      const idMeta = `${stateId >> 4}:${stateId & 15}`
      const flattened = legacyTable[idMeta]
      if (!flattened) throw new Error(`no flattened name for legacy block ${idMeta} in ${registry.version.minecraftVersion}`)
      name = sorted(flattened)
    } else {
      const block = Block.fromStateId(stateId, 0)
      if (!block.name) throw new Error(`unknown block state ${stateId} in ${registry.version.minecraftVersion}`)
      const props = block.getProperties()
      const keys = Object.keys(props).sort()
      name = 'minecraft:' + block.name + (keys.length ? '[' + keys.map(k => `${k}=${props[k]}`).join(',') + ']' : '')
    }

    cache.set(stateId, name)
    return name
  }

  // prismarine-chunk's state id for a mineflayer block: id << 4 | meta before 1.13.
  function stateIdOf (block) {
    return legacy ? (block.type << 4) | block.metadata : block.stateId
  }

  return { stateName, stateIdOf }
}

// The block of a state, minecraft:chest. It is also the type of the block entity it carries: the NBT
// ids differ between versions (Chest, minecraft:chest, none at all in 1.18+ chunks), the names do not.
module.exports.blockName = state => {
  const bracket = state.indexOf('[')
  return bracket < 0 ? state : state.slice(0, bracket)
}

// [[state id, name]] of every pre-flattening state (id << 4 | meta), as stateName names them: the
// same for every version the shared columns know (1.9 to 1.12), for the mod to name a slot's ids.
module.exports.legacyNames = () => Object.entries(minecraftData.legacy.pc.blocks).map(([idMeta, flattened]) => {
  const [id, meta] = idMeta.split(':').map(Number)
  if (!Number.isInteger(id) || id < 0 || id > 4095 || !Number.isInteger(meta) || meta < 0 || meta > 15) {
    throw new Error(`legacy block ${idMeta} is not an id:meta state id`)
  }
  return [(id << 4) | meta, sorted(flattened)]
})
